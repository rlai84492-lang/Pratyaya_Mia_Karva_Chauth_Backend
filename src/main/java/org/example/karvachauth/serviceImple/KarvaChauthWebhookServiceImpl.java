package org.example.karvachauth.serviceImple;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.example.karvachauth.entity.Message;
import org.example.karvachauth.entity.MessageStatusEvent;
import org.example.karvachauth.entity.ProcessedMessage;
import org.example.karvachauth.repository.MessageRepository;
import org.example.karvachauth.repository.MessageStatusEventRepository;
import org.example.karvachauth.repository.ProcessedMessageRepository;
import org.example.karvachauth.service.BotEngineService;
import org.example.karvachauth.service.KarvaChauthWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.time.OffsetDateTime;

import static org.example.karvachauth.constants.KarvaChauthConstants.*;

/**
 * Receives every Karix callback. Mode = karvachauth.servicebus.enabled:
 *
 *   true  (QUEUE)  — validate -> Service Bus -> 200. KarvaQueueConsumer reads the queues and calls
 *                    processQueuedInbound / processQueuedStatus. Nothing is lost if the app stops:
 *                    messages wait in the queue; failures are retried, then go to the DLQ.
 *
 *   false (DIRECT) — validate -> 200, then handled here on a background lane (no Service Bus).
 *                    In-flight messages are lost if the app stops mid-reply.
 *
 * Both modes run the SAME logic (handleInbound / handleStatus). One customer's messages are always
 * handled in order (QUEUE: SessionId = mobile, DIRECT: one lane per mobile).
 */
@Service
public class KarvaChauthWebhookServiceImpl implements KarvaChauthWebhookService {

    private static final Logger log = LoggerFactory.getLogger(KarvaChauthWebhookServiceImpl.class);
    private static final int STATUS_MAX_RETRY = 3;   // DR before its message row exists -> re-check 3 times

    /** Status order. messages.status only moves to a HIGHER number (never back). */
    private static final Map<String, Integer> STATUS_RANK = Map.of(
            MSG_STATUS_SENT, 1,
            MSG_STATUS_DELIVERED, 2,
            MSG_STATUS_READ, 3,
            MSG_STATUS_FAILED, 4);

    /** Thread-safe, shared. Spring Boot 4 has no Jackson-2 bean, so one is created here. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EV_INBOUND = "INBOUND_MESSAGE";
    private static final String EV_STATUS  = "DELIVERY_STATUS";
    private static final String EV_UNKNOWN = "UNKNOWN";

    private final BotEngineService botEngineService;
    private final MessageRepository messageRepository;
    private final MessageStatusEventRepository statusEventRepository;   // one row per status (history)
    private final ProcessedMessageRepository processedMessageRepository;

    private final boolean serviceBusEnabled;
    private final ServiceBusSenderClient inboundSender;   // customer messages (SessionId = mobile)
    private final ServiceBusSenderClient statusSender;    // delivery / read receipts

    private final String webhookToken;
    private final boolean authEnabled;
    private final int maxBodyBytes;

    private final Set<String> wifePayloads;
    private final Set<String> husbandPayloads;
    private final Set<String> sparklePayloads;

    /** DIRECT mode lanes. Same mobile -> same lane -> her messages stay in order. Bounded -> no OOM. */
    private final ThreadPoolExecutor[] lanes;

