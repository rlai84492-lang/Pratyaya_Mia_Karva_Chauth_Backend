//
//
//
//package org.example.karvachauth.serviceImple;
//
//import com.azure.messaging.servicebus.ServiceBusClientBuilder;
//import com.azure.messaging.servicebus.ServiceBusErrorContext;
//import com.azure.messaging.servicebus.ServiceBusProcessorClient;
//import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
//import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
//import com.azure.messaging.servicebus.models.DeadLetterOptions;
//import jakarta.annotation.PostConstruct;
//import jakarta.annotation.PreDestroy;
//import org.example.karvachauth.service.KarvaChauthWebhookService;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
//import org.springframework.stereotype.Component;
//
//import java.time.Duration;
//
///**
// * Service Bus -> bot. Sirf karvachauth.servicebus.enabled=true par chalta hai.
// *
// *  inbound queue (SESSIONS ON, SessionId = mobile) -> ek customer ke messages hamesha order mein
// *  status  queue (sessions off)                    -> delivery receipts, parallel
// *
// *  Success -> complete | Error -> abandon (Service Bus 5 baar retry, phir DLQ) | Bad JSON -> turant DLQ
// */
//@Component
//@ConditionalOnProperty(name = "karvachauth.servicebus.enabled", havingValue = "true")
//public class KarvaQueueConsumer {
//
//    private static final Logger log = LoggerFactory.getLogger(KarvaQueueConsumer.class);
//
//    private final KarvaChauthWebhookService webhookService;
//    private final String connectionString;
//    private final String inboundQueue;
//    private final String statusQueue;
//    private final int inboundSessions;
//    private final int statusConcurrency;
//
//    private ServiceBusProcessorClient inboundProcessor;
//    private ServiceBusProcessorClient statusProcessor;
//
//    @Value("${karvachauth.servicebus.session-idle-ms:300}")
//    private long sessionIdleMs;
//
//    public KarvaQueueConsumer(
//            KarvaChauthWebhookService webhookService,
//            @Value("${karvachauth.servicebus.connection-string}") String connectionString,
//            @Value("${karvachauth.servicebus.inbound-queue:karvachauth-inbound}") String inboundQueue,
//            @Value("${karvachauth.servicebus.status-queue:karvachauth-status}") String statusQueue,
//            @Value("${karvachauth.servicebus.inbound-sessions:300}") int inboundSessions,
//            @Value("${karvachauth.servicebus.status-concurrency:64}") int statusConcurrency) {
//        this.webhookService = webhookService;
//        this.connectionString = connectionString;
//        this.inboundQueue = inboundQueue;
//        this.statusQueue = statusQueue;
//        this.inboundSessions = inboundSessions;
//        this.statusConcurrency = statusConcurrency;
//    }
//
//    @PostConstruct
//    public void start() {
//        inboundProcessor = new ServiceBusClientBuilder()
//                .connectionString(connectionString)
//                .sessionProcessor()
//                .queueName(inboundQueue)
//                .maxConcurrentSessions(inboundSessions)     // itne customers ek saath
//                .maxConcurrentCalls(1)                      // ek customer ke messages ek-ek karke
//                .sessionIdleTimeout(Duration.ofMillis(sessionIdleMs))   // pehle: Duration.ofSeconds(2)                .disableAutoComplete()
//                .processMessage(ctx -> handle(ctx, true))
//                .processError(this::onError)
//                .buildProcessorClient();
//
//        statusProcessor = new ServiceBusClientBuilder()
//                .connectionString(connectionString)
//                .processor()
//                .queueName(statusQueue)
//                .maxConcurrentCalls(statusConcurrency)
//                .disableAutoComplete()
//                .processMessage(ctx -> handle(ctx, false))
//                .processError(this::onError)
//                .buildProcessorClient();
//
//        inboundProcessor.start();
//        statusProcessor.start();
//        log.info("stage=CONSUMER_STARTED inbound={} sessions={} status={} concurrency={}",
//                inboundQueue, inboundSessions, statusQueue, statusConcurrency);
//    }
//
//    private void handle(ServiceBusReceivedMessageContext ctx, boolean inbound) {
//        ServiceBusReceivedMessage m = ctx.getMessage();
//        Object idProp = m.getApplicationProperties().get("eventId");
//        String eventId = idProp != null ? idProp.toString() : m.getCorrelationId();
//        long start = System.nanoTime();
//        try {
//            String body = m.getBody().toString();
//            if (inbound) {
//                long receivedAt = longProp(m, "receivedAtEpochMs", m.getEnqueuedTime() != null
//                        ? m.getEnqueuedTime().toInstant().toEpochMilli()
//                        : System.currentTimeMillis());
//                webhookService.processQueuedInbound(eventId, body, receivedAt);
//            } else {
//                int attempt = (int) longProp(m, "statusAttempt", 0);
//                webhookService.processQueuedStatus(eventId, body, attempt);
//            }
//            ctx.complete();
//            ctx.complete();
//            log.info("stage=CONSUMER_DONE eventId={} queue={} tookMs={} queueWaitMs={}",
//                    eventId, inbound ? "inbound" : "status",
//                    (System.nanoTime() - start) / 1_000_000, queueWaitMs(m));
//
//        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException bad) {
//            // Retry se theek nahi hoga -> seedha DLQ (delete nahi hota, Portal mein dekh sakte ho)
//            log.error("stage=CONSUMER_DEAD_LETTER eventId={} reason={}", eventId, bad.getMessage());
//            ctx.deadLetter(new DeadLetterOptions()
//                    .setDeadLetterReason("BAD_MESSAGE")
//                    .setDeadLetterErrorDescription(cut(bad.getMessage())));
//
//        } catch (Exception e) {
//            // DB / Karix down -> wapas queue mein, Service Bus dobara dega (max 5, phir DLQ)
//            log.error("stage=CONSUMER_RETRY eventId={} deliveryCount={} error={}",
//                    eventId, m.getDeliveryCount(), e.getMessage(), e);
//            ctx.abandon();
//        }
//    }
//
//    private void onError(ServiceBusErrorContext ctx) {
//        log.error("stage=CONSUMER_ERROR source={} entity={} error={}",
//                ctx.getErrorSource(), ctx.getEntityPath(), ctx.getException().getMessage());
//    }
//
//    /** Application property as long (Service Bus may give Long, Integer or String). */
//    private static long longProp(ServiceBusReceivedMessage m, String key, long fallback) {
//        Object v = m.getApplicationProperties().get(key);
//        if (v instanceof Number n) return n.longValue();
//        if (v != null) {
//            try {
//                return Long.parseLong(v.toString());
//            } catch (NumberFormatException ignored) {
//                // fall through
//            }
//        }
//        return fallback;
//    }
//
//    /** How long the message waited in the queue before we picked it up (-1 if unknown). */
//    private static long queueWaitMs(ServiceBusReceivedMessage m) {
//        return m.getEnqueuedTime() == null ? -1
//                : System.currentTimeMillis() - m.getEnqueuedTime().toInstant().toEpochMilli();
//    }
//
//    private static String cut(String s) {
//        if (s == null) return "";
//        return s.length() > 1000 ? s.substring(0, 1000) : s;
//    }
//
//    @PreDestroy
//    public void stop() {
//        // Chal rahe messages khatam hone do; jo complete nahi hue wo queue mein wapas -> data loss nahi
//        if (inboundProcessor != null) inboundProcessor.close();
//        if (statusProcessor != null) statusProcessor.close();
//        log.info("stage=CONSUMER_STOPPED");
//    }
//}



