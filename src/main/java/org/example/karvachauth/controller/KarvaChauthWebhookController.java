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

import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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


    // ---------- DEV FORWARD (VM -> laptop ngrok) ----------
    @Value("${karvachauth.forward.enabled:false}")
    private boolean forwardEnabled;

    @Value("${karvachauth.forward.url:}")
    private String forwardUrl;

    /** Forward kabhi Karix ka 200 slow na kare: alag thread, chhoti queue, bhar jaaye toh drop. */
    private final ThreadPoolExecutor forwardExecutor = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "kc-dev-forward");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private final HttpClient forwardClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

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

//
//                String token = headerToken != null ? headerToken : queryToken;
//                KarvaChauthWebhookService.WebhookResult result = webhookService.handleWebhook(eventId, rawBody, token);

                // DEV: wahi raw body laptop (ngrok) par bhi bhejo. Galat token wala forward nahi hoga.
                if (result != KarvaChauthWebhookService.WebhookResult.UNAUTHORIZED) {
                    forwardToDev(eventId, rawBody, token);
                }

                log.info("stage=HTTP_RESPONDED eventId={} httpStatus={} result={}",
                        eventId, result.getHttpStatus().value(), result.name());

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


    // ------------------------------------------------------------------ DEV forward




    /** Same raw Karix payload -> ngrok (laptop). Fail ho to sirf log, prod pe koi asar nahi. */
    private void forwardToDev(String eventId, String rawBody, String token) {
        if (!forwardEnabled || rawBody == null || rawBody.isBlank()) {
            return;
        }
        forwardExecutor.execute(() -> {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(forwardUrl))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("X-Request-Id", eventId)
                        .header("ngrok-skip-browser-warning", "true")
                        .POST(HttpRequest.BodyPublishers.ofString(rawBody, StandardCharsets.UTF_8));
                if (token != null && !token.isBlank()) {
                    req.header("X-Webhook-Token", token);
                }
                HttpResponse<Void> resp = forwardClient.send(req.build(), HttpResponse.BodyHandlers.discarding());
                log.info("stage=DEV_FORWARDED eventId={} status={}", eventId, resp.statusCode());
            } catch (Exception e) {
                log.warn("stage=DEV_FORWARD_FAILED eventId={} error={}", eventId, e.getMessage());
            }
        });
    }
    @PreDestroy
    public void stopForwarder() {
        forwardExecutor.shutdown();
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