package org.example.karvachauth.loadtest;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FAKE Karix send API - only active with the "loadtest" profile.
 * It waits like the real Karix API (200-400 ms) and returns a success JSON,
 * so the load test never sends real WhatsApp messages.
 *
 * Use:  run the app with  --spring.profiles.active=prod,loadtest
 *       and point the Karix send URL property (whatever your KC code uses) to:
 *       http://127.0.0.1:8086/mock/karix/send
 */
@Profile("loadtest")
@RestController
@RequestMapping("/mock/karix")
public class MockKarixController {

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody(required = false) String body) throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextInt(200, 401));   // simulate Karix latency
        return ResponseEntity.ok(Map.of(
                "statusCode", "200",
                "statusDesc", "Successfully accepted",
                "mid", "MOCK" + UUID.randomUUID()));
    }
}