package org.example.karvachauth.serviceImple;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.example.karvachauth.service.KarvaChauthWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Service Bus -> bot. Sirf karvachauth.servicebus.enabled=true par chalta hai.
 *
 *  inbound queue (SESSIONS ON, SessionId = mobile) -> ek customer ke messages hamesha order mein
 *  status  queue (sessions off)                    -> delivery receipts, parallel
 *
 *  Success -> complete | Error -> abandon (Service Bus 5 baar retry, phir DLQ) | Bad JSON -> turant DLQ
 *
 *  Inbound ke liye N alag processors bante hain, har ek ka APNA connection
 *  (har baar naya ServiceBusClientBuilder). Isse saare sessions ka bojh ek hi
 *  AMQP connection par nahi padta.
 *    total inbound workers = inbound-processors x inbound-sessions  (default 4 x 200 = 800)
 */
@Component
@ConditionalOnProperty(name = "karvachauth.servicebus.enabled", havingValue = "true")
public class KarvaQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(KarvaQueueConsumer.class);

    private final KarvaChauthWebhookService webhookService;
    private final String connectionString;
    private final String inboundQueue;
    private final String statusQueue;
    private final int inboundSessions;
    private final int statusConcurrency;
    private final int inboundProcessorCount;
    private final long sessionIdleMs;

    private final List<ServiceBusProcessorClient> inboundProcessors = new ArrayList<>();
    private ServiceBusProcessorClient statusProcessor;

    public KarvaQueueConsumer(
            KarvaChauthWebhookService webhookService,
            @Value("${karvachauth.servicebus.connection-string}") String connectionString,
            @Value("${karvachauth.servicebus.inbound-queue:karvachauth-inbound}") String inboundQueue,
            @Value("${karvachauth.servicebus.status-queue:karvachauth-status}") String statusQueue,
            @Value("${karvachauth.servicebus.inbound-sessions:200}") int inboundSessions,
            @Value("${karvachauth.servicebus.status-concurrency:64}") int statusConcurrency,
            @Value("${karvachauth.servicebus.inbound-processors:4}") int inboundProcessorCount,
            @Value("${karvachauth.servicebus.session-idle-ms:2000}") long sessionIdleMs) {
        this.webhookService = webhookService;
        this.connectionString = connectionString;
        this.inboundQueue = inboundQueue;
        this.statusQueue = statusQueue;
        this.inboundSessions = inboundSessions;
        this.statusConcurrency = statusConcurrency;
        this.inboundProcessorCount = Math.max(1, inboundProcessorCount);
        this.sessionIdleMs = sessionIdleMs;
    }

    @PostConstruct
    public void start() {
        for (int i = 0; i < inboundProcessorCount; i++) {
            ServiceBusProcessorClient p = new ServiceBusClientBuilder()   // naya builder = naya connection
                    .connectionString(connectionString)
                    .sessionProcessor()
                    .queueName(inboundQueue)
                    .maxConcurrentSessions(inboundSessions)                 // is processor par itne customers ek saath
                    .maxConcurrentCalls(1)                                  // ek customer ke messages ek-ek karke
                    .sessionIdleTimeout(Duration.ofMillis(sessionIdleMs))
                    .disableAutoComplete()                                  // complete/abandon hum khud karte hain
                    .processMessage(ctx -> handle(ctx, true))
                    .processError(this::onError)
                    .buildProcessorClient();
            inboundProcessors.add(p);
        }

        statusProcessor = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .processor()
                .queueName(statusQueue)
                .maxConcurrentCalls(statusConcurrency)
                .disableAutoComplete()
                .processMessage(ctx -> handle(ctx, false))
                .processError(this::onError)
                .buildProcessorClient();

        inboundProcessors.forEach(ServiceBusProcessorClient::start);
        statusProcessor.start();

        log.info("stage=CONSUMER_STARTED inbound={} processors={} sessionsEach={} totalSessions={} idleMs={} status={} concurrency={}",
                inboundQueue, inboundProcessorCount, inboundSessions, inboundProcessorCount * inboundSessions,
                sessionIdleMs, statusQueue, statusConcurrency);
    }

    private void handle(ServiceBusReceivedMessageContext ctx, boolean inbound) {
        ServiceBusReceivedMessage m = ctx.getMessage();
        Object idProp = m.getApplicationProperties().get("eventId");
        String eventId = idProp != null ? idProp.toString() : m.getCorrelationId();
        long start = System.nanoTime();

        // 1) Process. Yahan fail hua toh hi abandon/DLQ.
        try {
            String body = m.getBody().toString();
            if (inbound) {
                long receivedAt = longProp(m, "receivedAtEpochMs", m.getEnqueuedTime() != null
                        ? m.getEnqueuedTime().toInstant().toEpochMilli()
                        : System.currentTimeMillis());
                webhookService.processQueuedInbound(eventId, body, receivedAt);
            } else {
                int attempt = (int) longProp(m, "statusAttempt", 0);
                webhookService.processQueuedStatus(eventId, body, attempt);
            }

        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException bad) {
            // Retry se theek nahi hoga -> seedha DLQ (delete nahi hota, Portal mein dekh sakte ho)
            log.error("stage=CONSUMER_DEAD_LETTER eventId={} reason={}", eventId, bad.getMessage());
            settle(eventId, "deadLetter", () -> ctx.deadLetter(new DeadLetterOptions()
                    .setDeadLetterReason("BAD_MESSAGE")
                    .setDeadLetterErrorDescription(cut(bad.getMessage()))));
            return;

        } catch (Exception e) {
            // DB / Karix down -> wapas queue mein, Service Bus dobara dega (max 5, phir DLQ)
            log.error("stage=CONSUMER_RETRY eventId={} deliveryCount={} error={}",
                    eventId, m.getDeliveryCount(), e.toString());
            settle(eventId, "abandon", ctx::abandon);
            return;
        }

        // 2) Process ho gaya -> SIRF EK BAAR complete.
        //    Complete fail hua (lock khatam / connection toota) toh message dobara aayega;
        //    duplicate ko processed_messages (dedup) rokta hai. Yahan abandon/DLQ NAHI karna.
        settle(eventId, "complete", ctx::complete);

        if (log.isDebugEnabled()) {
            log.debug("stage=CONSUMER_DONE eventId={} queue={} tookMs={} queueWaitMs={}",
                    eventId, inbound ? "inbound" : "status",
                    (System.nanoTime() - start) / 1_000_000, queueWaitMs(m));
        }
    }

    /** complete/abandon/deadLetter — kabhi exception bahar nahi phenkta. */
    private void settle(String eventId, String action, Runnable op) {
        try {
            op.run();
        } catch (Exception e) {
            log.warn("stage=CONSUMER_SETTLE_FAILED eventId={} action={} error={}",
                    eventId, action, e.getMessage());
        }
    }

    private void onError(ServiceBusErrorContext ctx) {
        String msg = String.valueOf(ctx.getException().getMessage());
        // Queue khaali ho toh session nahi milta -> normal, shor mat karo
        if (msg.contains("Receive Link completed without being Active")) {
            log.debug("stage=CONSUMER_IDLE entity={} (no session available)", ctx.getEntityPath());
            return;
        }
        log.error("stage=CONSUMER_ERROR source={} entity={} error={}",
                ctx.getErrorSource(), ctx.getEntityPath(), msg);
    }

    /** Application property as long (Service Bus may give Long, Integer or String). */
    private static long longProp(ServiceBusReceivedMessage m, String key, long fallback) {
        Object v = m.getApplicationProperties().get(key);
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            try {
                return Long.parseLong(v.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    /** How long the message waited in the queue before we picked it up (-1 if unknown). */
    private static long queueWaitMs(ServiceBusReceivedMessage m) {
        return m.getEnqueuedTime() == null ? -1
                : System.currentTimeMillis() - m.getEnqueuedTime().toInstant().toEpochMilli();
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    @PreDestroy
    public void stop() {
        // Chal rahe messages khatam hone do; jo complete nahi hue wo queue mein wapas -> data loss nahi
        inboundProcessors.forEach(p -> {
            try { p.close(); } catch (Exception ignored) { }
        });
        if (statusProcessor != null) {
            try { statusProcessor.close(); } catch (Exception ignored) { }
        }
        log.info("stage=CONSUMER_STOPPED");
    }
}