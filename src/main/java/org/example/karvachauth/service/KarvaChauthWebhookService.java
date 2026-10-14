package org.example.karvachauth.service;

import org.springframework.http.HttpStatus;

public interface KarvaChauthWebhookService {

    /** Validate -> identify event -> publish to Service Bus -> return result. */
    WebhookResult handleWebhook(String eventId, String rawBody, String token);





    enum WebhookResult {
        ACCEPTED(HttpStatus.OK),                      // accepted
        IGNORED(HttpStatus.OK),                       // empty / not JSON / too large -> 200 so Karix doesn't retry garbage
        UNAUTHORIZED(HttpStatus.UNAUTHORIZED),        // wrong/missing token
        UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);  // Service Bus down -> Karix should retry

        private final HttpStatus httpStatus;

        WebhookResult(HttpStatus httpStatus) { this.httpStatus = httpStatus; }

        public HttpStatus getHttpStatus() { return httpStatus; }
    }



//    /** Queue consumer -> inbound (button / text / location). Exception = Service Bus retry. */
//    void processQueuedInbound(String eventId, String rawBody) throws Exception;
//
//    /** Queue consumer -> delivery receipt. Exception = Service Bus retry. */
//    void processQueuedStatus(String eventId, String rawBody) throws Exception;


    /** Queue consumer -> inbound. receivedAtEpochMs = when our webhook accepted it. */
    void processQueuedInbound(String eventId, String rawBody, long receivedAtEpochMs) throws Exception;

    /** Queue consumer -> delivery receipt. attempt = how many times it was re-queued (0 first time). */
    void processQueuedStatus(String eventId, String rawBody, int attempt) throws Exception;
}