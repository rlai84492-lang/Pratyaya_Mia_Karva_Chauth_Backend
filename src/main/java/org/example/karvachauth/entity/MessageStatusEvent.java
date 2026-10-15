package org.example.karvachauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One row per Karix delivery status: SENT, DELIVERED, READ, FAILED.
 * Never updated, never overwritten — full history of every message.
 * The latest status is still kept on messages.status (dashboard reads that).
 */
@Entity
@Table(name = "message_status_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_mse_mid_status",
                columnNames = {"karix_message_id", "status"}),
        indexes = {
                @Index(name = "idx_mse_phone", columnList = "phone"),
                @Index(name = "idx_mse_received_at", columnList = "received_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "karix_message_id", nullable = false, length = 100)
    private String karixMessageId;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_reason", length = 500)
    private String errorReason;

    @Column(name = "template_id", length = 100)
    private String templateId;

    /** When it happened (Karix events.timestamp). */
    @Column(name = "event_at")
    private LocalDateTime eventAt;

    /** When our server received it. */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;
}