package org.example.karvachauth.service;//package org.example.karvachauth.serviceImple;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.karvachauth.entity.MiaStore;
import org.example.karvachauth.repository.MiaStoreRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Nearest Mia stores — KC's own copy, no dependency on the DT server.
 *
 * All Active stores (a few hundred rows) are kept in memory and refreshed every 10 minutes,
 * so a location lookup is a quick in-memory distance sort — no DB query per customer.
 * If the DB is down during a refresh, the last good list is kept.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NearbyStoreService {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final MiaStoreRepository miaStoreRepository;

    /** Immutable snapshot, swapped in one go (thread-safe reads). */
    private volatile List<MiaStore> activeStores = List.of();

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        reload();
    }

    /** Every 10 minutes — pick up store changes made in the DB without a restart. */
    @Scheduled(fixedDelayString = "${karvachauth.stores.refresh-ms:600000}",
            initialDelayString = "${karvachauth.stores.refresh-ms:600000}")
    public void reload() {
        try {
            List<MiaStore> list = miaStoreRepository.findByStatusIgnoreCase("Active").stream()
                    .filter(s -> s.getLatitude() != null && s.getLongitude() != null)
                    .filter(s -> s.getShortName() != null && !s.getShortName().isBlank())
                    .toList();
            activeStores = list;
            log.info("stage=STORES_LOADED activeStores={}", list.size());
        } catch (Exception e) {
            log.error("stage=STORES_LOAD_FAILED — keeping previous list ({} stores)", activeStores.size(), e);
        }
    }

    /** Nearest active stores to (lat, lng), closest first. */
    public List<MiaStore> nearest(double lat, double lng, int limit) {
        List<MiaStore> stores = activeStores;
        if (stores.isEmpty()) {
            reload();                       // first call after a failed startup load
            stores = activeStores;
        }
        return stores.stream()
                .sorted(Comparator.comparingDouble(s ->
                        distanceKm(lat, lng, s.getLatitude().doubleValue(), s.getLongitude().doubleValue())))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Distance in km between two points (Haversine). */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public int activeCount() {
        return activeStores.size();
    }
}