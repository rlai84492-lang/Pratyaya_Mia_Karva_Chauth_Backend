package org.example.karvachauth.repository;

import org.example.karvachauth.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
}