    public KarvaChauthWebhookServiceImpl(
            BotEngineService botEngineService,
            MessageRepository messageRepository,
            MessageStatusEventRepository statusEventRepository,
            ProcessedMessageRepository processedMessageRepository,
            @Value("${karvachauth.servicebus.enabled:true}") boolean serviceBusEnabled,
            @Value("${karvachauth.servicebus.connection-string:}") String sbConnectionString,
            @Value("${karvachauth.servicebus.inbound-queue:karvachauth-inbound}") String inboundQueue,
            @Value("${karvachauth.servicebus.status-queue:karvachauth-status}") String statusQueue,
            @Value("${karvachauth.webhook.auth-token:}") String webhookToken,
            @Value("${karvachauth.webhook.auth-enabled:true}") boolean authEnabled,
            @Value("${karvachauth.webhook.max-body-bytes:262144}") int maxBodyBytes,
            @Value("${karvachauth.direct.lanes:16}") int laneCount,
            @Value("${karvachauth.direct.lane-queue-capacity:5000}") int laneQueueCapacity,
            @Value("${karvachauth.step0.wife-payloads:BTN_CELEBRATING}") String wifePayloads,
            @Value("${karvachauth.step0.husband-payloads:BTN_SHOPPING_FOR_HER}") String husbandPayloads,
            @Value("${karvachauth.step0.sparkle-payloads:BTN_SPARKLE}") String sparklePayloads) {

        this.botEngineService = botEngineService;
        this.messageRepository = messageRepository;
        this.statusEventRepository = statusEventRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.serviceBusEnabled = serviceBusEnabled;
        this.webhookToken = webhookToken;
        this.authEnabled = authEnabled;
        this.maxBodyBytes = maxBodyBytes;
        this.wifePayloads = toSet(wifePayloads);
        this.husbandPayloads = toSet(husbandPayloads);
        this.sparklePayloads = toSet(sparklePayloads);

        if (!serviceBusEnabled) {
            this.inboundSender = null;
            this.statusSender = null;
            this.lanes = new ThreadPoolExecutor[Math.max(1, laneCount)];
            int capacity = Math.max(100, laneQueueCapacity);
            for (int i = 0; i < lanes.length; i++) {
                final int n = i;
                lanes[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(capacity),
                        r -> {
                            Thread t = new Thread(r, "kc-lane-" + n);
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());   // full -> 503 -> Karix retries
            }
            log.warn("DIRECT MODE (servicebus.enabled=false): {} lanes x {} queue. "
                    + "In-flight messages are lost if the app stops mid-reply.", lanes.length, capacity);
            return;
        }
        this.lanes = null;

        if (sbConnectionString == null || sbConnectionString.isBlank()) {
            throw new IllegalStateException("karvachauth.servicebus.connection-string is empty. "
                    + "Set it, or set karvachauth.servicebus.enabled=false for direct mode.");
        }

        AmqpRetryOptions retry = new AmqpRetryOptions()
                .setMode(AmqpRetryMode.EXPONENTIAL)
                .setMaxRetries(2)
                .setDelay(Duration.ofMillis(200))
                .setMaxDelay(Duration.ofSeconds(1))
                .setTryTimeout(Duration.ofSeconds(3));

        ServiceBusClientBuilder builder = new ServiceBusClientBuilder()
                .connectionString(sbConnectionString)
                .retryOptions(retry);

        this.inboundSender = builder.sender().queueName(inboundQueue).buildClient();
        this.statusSender = builder.sender().queueName(statusQueue).buildClient();
        log.info("QUEUE MODE: Service Bus senders ready: inbound={}, status={}", inboundQueue, statusQueue);
    }

    // ==================================================================
    // 1. WEBHOOK — validate, then queue (QUEUE) or lane (DIRECT). Must return fast.
    // ==================================================================

    @Override
    public WebhookResult handleWebhook(String eventId, String rawBody, String token) {
        long start = System.nanoTime();
        long receivedAtMs = System.currentTimeMillis();     // ADD
        // ---------- VALIDATE ----------
        if (!isAuthorized(token)) {
            log.warn("stage=REJECTED eventId={} reason=INVALID_TOKEN", eventId);
            return WebhookResult.UNAUTHORIZED;
        }


        // UTF-8 bytes >= chars, so a char count over the limit is always too big; exact count only near the limit
        int chars = rawBody == null ? 0 : rawBody.length();
        if (chars == 0 || chars > maxBodyBytes
                || (chars * 3L > maxBodyBytes && rawBody.getBytes(StandardCharsets.UTF_8).length > maxBodyBytes)) {
            log.warn("stage=REJECTED eventId={} reason=BAD_SIZE chars={}", eventId, chars);
            return WebhookResult.IGNORED;
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn("stage=REJECTED eventId={} reason=NOT_JSON", eventId);
            return WebhookResult.IGNORED;
        }
        if (root == null || !root.isObject()) {
            log.warn("stage=REJECTED eventId={} reason=NOT_JSON_OBJECT", eventId);
            return WebhookResult.IGNORED;
        }

        // ---------- IDENTIFY (real Karix format) ----------
        JsonNode message = root.path("eventContent").path("message");
        JsonNode notification = root.path("notificationAttributes");

        String eventType;
        String providerMessageId;
        String mobile;
        String dedupeSource;

        if (message.isObject()) {
            eventType = EV_INBOUND;
            providerMessageId = text(message, "id");
            mobile = digits(text(message, "from"));
            dedupeSource = "IN|" + (providerMessageId != null ? providerMessageId : rawBody);
        } else if (notification.isObject()) {
            eventType = EV_STATUS;
            providerMessageId = text(root.path("events"), "mid");
            mobile = digits(text(root.path("recipient"), "to"));
            dedupeSource = "ST|" + (providerMessageId != null
                    ? providerMessageId + "|" + text(notification, "status") : rawBody);
        } else {
            eventType = EV_UNKNOWN;
            providerMessageId = null;
            mobile = null;
            dedupeSource = null;
        }

        if (EV_UNKNOWN.equals(eventType)) {
            log.info("stage=IGNORED eventId={} reason=UNKNOWN_EVENT", eventId);
            return WebhookResult.IGNORED;
        }
        if (EV_INBOUND.equals(eventType) && mobile == null) {
            log.warn("stage=REJECTED eventId={} reason=NO_MOBILE", eventId);
            return WebhookResult.IGNORED;
        }
        if (EV_STATUS.equals(eventType) && providerMessageId == null) {
            log.warn("stage=REJECTED eventId={} reason=NO_MID", eventId);
            return WebhookResult.IGNORED;
        }

        // Detail only in DEBUG — at lakhs of events, INFO stays one line per event
        if (log.isDebugEnabled()) {
            log.debug("stage=VALIDATED eventId={} eventType={} messageType={} id={} mobile={} status={} templateId={}",
                    eventId, eventType, text(message, "messageType"), providerMessageId, mask(mobile),
                    text(notification, "status"), text(root, "templateId"));
        }

        // ---------- DIRECT MODE ----------
        if (!serviceBusEnabled) {
            try {
                if (EV_INBOUND.equals(eventType)) {
                    final String pid = providerMessageId;
                    final String phone = mobile;
//                    laneFor(phone).execute(() -> processInbound(eventId, message, phone, pid));
                    laneFor(phone).execute(() -> processInbound(eventId, message, phone, pid, receivedAtMs));
                } else {
                    final String mid = providerMessageId;
                    final String phone = mobile;
                    final String status = text(notification, "status");
                    final String code = text(notification, "code");
                    final String reason = text(notification, "reason");
                    final String tpl = firstText(text(root, "templateId"), null);   // "" on bot messages -> null
                    final String karixTimestamp = text(root.path("events"), "timestamp");   // epoch millis, when it happened
                    laneFor(phone != null ? phone : mid)
                            .execute(() -> processStatus(eventId, mid, phone, status, code, reason, tpl, karixTimestamp));
                }
            } catch (RejectedExecutionException full) {
                // Lane full (huge spike) -> 503, Karix retries later. Nothing silently dropped.
                log.error("stage=FAILED failedStage=LANE_FULL eventId={} eventType={}", eventId, eventType);
                return WebhookResult.UNAVAILABLE;
            }
            log.info("stage=ACCEPTED mode=DIRECT eventId={} eventType={} mobile={} totalMs={}",
                    eventId, eventType, mask(mobile), ms(start));
            return WebhookResult.ACCEPTED;
        }

        // ---------- QUEUE MODE ----------
        try {
            ServiceBusMessage sbMessage = new ServiceBusMessage(rawBody);
            sbMessage.setMessageId(sha256(dedupeSource));     // Service Bus duplicate detection
            sbMessage.setCorrelationId(eventId);
            sbMessage.setContentType("application/json");
            sbMessage.setSubject(eventType);
            sbMessage.getApplicationProperties().put("eventId", eventId);
            sbMessage.getApplicationProperties().put("eventType", eventType);
            sbMessage.getApplicationProperties().put("receivedAtEpochMs", System.currentTimeMillis());

            if (EV_INBOUND.equals(eventType)) {
                sbMessage.setSessionId(mobile);               // one customer -> in order
                inboundSender.sendMessage(sbMessage);
            } else {
                statusSender.sendMessage(sbMessage);
            }
            log.info("stage=ACCEPTED mode=QUEUE eventId={} eventType={} mobile={} totalMs={}",
                    eventId, eventType, mask(mobile), ms(start));
            return WebhookResult.ACCEPTED;

        } catch (Exception e) {
            // Not queued -> 503 so Karix retries. Never 200 for a message we didn't keep.
            log.error("stage=FAILED failedStage=QUEUE_PUBLISH eventId={} error={} totalMs={}",
                    eventId, e.getMessage(), ms(start), e);
            return WebhookResult.UNAVAILABLE;
        }
    }


    @Override
    public void processQueuedInbound(String eventId, String rawBody, long receivedAtEpochMs) throws Exception {
        JsonNode msg = MAPPER.readTree(rawBody).path("eventContent").path("message");
        String phone = digits(text(msg, "from"));
        if (!msg.isObject() || phone == null) {
            throw new IllegalArgumentException("not an inbound customer message");
        }
        MDC.put("eventId", eventId);
        MDC.put("mobile", mask(phone));
        try {
            handleInbound(eventId, msg, phone, text(msg, "id"), receivedAtEpochMs);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void processQueuedStatus(String eventId, String rawBody, int attempt) throws Exception {
        JsonNode root = MAPPER.readTree(rawBody);
        JsonNode n = root.path("notificationAttributes");
        String mid = text(root.path("events"), "mid");
        if (mid == null) {
            throw new IllegalArgumentException("delivery receipt without mid");
        }
        MDC.put("eventId", eventId);
        try {
            boolean matched = handleStatus(mid, digits(text(root.path("recipient"), "to")), text(n, "status"),
                    text(n, "code"), text(n, "reason"), firstText(text(root, "templateId"), null),
                    text(root.path("events"), "timestamp"));                      // 7th: Karix event time
            if (!matched) {
                if (attempt < STATUS_MAX_RETRY) {
                    retryStatusLater(eventId, rawBody, mid, attempt);
                } else {
                    log.warn("stage=STATUS_NO_MATCH_FINAL eventId={} mid={} attempts={}", eventId, mid, attempt);
                }
            }
        } finally {
            MDC.clear();
        }
    }

    /** DR came before the outbound row was saved -> put it back on the queue, check again in 10s / 20s / 30s. */
    private void retryStatusLater(String eventId, String rawBody, String mid, int attempt) {
        ServiceBusMessage m = new ServiceBusMessage(rawBody);
        m.setMessageId(sha256("ST-RETRY|" + mid + "|" + eventId + "|" + (attempt + 1)));  // not a duplicate of the original
        m.setCorrelationId(eventId);
        m.setContentType("application/json");
        m.setSubject(EV_STATUS);
        m.getApplicationProperties().put("eventId", eventId);
        m.getApplicationProperties().put("eventType", EV_STATUS);
        m.getApplicationProperties().put("statusAttempt", attempt + 1);
        statusSender.scheduleMessage(m, OffsetDateTime.now().plusSeconds(10L * (attempt + 1)));
        log.info("stage=STATUS_RETRY_SCHEDULED eventId={} mid={} attempt={}", eventId, mid, attempt + 1);
    }

    // ==================================================================
    // 3. DIRECT MODE wrappers — runs on a lane; errors are logged (no retry in this mode)
    // ==================================================================

//    private void processInbound(String eventId, JsonNode msg, String phone, String providerMessageId) {
//        MDC.put("eventId", eventId);
//        MDC.put("mobile", mask(phone));
//        try {
//            handleInbound(eventId, msg, phone, providerMessageId);
//        } catch (Exception e) {
//            log.error("stage=DIRECT_FAILED eventId={}", eventId, e);
//        } finally {
//            MDC.clear();
//        }
//    }


    private void processInbound(String eventId, JsonNode msg, String phone, String providerMessageId,
                                long receivedAtEpochMs) {
        MDC.put("eventId", eventId);
        MDC.put("mobile", mask(phone));
        try {
            handleInbound(eventId, msg, phone, providerMessageId, receivedAtEpochMs);
        } catch (Exception e) {
            log.error("stage=DIRECT_FAILED eventId={}", eventId, e);
        } finally {
            MDC.clear();
        }
    }


    private void processStatus(String eventId, String mid, String phone, String rawStatus,
                               String code, String reason, String templateId, String karixTimestamp) {
        MDC.put("eventId", eventId);
        try {
            boolean matched = handleStatus(mid, phone, rawStatus, code, reason, templateId, karixTimestamp);
            if (!matched) {
                log.warn("stage=STATUS_NO_MATCH mode=DIRECT eventId={} mid={}", eventId, mid);   // no retry in DIRECT
            }
        } catch (Exception e) {
            log.error("stage=STATUS_FAILED eventId={} mid={}", eventId, mid, e);
        } finally {
            MDC.clear();
        }
    }

    // ==================================================================
    // 4. SHARED LOGIC — DIRECT and QUEUE both run exactly this
    // ==================================================================

    /** Customer message: Step 0 tap / button / list row / typed text / location. */
    private void handleInbound(String eventId, JsonNode msg, String phone, String providerMessageId,
                               long receivedAtEpochMs) {
        // Karix (or a Service Bus retry) sent it twice? Answer once.
        if (providerMessageId != null && processedMessageRepository.existsById(providerMessageId)) {
            log.info("stage=DUPLICATE_SKIPPED eventId={} providerMessageId={}", eventId, providerMessageId);
            return;
        }

        String type = firstText(text(msg, "messageType"), text(msg, "type"));
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);

        // WhatsApp profile name — personal data, never logged
        String profileName = cleanProfileName(firstText(text(msg, "profileName"),
                text(msg.path("profile"), "name")));

        String payload = null;     // button / list-row id
        String typed = null;       // typed text
        String title = null;       // visible button text (backup for Step 0)
        JsonNode location = null;  // shared location

        switch (t) {
            case "text" -> {
                JsonNode textNode = msg.path("text");
                typed = textNode.isObject() ? text(textNode, "body") : text(msg, "text");
            }
            case "button" -> {                                    // template quick reply (Step 0)
                JsonNode b = msg.path("button");
                payload = firstText(text(b, "payload"), text(b, "id"));
                title = text(b, "text");
            }
            case "interactive" -> {                               // our buttons, list rows, Flow
                JsonNode inter = msg.path("interactive");
                JsonNode buttonReply = firstObject(inter.path("button_reply"), inter.path("buttonReply"));
                JsonNode listReply = firstObject(inter.path("list_reply"), inter.path("listReply"));
                JsonNode nfm = firstObject(inter.path("nfm_reply"), inter.path("nfmReply"));
                if (buttonReply.isObject()) {
                    payload = text(buttonReply, "id");
                    title = text(buttonReply, "title");
                } else if (listReply.isObject()) {
                    payload = text(listReply, "id");
                    title = text(listReply, "title");
//                } else if (nfm.isObject()) {
//                    log.info("stage=NOT_BUILT eventId={} — WhatsApp Flow reply (C2 store booking)", eventId);
//                }
//                } else if (nfm.isObject()) {
//                    payload = "FLOW_REPLY";
//                    typed = text(nfm, "response_json");
//                    log.info("stage=NOT_BUILT eventId={} — WhatsApp Flow reply (C2 store booking)", eventId);
//                }
                } else if (nfm.isObject()) {                     // C2 — "Book Appointment" Flow submitted
                    payload = "FLOW_REPLY";
                    JsonNode responseJson = nfm.path("response_json").isMissingNode()
                            ? nfm.path("responseJson") : nfm.path("response_json");
                    typed = responseJson.isTextual() ? responseJson.asText() : responseJson.toString();
                    log.info("stage=FLOW_REPLY_RECEIVED eventId={}", eventId);
                }
            }
            case "location" -> location = msg.path("location");
            default -> { /* image, sticker, audio... */ }
        }


        // "User ka click aaya" — saved for EVERY inbound type, before routing.
        // If this save fails, the exception goes up -> Service Bus retry (nothing has been sent yet).
        saveInbound(phone, providerMessageId, t, payload, title, typed, location,
                epochToTime(text(msg, "timestamp")), toTime(receivedAtEpochMs));

        if ("FLOW_REPLY".equals(payload)) {
            botEngineService.updateCustomerName(phone, profileName);
            boolean handled = botEngineService.onFlowReply(phone, typed);
            log.info("stage=FLOW_REPLY eventId={} handled={}", eventId, handled);
        } else if (payload != null && !payload.isBlank()) {
            String path = step0Path(payload, t, title);
            if (path != null) {
                // Step 0 template tap -> journey starts (session + tap on OPENER, first message sent)
                log.info("stage=STEP0 eventId={} path={} payload={}", eventId, path, payload);
                botEngineService.startFromOpener(phone, profileName, path, payload, title);
            } else {
                botEngineService.updateCustomerName(phone, profileName);
                boolean handled = botEngineService.onButtonTap(phone, null, payload);   // session's own path
                log.info("stage=BUTTON eventId={} payload={} handled={}", eventId, payload, handled);
            }
        } else if (typed != null && !typed.isBlank()) {
            botEngineService.updateCustomerName(phone, profileName);
            boolean handled = botEngineService.onTextMessage(phone, typed);   // wife / husband / sparkle keywords inside
            log.info("stage=TEXT eventId={} handled={}", eventId, handled);
        } else if (location != null) {
            printLocation(eventId, phone, location, msg);
            boolean handled = botEngineService.onLocationShared(phone, text(location, "latitude"), text(location, "longitude"));
            log.info("stage=LOCATION eventId={} handled={}", eventId, handled);
        } else {
            log.info("stage=IGNORED eventId={} messageType={}", eventId, type);
        }

        if (providerMessageId != null) markProcessed(providerMessageId);
    }

    /**
     * Delivery receipt: SENT -> DELIVERED -> READ / FAILED.
     *
     *  1. HISTORY  — every status gets its OWN row in message_status_events (SENT row, DELIVERED row, READ row).
     *                Nothing is overwritten. Same status twice (Karix retry) -> unique key -> saved once.
     *  2. LATEST   — messages.status keeps the latest status and only moves forward.
     *                Done with ONE atomic UPDATE in MySQL, so SENT arriving late (or at the same time as
     *                DELIVERED on another thread) can never pull DELIVERED back to SENT.
     *
     * @return true = handled. false = message row not saved yet (QUEUE mode re-checks in 10s / 20s / 30s).
     */
    private boolean handleStatus(String mid, String phone, String rawStatus,
                                 String code, String reason, String templateId, String karixTimestamp) {
        String status = mapStatus(rawStatus);
        if (status == null) {
            log.debug("stage=STATUS_SKIPPED mid={} rawStatus={}", mid, rawStatus);
            return true;                                  // unknown status -> nothing to save, no retry
        }
        boolean failed = MSG_STATUS_FAILED.equals(status);
        String shortReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));

        // 1. HISTORY — separate row per status
        saveStatusEvent(mid, phone, status, failed ? code : null, failed ? shortReason : null,
                templateId, karixTimestamp);

        // 2. LATEST — only forward (atomic)
        Set<String> sameOrHigher = sameOrHigher(status);
        LocalDateTime now = LocalDateTime.now();
        int updated = failed
                ? messageRepository.updateFailedIfNewer(mid, status, now, code, shortReason, sameOrHigher)
                : messageRepository.updateStatusIfNewer(mid, status, now, sameOrHigher);
        if (updated > 0) {
            log.debug("stage=STATUS_UPDATED mid={} status={} code={}", mid, status, code);
            return true;
        }

        if (messageRepository.findFirstByKarixMessageId(mid).isPresent()) {
            // Row exists but already has this or a newer status (e.g. SENT came after DELIVERED) -> keep it
            log.debug("stage=STATUS_NOT_NEWER mid={} status={}", mid, status);
            return true;
        }

        if (templateId != null && phone != null) {
            // Step 0 template (sent from the Karix portal) — first receipt saves customer + OPENER session
            botEngineService.recordOpenerTemplate(phone, mid, templateId, status, code, reason);
            return true;
        }

        log.debug("stage=STATUS_NO_MATCH mid={} status={}", mid, status);
        return false;                                     // our row not saved yet -> retry later
    }

