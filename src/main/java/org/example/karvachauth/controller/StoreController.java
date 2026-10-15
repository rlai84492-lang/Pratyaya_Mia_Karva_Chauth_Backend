package org.example.karvachauth.controller;

import lombok.RequiredArgsConstructor;
import org.example.karvachauth.entity.MiaStore;
//import org.example.karvachauth.serviceImple.NearbyStoreService;
import org.example.karvachauth.service.NearbyStoreService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KC's own store API (same response format as the DT one) — for testing / other uses.
 * The bot itself does NOT call this over HTTP; it uses NearbyStoreService directly.
 *
 * GET http://localhost:8086/api/karvachauth/store-names/nearby?lat=12.97&lng=77.59&limit=3
 */
@RestController
@RequestMapping("/api/karvachauth/store-names")
@RequiredArgsConstructor
public class StoreController {

    private final NearbyStoreService nearbyStoreService;

    @GetMapping(value = "/nearby", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> nearby(@RequestParam double lat,
                                                      @RequestParam double lng,
                                                      @RequestParam(defaultValue = "3") int limit) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "invalid lat/lng"));
        }
        List<MiaStore> stores = nearbyStoreService.nearest(lat, lng, Math.min(Math.max(limit, 1), 10));
        List<Map<String, Object>> out = stores.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shortName", s.getShortName());
            m.put("city", s.getCity());
            m.put("distanceKm", Math.round(NearbyStoreService.distanceKm(lat, lng,
                    s.getLatitude().doubleValue(), s.getLongitude().doubleValue()) * 10) / 10.0);
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("count", out.size());
        body.put("stores", out);
        return ResponseEntity.ok(body);
    }
}