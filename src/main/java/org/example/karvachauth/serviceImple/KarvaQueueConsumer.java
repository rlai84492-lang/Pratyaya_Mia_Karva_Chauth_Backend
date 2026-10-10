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

/**
 * Service Bus -> bot. Sirf karvachauth.servicebus.enabled=true par chalta hai.
 *
 *  inbound queue (SESSIONS ON, SessionId = mobile) -> ek customer ke messages hamesha order mein
 *  status  queue (sessions off)                    -> delivery receipts, parallel
 *
 *  Success -> complete | Error -> abandon (Service Bus 5 baar retry, phir DLQ) | Bad JSON -> turant DLQ
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

    private ServiceBusProcessorClient inboundProcessor;
    private ServiceBusProcessorClient statusProcessor;

    public KarvaQueueConsumer(
            KarvaChauthWebhookService webhookService,
            @Value("${karvachauth.servicebus.connection-string}") String connectionString,
            @Value("${karvachauth.servicebus.inbound-queue:karvachauth-inbound}") String inboundQueue,
            @Value("${karvachauth.servicebus.status-queue:karvachauth-status}") String statusQueue,
            @Value("${karvachauth.servicebus.inbound-sessions:64}") int inboundSessions,
            @Value("${karvachauth.servicebus.status-concurrency:32}") int statusConcurrency) {
        this.webhookService = webhookService;
        this.connectionString = connectionString;
        this.inboundQueue = inboundQueue;
        this.statusQueue = statusQueue;
        this.inboundSessions = inboundSessions;
        this.statusConcurrency = statusConcurrency;
    }

    @PostConstruct
    public void start() {
        inboundProcessor = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sessionProcessor()
                .queueName(inboundQueue)
                .maxConcurrentSessions(inboundSessions)     // itne customers ek saath
                .maxConcurrentCalls(1)                      // ek customer ke messages ek-ek karke
                .sessionIdleTimeout(Duration.ofSeconds(2))  // customer chup -> session jaldi chhodo
                .disableAutoComplete()
                .processMessage(ctx -> handle(ctx, true))
                .processError(this::onError)
                .buildProcessorClient();

        statusProcessor = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .processor()
                .queueName(statusQueue)
                .maxConcurrentCalls(statusConcurrency)
                .disableAutoComplete()
                .processMessage(ctx -> handle(ctx, false))
                .processError(this::onError)
                .buildProcessorClient();

        inboundProcessor.start();
        statusProcessor.start();
        log.info("stage=CONSUMER_STARTED inbound={} sessions={} status={} concurrency={}",
                inboundQueue, inboundSessions, statusQueue, statusConcurrency);
    }

    private void handle(ServiceBusReceivedMessageContext ctx, boolean inbound) {
        ServiceBusReceivedMessage m = ctx.getMessage();
        Object idProp = m.getApplicationProperties().get("eventId");
        String eventId = idProp != null ? idProp.toString() : m.getCorrelationId();
        long start = System.nanoTime();
        try {
            String body = m.getBody().toString();
            if (inbound) {
                webhookService.processQueuedInbound(eventId, body);
            } else {
                webhookService.processQueuedStatus(eventId, body);
            }
            ctx.complete();
            log.info("stage=CONSUMER_DONE eventId={} queue={} tookMs={}",
                    eventId, inbound ? "inbound" : "status", (System.nanoTime() - start) / 1_000_000);

        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException bad) {
            // Retry se theek nahi hoga -> seedha DLQ (delete nahi hota, Portal mein dekh sakte ho)
            log.error("stage=CONSUMER_DEAD_LETTER eventId={} reason={}", eventId, bad.getMessage());
            ctx.deadLetter(new DeadLetterOptions()
                    .setDeadLetterReason("BAD_MESSAGE")
                    .setDeadLetterErrorDescription(cut(bad.getMessage())));

        } catch (Exception e) {
            // DB / Karix down -> wapas queue mein, Service Bus dobara dega (max 5, phir DLQ)
            log.error("stage=CONSUMER_RETRY eventId={} deliveryCount={} error={}",
                    eventId, m.getDeliveryCount(), e.getMessage(), e);
            ctx.abandon();
        }
    }

    private void onError(ServiceBusErrorContext ctx) {
        log.error("stage=CONSUMER_ERROR source={} entity={} error={}",
                ctx.getErrorSource(), ctx.getEntityPath(), ctx.getException().getMessage());
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    @PreDestroy
    public void stop() {
        // Chal rahe messages khatam hone do; jo complete nahi hue wo queue mein wapas -> data loss nahi
        if (inboundProcessor != null) inboundProcessor.close();
        if (statusProcessor != null) statusProcessor.close();
        log.info("stage=CONSUMER_STOPPED");
    }
}