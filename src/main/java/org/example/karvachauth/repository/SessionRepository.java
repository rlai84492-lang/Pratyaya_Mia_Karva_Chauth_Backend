package org.example.karvachauth.repository;

import org.example.karvachauth.entity.Session;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, Long> {

    /** The customer's current conversation — the first thing every tap / text loads. */
    Optional<Session> findFirstByPhoneAndIsActiveTrueOrderByIdDesc(String phone);

    /**
     * IDLE nudge: sessions quiet for longer than the idle threshold, not yet nudged,
     * still inside WhatsApp's 24-hour window, and not finished.
     *
     *   idleCutoff   = now - idleMinutes (60 in production)
     *   windowCutoff = now - 23 hours (safety margin before 24h)
     */
    @Query("""
           SELECT s FROM Session s
           WHERE s.isActive = true
             AND s.idleNudgeSent = false
             AND s.currentStep <> 'CLOSED'
             AND s.lastInboundAt < :idleCutoff
             AND s.lastInboundAt > :windowCutoff
           ORDER BY s.lastInboundAt ASC
           """)
    List<Session> findIdleSessions(@Param("idleCutoff") LocalDateTime idleCutoff,
                                   @Param("windowCutoff") LocalDateTime windowCutoff,
                                   Pageable pageable);

    long countByPathAndIsActiveTrue(String path);

    long countByCurrentStepAndIsActiveTrue(String currentStep);

    /** User ka aakhri session kisi path par (active ho ya band). */
    Optional<Session> findFirstByPhoneAndPathOrderByIdDesc(String phone, String path);


    /** User ka aakhri session kisi path par (active ho ya band). */
//    Optional<Session> findFirstByPhoneAndPathOrderByIdDesc(String phone, String path);

    /** User ka aakhri session, koi bhi path (occasion templates ke liye). */
    Optional<Session> findFirstByPhoneOrderByIdDesc(String phone);
}