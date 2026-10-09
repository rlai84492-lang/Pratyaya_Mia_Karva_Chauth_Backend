package org.example.karvachauth.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.karvachauth.service.BotEngineService;
import org.example.karvachauth.service.KarvaChauthWebhookService;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Karix webhook for Karva Chauth campaign.
 * Public URL : https://karvachauth-rlai.indiasouthcentral.cloudapp.azure.com/karvachauth-webhook
 * Internal   : POST http://localhost:8086/webhook/karvachauth
 *
 * Thin controller: read raw body + token -> service -> return quickly.
 * Real customer messages are answered by InboundMessageConsumer (from Service Bus), not here.
 */
@Slf4j
@RestController
@RequestMapping("/webhook/karvachauth")
@RequiredArgsConstructor
public class KarvaChauthWebhookController {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{16,36}$");

    private final KarvaChauthWebhookService webhookService;
    private final BotEngineService botEngineService;     // CHANGED: unused KarixService removed

    /**
     * CHANGED: /test is off unless karvachauth.test.enabled=true (dev only).
     * This controller is on the public URL — without this, anyone could make Mia message any number.
     */
    @Value("${karvachauth.test.enabled:false}")
    private boolean testEnabled;

    /** Raw String body: a Karix payload change never causes a 400, exact payload is kept. */
    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> receiveWebhook(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "X-Webhook-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken,
            @RequestHeader(value = "X-Request-Id", required = false) String nginxRequestId,
            @RequestHeader(value = "X-Real-IP", required = false) String sourceIp) {

        // Correlation id = nginx $request_id (same id in nginx log + app log + DB)
        String eventId = (nginxRequestId != null && SAFE_ID.matcher(nginxRequestId).matches())
                ? nginxRequestId
                : UUID.randomUUID().toString();

        MDC.put("eventId", eventId);
        try {
            log.info("stage=HTTP_RECEIVED eventId={} sourceIp={} bytes={}",
                    eventId, sourceIp, rawBody == null ? 0 : rawBody.length());

            String token = headerToken != null ? headerToken : queryToken;
            KarvaChauthWebhookService.WebhookResult result = webhookService.handleWebhook(eventId, rawBody, token);

            log.info("stage=HTTP_RESPONDED eventId={} httpStatus={} result={}",
                    eventId, result.getHttpStatus().value(), result.name());

            return ResponseEntity.status(result.getHttpStatus())
                    .header("X-Request-Id", eventId)
                    .body(Map.of("status", result.name(), "eventId", eventId));

        } catch (Exception e) {
            log.error("stage=FAILED failedStage=CONTROLLER eventId={} error={}", eventId, e.getMessage(), e);
            return ResponseEntity.status(503)
                    .header("X-Request-Id", eventId)
                    .body(Map.of("status", "UNAVAILABLE", "eventId", eventId));
        } finally {
            MDC.clear();   // Tomcat threads are reused
        }
    }

    /** Lightweight health check, no DB / Service Bus call. */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "karvachauth-webhook");
    }

    @PostMapping("/test")
    public ResponseEntity<String> testTrigger(
            @RequestParam String phone,
            @RequestParam String template,
            @RequestParam(required = false) String payload,
            @RequestParam(required = false) String text) {

        if (!testEnabled) {
            return ResponseEntity.notFound().build();   // invisible unless enabled
        }


        if ("opener".equalsIgnoreCase(template)) {
            botEngineService.sendOpener(phone, null);
            return ResponseEntity.ok("Opener sent");
        }

        String path = switch (template.toLowerCase()) {
            case "wife"    -> "WIFE";
            case "husband" -> "HUSBAND";
            case "sparkle" -> "SPARKLE";
            default        -> null;
        };
        if (path == null) {
            return ResponseEntity.badRequest().body("template must be wife / husband / sparkle");
        }

        // ---------- Typed reply (answers to 1J / 2F questions) ----------
        if (text != null && !text.isBlank()) {
            boolean handled = botEngineService.onTextMessage(phone, text);
            return handled
                    ? ResponseEntity.ok("Handled text as " + template)
                    : ResponseEntity.badRequest().body("Text not handled — no question pending, or no active session");
        }

        // ---------- No payload -> opener ----------
        if (payload == null || payload.isBlank()) {
            switch (path) {
                case "WIFE"    -> botEngineService.startWifeFlow(phone, null);
                case "HUSBAND" -> botEngineService.startHusbandFlow(phone, null);
                case "SPARKLE" -> botEngineService.startSparkleFlow(phone, null);
            }
            return ResponseEntity.ok("Opener sent as " + template);
        }

        // ---------- Payload -> button tap ----------
        boolean handled = botEngineService.onButtonTap(phone, path, payload);
        return handled
                ? ResponseEntity.ok("Handled payload " + payload + " as " + template)
                : ResponseEntity.badRequest().body("Payload '" + payload + "' not handled for " + template
                + " (unknown, not built yet, or no active session)");
    }
}