//package org.example.karvachauth.serviceImple;//package org.example.karvachauth.serviceImpl;
//
//import com.azure.core.amqp.AmqpRetryMode;
//import com.azure.core.amqp.AmqpRetryOptions;
//import com.azure.messaging.servicebus.ServiceBusClientBuilder;
//import com.azure.messaging.servicebus.ServiceBusMessage;
//import com.azure.messaging.servicebus.ServiceBusSenderClient;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import jakarta.annotation.PreDestroy;
//import org.example.karvachauth.entity.Message;
//import org.example.karvachauth.entity.ProcessedMessage;
//import org.example.karvachauth.repository.MessageRepository;
//import org.example.karvachauth.repository.ProcessedMessageRepository;
//import org.example.karvachauth.service.BotEngineService;
//import org.example.karvachauth.service.KarvaChauthWebhookService;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.slf4j.MDC;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.dao.DataIntegrityViolationException;
//import org.springframework.stereotype.Service;
//
//import java.nio.charset.StandardCharsets;
//import java.security.MessageDigest;
//import java.time.Duration;
//import java.time.LocalDateTime;
//import java.util.Arrays;
//import java.util.HexFormat;
//import java.util.Map;
//import java.util.Optional;
//import java.util.Set;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Collectors;
//
//import static org.example.karvachauth.constants.KarvaChauthConstants.*;
//
///**
// * Receives every Karix callback. Two modes, chosen by karvachauth.servicebus.enabled:
// *
// *   true  (QUEUE mode)  — validate → put on Service Bus → 200.
// *                         Something else (InboundMessageConsumer / StatusConsumer) must read the queues.
// *
// *   false (DIRECT mode) — validate → 200 straight away, and THIS class handles the message itself
// *                         on a background lane: button taps / typed text → BotEngineService,
// *                         delivery receipts → messages.status. No Service Bus, no extra classes.
// *
// * Direct mode keeps each customer's messages in order (one lane per customer, chosen by phone),
// * so Karix always gets a fast 200 while the bot replies in the background.
// */
//@Service
//public class KarvaChauthWebhookServiceImpl implements KarvaChauthWebhookService {
//
//    private static final Logger log = LoggerFactory.getLogger(KarvaChauthWebhookServiceImpl.class);
//
//    /** Status order — a late "delivered" must never overwrite "read". */
//    private static final Map<String, Integer> STATUS_RANK = Map.of(
//            MSG_STATUS_SENT, 1, MSG_STATUS_DELIVERED, 2, MSG_STATUS_READ, 3, MSG_STATUS_FAILED, 4);
//
//    private final ObjectMapper objectMapper = new ObjectMapper();   // Spring Boot 4 has no Jackson-2 bean
//    private final boolean serviceBusEnabled;
//    private final ServiceBusSenderClient inboundSender;   // user messages (sessions, SessionId = mobile)
//    private final ServiceBusSenderClient statusSender;    // delivery/read receipts
//    private final String webhookToken;
//    private final boolean authEnabled;
//    private final int maxBodyBytes;
//
//    // ---------- DIRECT mode ----------
//    private final BotEngineService botEngineService;
//    private final MessageRepository messageRepository;
//    private final ProcessedMessageRepository processedMessageRepository;
//    private final Set<String> wifePayloads;
//    private final Set<String> husbandPayloads;
//    private final Set<String> sparklePayloads;
//    /** Background lanes. The same customer always lands on the same lane → her messages stay in order. */
//    private final ExecutorService[] lanes;
//
//    public KarvaChauthWebhookServiceImpl(
//            BotEngineService botEngineService,
//            MessageRepository messageRepository,
//            ProcessedMessageRepository processedMessageRepository,
//            @Value("${karvachauth.servicebus.enabled:true}") boolean serviceBusEnabled,
//            @Value("${karvachauth.servicebus.connection-string:}") String sbConnectionString,
//            @Value("${karvachauth.servicebus.inbound-queue:karvachauth-inbound}") String inboundQueue,
//            @Value("${karvachauth.servicebus.status-queue:karvachauth-status}") String statusQueue,
//            @Value("${karvachauth.webhook.auth-token:}") String webhookToken,
//            @Value("${karvachauth.webhook.auth-enabled:true}") boolean authEnabled,
//            @Value("${karvachauth.webhook.max-body-bytes:262144}") int maxBodyBytes,
//            @Value("${karvachauth.direct.lanes:16}") int laneCount,
//            @Value("${karvachauth.step0.wife-payloads:BTN_CELEBRATING}") String wifePayloads,
//            @Value("${karvachauth.step0.husband-payloads:BTN_SHOPPING_FOR_HER}") String husbandPayloads,
//            @Value("${karvachauth.step0.sparkle-payloads:BTN_SPARKLE}") String sparklePayloads) {
//
//        this.botEngineService = botEngineService;
//        this.messageRepository = messageRepository;
//        this.processedMessageRepository = processedMessageRepository;
//        this.serviceBusEnabled = serviceBusEnabled;
//        this.webhookToken = webhookToken;
//        this.authEnabled = authEnabled;
//        this.maxBodyBytes = maxBodyBytes;
//        this.wifePayloads = toSet(wifePayloads);
//        this.husbandPayloads = toSet(husbandPayloads);
//        this.sparklePayloads = toSet(sparklePayloads);
//
//        if (!serviceBusEnabled) {
//            this.inboundSender = null;
//            this.statusSender = null;
//            this.lanes = new ExecutorService[Math.max(1, laneCount)];
//            for (int i = 0; i < lanes.length; i++) {
//                final int n = i;
//                lanes[i] = Executors.newSingleThreadExecutor(r -> {
//                    Thread t = new Thread(r, "kc-lane-" + n);
//                    t.setDaemon(true);
//                    return t;
//                });
//            }
//            log.warn("karvachauth.servicebus.enabled=false -> DIRECT MODE: messages are handled in this app on {} lanes "
//                    + "(no Service Bus). In-flight messages are lost if the app stops mid-reply.", lanes.length);
//            return;
//        }
//        this.lanes = null;
//
//        if (sbConnectionString == null || sbConnectionString.isBlank()) {
//            throw new IllegalStateException(
//                    "karvachauth.servicebus.connection-string is empty. Set it, or set karvachauth.servicebus.enabled=false for direct mode.");
//        }
//
//        AmqpRetryOptions retry = new AmqpRetryOptions()
//                .setMode(AmqpRetryMode.EXPONENTIAL)
//                .setMaxRetries(2)
//                .setDelay(Duration.ofMillis(200))
//                .setMaxDelay(Duration.ofSeconds(1))
//                .setTryTimeout(Duration.ofSeconds(3));
//
//        ServiceBusClientBuilder builder = new ServiceBusClientBuilder()
//                .connectionString(sbConnectionString)
//                .retryOptions(retry);
//
//        this.inboundSender = builder.sender().queueName(inboundQueue).buildClient();
//        this.statusSender = builder.sender().queueName(statusQueue).buildClient();
//        log.info("QUEUE MODE: Service Bus senders ready: inbound={}, status={}", inboundQueue, statusQueue);
//    }
//
//    @Override
//    public WebhookResult handleWebhook(String eventId, String rawBody, String token) {
//        long start = System.nanoTime();
//        int bytes = rawBody == null ? 0 : rawBody.getBytes(StandardCharsets.UTF_8).length;
//        log.info("stage=WEBHOOK_RECEIVED eventId={} bytes={}", eventId, bytes);
//
//        // ---------- 1. VALIDATE ----------
//        if (!isAuthorized(token)) {
//            log.warn("stage=REJECTED eventId={} reason=INVALID_TOKEN elapsedMs={}", eventId, ms(start));
//            return WebhookResult.UNAUTHORIZED;
//        }
//        if (bytes == 0 || bytes > maxBodyBytes) {
//            log.warn("stage=REJECTED eventId={} reason=BAD_SIZE bytes={}", eventId, bytes);
//            return WebhookResult.IGNORED;
//        }
//        JsonNode root;
//        try {
//            root = objectMapper.readTree(rawBody);
//        } catch (Exception e) {
//            log.warn("stage=REJECTED eventId={} reason=NOT_JSON", eventId);
//            return WebhookResult.IGNORED;
//        }
//        if (root == null || !root.isObject()) {
//            log.warn("stage=REJECTED eventId={} reason=NOT_JSON_OBJECT", eventId);
//            return WebhookResult.IGNORED;
//        }
//
//        // ---------- 2. IDENTIFY EVENT (real Karix format) ----------
//        String eventType;
//        String providerMessageId;
//        String mobile;
//        String dedupeSource;
//        String messageType = null;
//        String ctaPayload = null;
//        String repliedToMid = null;
//        String deliveryStatus = null;
//        String templateId = null;
//
//        JsonNode message = root.path("eventContent").path("message");
//        JsonNode notification = root.path("notificationAttributes");
//
//        if (message.isObject()) {
//            eventType = "INBOUND_MESSAGE";
//            providerMessageId = text(message, "id");
//            mobile = digits(text(message, "from"));
//            messageType = text(message, "messageType");
//            repliedToMid = text(message.path("context"), "id");
//            if ("button".equals(messageType)) {
//                ctaPayload = text(message.path("button"), "payload");
//            }
//            dedupeSource = "IN|" + (providerMessageId != null ? providerMessageId : rawBody);
//        } else if (notification.isObject()) {
//            eventType = "DELIVERY_STATUS";
//            providerMessageId = text(root.path("events"), "mid");
//            mobile = digits(text(root.path("recipient"), "to"));
//            deliveryStatus = text(notification, "status");
//            templateId = firstText(text(root, "templateId"), null);   // Karix sends "" on bot messages → null
//            dedupeSource = "ST|" + (providerMessageId != null ? providerMessageId + "|" + deliveryStatus : rawBody);
//        } else {
//            eventType = "UNKNOWN";
//            providerMessageId = null;
//            mobile = null;
//            dedupeSource = "UN|" + rawBody;
//        }
//
//        log.info("stage=VALIDATED eventId={} eventType={} messageType={} providerMessageId={} mobile={} elapsedMs={}",
//                eventId, eventType, messageType, providerMessageId, mask(mobile), ms(start));
//
//        if (ctaPayload != null) {
//            log.info("stage=CTA_CLICKED eventId={} mobile={} ctaPayload={} repliedToMid={}",
//                    eventId, mask(mobile), ctaPayload, repliedToMid);
//        }
//        if (deliveryStatus != null) {
//            log.info("stage=DELIVERY_STATUS eventId={} mobile={} mid={} status={} code={} templateId={}",
//                    eventId, mask(mobile), providerMessageId, deliveryStatus, text(notification, "code"), templateId);
//        }
//
//        // No phone on a customer message → nobody to reply to
//        if ("INBOUND_MESSAGE".equals(eventType) && mobile == null) {
//            log.warn("stage=REJECTED eventId={} reason=NO_MOBILE", eventId);
//            return WebhookResult.IGNORED;
//        }
//
//        // ---------- 3a. DIRECT MODE: hand off to a background lane, reply 200 at once ----------
//        if (!serviceBusEnabled) {
//            if ("INBOUND_MESSAGE".equals(eventType)) {
//                final String pid = providerMessageId;
//                final String phone = mobile;
//                laneFor(phone).submit(() -> processInbound(eventId, message, phone, pid));
//            } else if ("DELIVERY_STATUS".equals(eventType) && providerMessageId != null) {
//                final String mid = providerMessageId;
//                final String phone = mobile;
//                final String status = deliveryStatus;
//                final String tpl = templateId;
//                final String code = text(notification, "code");
//                final String reason = text(notification, "reason");
//                laneFor(phone == null ? mid : phone).submit(() -> processStatus(eventId, mid, phone, status, code, reason, tpl));
//            }
//            log.info("stage=DIRECT_DISPATCHED eventId={} eventType={} totalMs={}", eventId, eventType, ms(start));
//            return WebhookResult.ACCEPTED;
//        }
//
//        // ---------- 3b. QUEUE MODE: duplicate key + publish to Service Bus ----------
//        String dedupeKey = sha256(dedupeSource);
//        log.info("stage=DUPLICATE_CHECK eventId={} dedupeKey={}", eventId, dedupeKey);
//        try {
//            ServiceBusMessage sbMessage = new ServiceBusMessage(rawBody);
//            sbMessage.setMessageId(dedupeKey);
//            sbMessage.setCorrelationId(eventId);
//            sbMessage.setContentType("application/json");
//            sbMessage.setSubject(eventType);
//            sbMessage.getApplicationProperties().put("eventId", eventId);
//            sbMessage.getApplicationProperties().put("eventType", eventType);
//            sbMessage.getApplicationProperties().put("receivedAtEpochMs", System.currentTimeMillis());
//
//            if ("INBOUND_MESSAGE".equals(eventType)) {
//                sbMessage.setSessionId(mobile);
//                inboundSender.sendMessage(sbMessage);
//            } else {
//                statusSender.sendMessage(sbMessage);
//            }
//            log.info("stage=QUEUE_PUBLISHED eventId={} eventType={} totalMs={}", eventId, eventType, ms(start));
//            return WebhookResult.ACCEPTED;
//        } catch (Exception e) {
//            log.error("stage=FAILED failedStage=QUEUE_PUBLISH eventId={} error={} totalMs={}",
//                    eventId, e.getMessage(), ms(start), e);
//            return WebhookResult.UNAVAILABLE;
//        }
//    }
//
//
//
//
//    // ==================================================================
//    // DIRECT MODE — CUSTOMER MESSAGES (button taps, list rows, typed text)
//    // ==================================================================
//
//    /** Runs on the customer's lane, after Karix already has its 200. */
//    private void processInbound(String eventId, JsonNode msg, String phone, String providerMessageId) {
//        MDC.put("eventId", eventId);
//        MDC.put("mobile", mask(phone));
//        try {
//            // Karix sent the same message twice? Answer it once.
//            if (providerMessageId != null && processedMessageRepository.existsById(providerMessageId)) {
//                log.info("stage=DUPLICATE_SKIPPED eventId={} providerMessageId={}", eventId, providerMessageId);
//                return;
//            }
//
//            String type = firstText(text(msg, "messageType"), text(msg, "type"));
//            String t = type == null ? "" : type.toLowerCase();
//
//            // Customer's WhatsApp profile name — Karix sends it on every inbound message
//            // (same field the Rakhi bot used). Not logged: it's personal data.
//            String profileName = cleanProfileName(firstText(text(msg, "profileName"),
//                    text(msg.path("profile"), "name")));
//
//            String payload = null;   // button / list-row id
//            String typed = null;     // typed text
//            String title = null;     // visible button text (used to recognise Step 0 buttons)
//            JsonNode location = null; // shared location ("Visit nearby store" → "Send location")
//
//            if (t.equals("text")) {
//                JsonNode textNode = msg.path("text");
//                typed = textNode.isObject() ? text(textNode, "body") : text(msg, "text");
//            } else if (t.equals("button")) {                         // template quick reply (Step 0)
//                JsonNode b = msg.path("button");
//                payload = firstText(text(b, "payload"), text(b, "id"));
//                title = text(b, "text");
//            } else if (t.equals("interactive")) {                    // our own buttons, list rows, Flow
//                JsonNode inter = msg.path("interactive");
//                JsonNode buttonReply = firstObject(inter.path("button_reply"), inter.path("buttonReply"));
//                JsonNode listReply = firstObject(inter.path("list_reply"), inter.path("listReply"));
//                JsonNode nfm = firstObject(inter.path("nfm_reply"), inter.path("nfmReply"));
//                if (buttonReply.isObject()) {
//                    payload = text(buttonReply, "id");
//                    title = text(buttonReply, "title");
//                } else if (listReply.isObject()) {
//                    payload = text(listReply, "id");
//                    title = text(listReply, "title");
//                } else if (nfm.isObject()) {
//                    log.info("stage=NOT_BUILT eventId={} — WhatsApp Flow reply (C2 store booking)", eventId);
//                }
//            } else if (t.equals("location")) {                       // she shared her location
//                location = msg.path("location");
//            }
//
//            if (payload != null && !payload.isBlank()) {
//                String path = step0Path(payload, t, title);
//                if (path != null) {
//                    // Step 0 (Opener) tap → session + tap saved on OPENER, then step 1 / 2 / 1-Sparkle
//                    log.info("stage=DIRECT_STEP0 eventId={} path={} payload={}", eventId, path, payload);
//                    botEngineService.startFromOpener(phone, profileName, path, payload, title);
//                } else {
//                    botEngineService.updateCustomerName(phone, profileName);
//                    // path = null → the bot uses the customer's own session path
//                    boolean handled = botEngineService.onButtonTap(phone, null, payload);
//                    log.info("stage=DIRECT_BUTTON eventId={} payload={} handled={}", eventId, payload, handled);
//                }
//            } else if (typed != null && !typed.isBlank()) {
//                botEngineService.updateCustomerName(phone, profileName);
//                boolean handled = botEngineService.onTextMessage(phone, typed);
//                log.info("stage=DIRECT_TEXT eventId={} handled={}", eventId, handled);
//            } else if (location != null) {
//                // C2 — location received. Only printed for now, NOT saved.
//                // Later: find the 3 nearest Mia stores to this latitude / longitude and send them.
//                printLocation(eventId, phone, location, msg);
//            } else {
//                log.info("stage=DIRECT_IGNORED eventId={} messageType={} — not a button or text "
//                        + "(image, sticker, location…). If it WAS a button/text, check the field names above.", eventId, type);
//            }
//
//            if (providerMessageId != null) markProcessed(providerMessageId);
//
//        } catch (Exception e) {
//            log.error("stage=DIRECT_FAILED eventId={}", eventId, e);
//        } finally {
//            MDC.clear();
//        }
//    }
//
//    /**
//     * Prints the location she shared — latitude, longitude, and the place name / address when she
//     * picked a place instead of her current location. Nothing is saved.
//     * If latitude / longitude come back empty, the raw message is printed so the Karix field names can be checked.
//     */
//    private void printLocation(String eventId, String phone, JsonNode location, JsonNode msg) {
//        String latitude = text(location, "latitude");
//        String longitude = text(location, "longitude");
//        if (latitude == null || longitude == null) {
//            log.warn("stage=LOCATION_FIELDS_MISSING eventId={} mobile={} raw={}", eventId, mask(phone), msg);
//            return;
//        }
//        log.info("stage=LOCATION_RECEIVED eventId={} mobile={} latitude={} longitude={} name={} address={}",
//                eventId, mask(phone), latitude, longitude, text(location, "name"), text(location, "address"));
//    }
//
//    /** Which journey a Step 0 template button starts, or null. By payload first, then by visible text. */
//    private String step0Path(String payload, String messageType, String title) {
//        if (wifePayloads.contains(payload))    return TEMPLATE_WIFE;
//        if (husbandPayloads.contains(payload)) return TEMPLATE_HUSBAND;
//        if (sparklePayloads.contains(payload)) return TEMPLATE_SPARKLE;
//        if ("button".equals(messageType) && title != null) {           // template buttons only
//            String t = title.toLowerCase();
//            if (t.contains("celebrating"))      return TEMPLATE_WIFE;
//            if (t.contains("shopping for her")) return TEMPLATE_HUSBAND;
//            if (t.contains("sparkle"))          return TEMPLATE_SPARKLE;
//        }
//        return null;
//    }
//
//    private void markProcessed(String providerMessageId) {
//        try {
//            processedMessageRepository.save(ProcessedMessage.builder().providerMessageId(providerMessageId).build());
//        } catch (DataIntegrityViolationException e) {
//            // already recorded — fine
//        }
//    }
//
//    // ==================================================================
//    // DIRECT MODE — DELIVERY RECEIPTS (SENT → DELIVERED → READ / FAILED)
//    // ==================================================================
//
//    private void processStatus(String eventId, String mid, String phone, String rawStatus,
//                               String code, String reason, String templateId) {
//        MDC.put("eventId", eventId);
//        try {
//            String status = mapStatus(rawStatus);
//            if (status == null) {
//                log.debug("stage=STATUS_SKIPPED mid={} rawStatus={}", mid, rawStatus);
//                return;
//            }
//
//            Optional<Message> found = messageRepository.findFirstByKarixMessageId(mid);
//            if (found.isPresent()) {
//                Message m = found.get();
//                int current = STATUS_RANK.getOrDefault(m.getStatus(), 0);
//                if (STATUS_RANK.get(status) <= current) return;           // never move backwards
//                m.setStatus(status);
//                m.setStatusUpdatedAt(LocalDateTime.now());
//                if (MSG_STATUS_FAILED.equals(status)) {
//                    m.setErrorCode(code);
//                    m.setErrorReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 500)));
//                }
//                messageRepository.save(m);
//                log.info("stage=STATUS_UPDATED mid={} status={} code={}", mid, status, code);
//
//            } else if (templateId != null && phone != null) {
//                // Step 0 (Opener) template, sent from the Karix portal — its first receipt saves it
//                // on STEP_OPENER with the customer; later receipts update it (branch above)
//                botEngineService.recordOpenerTemplate(phone, mid, templateId, status, code, reason);
//            } else {
//                log.debug("stage=STATUS_NO_MATCH mid={} status={}", mid, status);
//            }
//        } catch (Exception e) {
//            log.error("stage=STATUS_FAILED eventId={} mid={}", eventId, mid, e);
//        } finally {
//            MDC.clear();
//        }
//    }
//
//    private static String mapStatus(String raw) {
//        if (raw == null) return null;
//        return switch (raw.trim().toLowerCase()) {
//            case "sent", "submitted", "accepted"              -> MSG_STATUS_SENT;
//            case "delivered"                                  -> MSG_STATUS_DELIVERED;
//            case "read", "seen"                               -> MSG_STATUS_READ;
//            case "failed", "undelivered", "rejected", "error" -> MSG_STATUS_FAILED;
//            default -> null;
//        };
//    }
//
//    // ---------------- helpers ----------------
//
//    private ExecutorService laneFor(String key) {
//        return lanes[Math.floorMod(key.hashCode(), lanes.length)];
//    }
//
//    private boolean isAuthorized(String presented) {
//        if (!authEnabled) return true;
//        if (webhookToken == null || webhookToken.isBlank() || presented == null) return false;
//        return MessageDigest.isEqual(webhookToken.getBytes(StandardCharsets.UTF_8),
//                presented.getBytes(StandardCharsets.UTF_8));
//    }
//
//    private static String text(JsonNode node, String field) {
//        JsonNode v = node.path(field);
//        return (v.isMissingNode() || v.isNull()) ? null : v.asText(null);
//    }
//
//    /** Trims the WhatsApp profile name; null if empty. Max 100 chars (customers.name column). */
//    private static String cleanProfileName(String raw) {
//        if (raw == null) return null;
//        String s = raw.replaceAll("\\s+", " ").trim();
//        if (s.isEmpty()) return null;
//        return s.length() > 100 ? s.substring(0, 100) : s;
//    }
//
//    private static String firstText(String a, String b) {
//        return (a != null && !a.isBlank()) ? a : b;
//    }
//
//    private static JsonNode firstObject(JsonNode a, JsonNode b) {
//        return a.isObject() ? a : b;
//    }
//
//    private static String digits(String s) {
//        if (s == null) return null;
//        String d = s.replaceAll("[^0-9]", "");
//        return (d.length() >= 10 && d.length() <= 15) ? d : null;
//    }
//
//    private static String mask(String mobile) {
//        if (mobile == null || mobile.length() < 6) return "****";
//        return mobile.substring(0, 2) + "******" + mobile.substring(mobile.length() - 4);
//    }
//
//    private static Set<String> toSet(String csv) {
//        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
//    }
//
//    private static String sha256(String value) {
//        try {
//            MessageDigest md = MessageDigest.getInstance("SHA-256");
//            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
//        } catch (Exception e) {
//            throw new IllegalStateException(e);
//        }
//    }
//
//    private static long ms(long startNanos) {
//        return (System.nanoTime() - startNanos) / 1_000_000;
//    }
//
//    @PreDestroy
//    public void close() {
//        if (inboundSender != null) inboundSender.close();
//        if (statusSender != null) statusSender.close();
//        if (lanes != null) {
//            // Let replies already in progress finish (up to 10s), then stop
//            for (ExecutorService lane : lanes) lane.shutdown();
//            for (ExecutorService lane : lanes) {
//                try {
//                    if (!lane.awaitTermination(10, TimeUnit.SECONDS)) lane.shutdownNow();
//                } catch (InterruptedException e) {
//                    lane.shutdownNow();
//                    Thread.currentThread().interrupt();
//                }
//            }
//        }
//    }
//}


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
import org.example.karvachauth.entity.ProcessedMessage;
import org.example.karvachauth.repository.MessageRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    /** Thread-safe, shared. Spring Boot 4 has no Jackson-2 bean, so one is created here. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Status order — a late "delivered" must never overwrite "read". */
    private static final Map<String, Integer> STATUS_RANK = Map.of(
            MSG_STATUS_SENT, 1, MSG_STATUS_DELIVERED, 2, MSG_STATUS_READ, 3, MSG_STATUS_FAILED, 4);

    private static final String EV_INBOUND = "INBOUND_MESSAGE";
    private static final String EV_STATUS  = "DELIVERY_STATUS";
    private static final String EV_UNKNOWN = "UNKNOWN";

    private final BotEngineService botEngineService;
    private final MessageRepository messageRepository;
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
                    laneFor(phone).execute(() -> processInbound(eventId, message, phone, pid));
                } else {
                    final String mid = providerMessageId;
                    final String phone = mobile;
                    final String status = text(notification, "status");
                    final String code = text(notification, "code");
                    final String reason = text(notification, "reason");
                    final String tpl = firstText(text(root, "templateId"), null);   // "" on bot messages -> null
                    laneFor(phone != null ? phone : mid)
                            .execute(() -> processStatus(eventId, mid, phone, status, code, reason, tpl));
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

    // ==================================================================
    // 2. QUEUE MODE — called by KarvaQueueConsumer. Exceptions go UP -> Service Bus retries / DLQ.
    // ==================================================================

    @Override
    public void processQueuedInbound(String eventId, String rawBody) throws Exception {
        JsonNode msg = MAPPER.readTree(rawBody).path("eventContent").path("message");
        String phone = digits(text(msg, "from"));
        if (!msg.isObject() || phone == null) {
            throw new IllegalArgumentException("not an inbound customer message");
        }
        MDC.put("eventId", eventId);
        MDC.put("mobile", mask(phone));
        try {
            handleInbound(eventId, msg, phone, text(msg, "id"));
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void processQueuedStatus(String eventId, String rawBody) throws Exception {
        JsonNode root = MAPPER.readTree(rawBody);
        JsonNode n = root.path("notificationAttributes");
        String mid = text(root.path("events"), "mid");
        if (mid == null) {
            throw new IllegalArgumentException("delivery receipt without mid");
        }
        MDC.put("eventId", eventId);
        try {
            handleStatus(mid, digits(text(root.path("recipient"), "to")), text(n, "status"),
                    text(n, "code"), text(n, "reason"), firstText(text(root, "templateId"), null));
        } finally {
            MDC.clear();
        }
    }

    // ==================================================================
    // 3. DIRECT MODE wrappers — runs on a lane; errors are logged (no retry in this mode)
    // ==================================================================

    private void processInbound(String eventId, JsonNode msg, String phone, String providerMessageId) {
        MDC.put("eventId", eventId);
        MDC.put("mobile", mask(phone));
        try {
            handleInbound(eventId, msg, phone, providerMessageId);
        } catch (Exception e) {
            log.error("stage=DIRECT_FAILED eventId={}", eventId, e);
        } finally {
            MDC.clear();
        }
    }

    private void processStatus(String eventId, String mid, String phone, String rawStatus,
                               String code, String reason, String templateId) {
        MDC.put("eventId", eventId);
        try {
            handleStatus(mid, phone, rawStatus, code, reason, templateId);
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
    private void handleInbound(String eventId, JsonNode msg, String phone, String providerMessageId) {
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
                } else if (nfm.isObject()) {
                    log.info("stage=NOT_BUILT eventId={} — WhatsApp Flow reply (C2 store booking)", eventId);
                }
            }
            case "location" -> location = msg.path("location");
            default -> { /* image, sticker, audio... */ }
        }

        if (payload != null && !payload.isBlank()) {
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
        } else {
            log.info("stage=IGNORED eventId={} messageType={}", eventId, type);
        }

        if (providerMessageId != null) markProcessed(providerMessageId);
    }

    /** Delivery receipt: SENT -> DELIVERED -> READ / FAILED. Never moves backwards. */
    private void handleStatus(String mid, String phone, String rawStatus,
                              String code, String reason, String templateId) {
        String status = mapStatus(rawStatus);
        if (status == null) {
            log.debug("stage=STATUS_SKIPPED mid={} rawStatus={}", mid, rawStatus);
            return;
        }

        Optional<Message> found = messageRepository.findFirstByKarixMessageId(mid);
        if (found.isPresent()) {
            Message m = found.get();
//            int current = STATUS_RANK.getOrDefault(m.getStatus(), 0);
            int current = m.getStatus() == null ? 0 : STATUS_RANK.getOrDefault(m.getStatus(), 0);
//            if (STATUS_RANK.get(status) <= current) return;
            if (STATUS_RANK.getOrDefault(status, 0) <= current) return;
            m.setStatus(status);

            m.setStatusUpdatedAt(LocalDateTime.now());
            if (MSG_STATUS_FAILED.equals(status)) {
                m.setErrorCode(code);
                m.setErrorReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 500)));
            }
            messageRepository.save(m);
            log.debug("stage=STATUS_UPDATED mid={} status={} code={}", mid, status, code);

        } else if (templateId != null && phone != null) {
            // Step 0 template (sent from the Karix portal) — first receipt saves customer + OPENER session
            botEngineService.recordOpenerTemplate(phone, mid, templateId, status, code, reason);
        } else {
            log.debug("stage=STATUS_NO_MATCH mid={} status={}", mid, status);
        }
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