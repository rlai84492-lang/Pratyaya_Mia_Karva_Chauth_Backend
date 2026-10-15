package org.example.karvachauth.repository;

import org.example.karvachauth.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;


public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findFirstByKarixMessageId(String karixMessageId);

    /**
     * The message as we first saved it — a message has one row per status (SENT, DELIVERED, READ …),
     * all with the same karix_message_id; the lowest id is the original.
     */
    Optional<Message> findFirstByKarixMessageIdOrderByIdAsc(String karixMessageId);

    /** True if this status is already saved for this message (Karix sends some receipts twice). */
    boolean existsByKarixMessageIdAndStatus(String karixMessageId, String status);

    /** Every status row of one message. */
    List<Message> findByKarixMessageId(String karixMessageId);

    @Override
    List<Message> findAll();

    /** Full chat transcript for one journey. */
    List<Message> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<Message> findByPhoneOrderByCreatedAtDesc(String phone);

    /**
     * Step 0: her latest template message not yet linked to a session — linked to the session
     * her Step 0 tap starts. Called with messageType = "template".
     */
    Optional<Message> findFirstByPhoneAndMessageTypeAndSessionIdIsNullOrderByIdDesc(String phone, String messageType);

    /** Dashboard: e.g. countByDirectionAndStatus("OUTBOUND", "FAILED"). */
    long countByDirectionAndStatus(String direction, String status);

    long countByDirectionAndCreatedAtBetween(String direction, LocalDateTime from, LocalDateTime to);

    /** True if she sent anything (tap / text) in this session after this time. */
    boolean existsBySessionIdAndDirectionAndCreatedAtAfter(Long sessionId, String direction, LocalDateTime after);




// MessageRepository.java — ADD these 2 methods inside the interface
// (keep all your existing methods as they are)
// =====================================================================

    /** SENT / DELIVERED / READ: update only if the row's current status is LOWER (atomic, no overwrite back). */
    @Modifying
    @Transactional
    @Query("UPDATE Message m SET m.status = :status, m.statusUpdatedAt = :now " +
            "WHERE m.karixMessageId = :mid " +
            "AND (m.status IS NULL OR m.status NOT IN :sameOrHigher)")
    int updateStatusIfNewer(@Param("mid") String mid,
                            @Param("status") String status,
                            @Param("now") LocalDateTime now,
                            @Param("sameOrHigher") Collection<String> sameOrHigher);

    /** FAILED: same rule, also saves error code + reason. */
    @Modifying
    @Transactional
    @Query("UPDATE Message m SET m.status = :status, m.statusUpdatedAt = :now, " +
            "m.errorCode = :code, m.errorReason = :reason " +
            "WHERE m.karixMessageId = :mid " +
            "AND (m.status IS NULL OR m.status NOT IN :sameOrHigher)")
    int updateFailedIfNewer(@Param("mid") String mid,
                            @Param("status") String status,
                            @Param("now") LocalDateTime now,
                            @Param("code") String code,
                            @Param("reason") String reason,
                            @Param("sameOrHigher") Collection<String> sameOrHigher);


}