    /** One row per (mid, status). Duplicate (Karix sent the same status again) -> unique key -> ignored. */
    private void saveStatusEvent(String mid, String phone, String status, String code, String reason,
                                 String templateId, String karixTimestamp) {
        try {
            statusEventRepository.save(MessageStatusEvent.builder()
                    .karixMessageId(mid)
                    .phone(phone)
                    .status(status)
                    .errorCode(cut(code, 50))
                    .errorReason(reason)
                    .templateId(cut(templateId, 100))
                    .eventAt(toLocalTime(karixTimestamp))   // when it happened (Karix time)
                    .receivedAt(LocalDateTime.now())        // when we got it
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.debug("stage=STATUS_EVENT_DUPLICATE mid={} status={}", mid, status);
        }
    }

    /** Statuses with rank >= this one. messages.status is updated only if it is NOT one of these. */
    private static Set<String> sameOrHigher(String status) {
        int rank = STATUS_RANK.getOrDefault(status, 0);
        return STATUS_RANK.entrySet().stream()
                .filter(e -> e.getValue() >= rank)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** Karix events.timestamp ("1791523711000", epoch millis) -> local time. Now, if it's missing or bad. */
    private static LocalDateTime toLocalTime(String epochMillis) {
        try {
            if (epochMillis != null && !epochMillis.isBlank()) {
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(epochMillis.trim())), ZoneId.systemDefault());
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return LocalDateTime.now();
    }

    /** Location she shared — printed only for now (C2 store search comes later). */
    private void printLocation(String eventId, String phone, JsonNode location, JsonNode msg) {
        String latitude = text(location, "latitude");
        String longitude = text(location, "longitude");
        if (latitude == null || longitude == null) {
            log.warn("stage=LOCATION_FIELDS_MISSING eventId={} mobile={} raw={}", eventId, mask(phone), msg);
            return;
        }
        log.info("stage=LOCATION_RECEIVED eventId={} mobile={} latitude={} longitude={}",
                eventId, mask(phone), latitude, longitude);
    }

    /** Which journey a Step 0 template button starts, or null. By payload first, then visible text. */
    private String step0Path(String payload, String messageType, String title) {
        if (wifePayloads.contains(payload))    return TEMPLATE_WIFE;
        if (husbandPayloads.contains(payload)) return TEMPLATE_HUSBAND;
        if (sparklePayloads.contains(payload)) return TEMPLATE_SPARKLE;
        if ("button".equals(messageType) && title != null) {
            String t = title.toLowerCase(Locale.ROOT);
            if (t.contains("celebrating"))      return TEMPLATE_WIFE;
            if (t.contains("shopping for her")) return TEMPLATE_HUSBAND;
            if (t.contains("sparkle"))          return TEMPLATE_SPARKLE;
        }
        return null;
    }

    private void markProcessed(String providerMessageId) {
        try {
            processedMessageRepository.save(ProcessedMessage.builder().providerMessageId(providerMessageId).build());
        } catch (DataIntegrityViolationException e) {
            // already recorded — fine
        }
    }

    private static String mapStatus(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "sent", "submitted", "accepted"              -> MSG_STATUS_SENT;
            case "delivered"                                  -> MSG_STATUS_DELIVERED;
            case "read", "seen"                               -> MSG_STATUS_READ;
            case "failed", "undelivered", "rejected", "error" -> MSG_STATUS_FAILED;
            default -> null;
        };
    }

    /** INBOUND row = proof the customer's tap / message reached our server. */
    private void saveInbound(String phone, String providerMessageId, String type, String payload,
                             String title, String typed, JsonNode location,
                             LocalDateTime eventAt, LocalDateTime receivedAt) {
        String content = typed;
        if (content == null && location != null && location.isObject()) {
            content = "LOCATION " + text(location, "latitude") + "," + text(location, "longitude");
        }
        messageRepository.save(Message.builder()
                .phone(phone)
                .direction("INBOUND")
                .karixMessageId(providerMessageId)
                .status("RECEIVED")
                .messageType(cut(type, 30))
                .buttonPayload(cut(payload, 100))
                .buttonTitle(cut(title, 100))
                .contentText(content)
                .eventAt(eventAt)
                .receivedAt(receivedAt)
                .build());
    }

    /** Karix / WhatsApp timestamp: epoch seconds or millis -> LocalDateTime, null if absent. */
    private static LocalDateTime epochToTime(String ts) {
        if (ts == null || !ts.matches("\\d{9,13}")) return null;
        long v = Long.parseLong(ts);
        return toTime(ts.length() <= 10 ? v * 1000 : v);
    }

    private static LocalDateTime toTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    private static String cut(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
    // ==================================================================
    // helpers
    // ==================================================================

    private ThreadPoolExecutor laneFor(String key) {
        return lanes[Math.floorMod(key.hashCode(), lanes.length)];
    }

    private boolean isAuthorized(String presented) {
        if (!authEnabled) return true;
        if (webhookToken == null || webhookToken.isBlank() || presented == null) return false;
        return MessageDigest.isEqual(webhookToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText(null);
    }

    /** Trims the WhatsApp profile name; null if empty. Max 100 chars (customers.name column). */
    private static String cleanProfileName(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static String firstText(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static JsonNode firstObject(JsonNode a, JsonNode b) {
        return a.isObject() ? a : b;
    }

    private static String digits(String s) {
        if (s == null) return null;
        String d = s.replaceAll("[^0-9]", "");
        return (d.length() >= 10 && d.length() <= 15) ? d : null;
    }

    private static String mask(String mobile) {
        if (mobile == null || mobile.length() < 6) return "****";
        return mobile.substring(0, 2) + "******" + mobile.substring(mobile.length() - 4);
    }

    private static Set<String> toSet(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @PreDestroy
    public void close() {
        if (inboundSender != null) inboundSender.close();
        if (statusSender != null) statusSender.close();
        if (lanes != null) {
            // Let replies already in progress finish (up to 10s), then stop
            for (ThreadPoolExecutor lane : lanes) lane.shutdown();
            for (ThreadPoolExecutor lane : lanes) {
                try {
                    if (!lane.awaitTermination(10, TimeUnit.SECONDS)) lane.shutdownNow();
                } catch (InterruptedException e) {
                    lane.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}