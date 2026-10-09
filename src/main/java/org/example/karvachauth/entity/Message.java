package org.example.karvachauth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_karix_id", columnList = "karixMessageId"),
        @Index(name = "idx_messages_phone_created", columnList = "phone, createdAt"),
        @Index(name = "idx_messages_created", columnList = "createdAt"),
        @Index(name = "idx_messages_session ",columnList = "id, sessionId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long customerId;

    private Long sessionId;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 10)
    private String direction;            // INBOUND / OUTBOUND

    @Column(length = 100)
    private String karixMessageId;

    @Column(length = 20)
    private String status;               // RECEIVED / SENT / DELIVERED / READ / FAILED

    @Column(length = 50)
    private String step;                 // flow step at the time of the message

    @Column(length = 20)
    private String path;

    @Column(length = 30)
    private String messageType;          // text / button / interactive / template / carousel / flow

    @Column(length = 100)
    private String templateName;         // outbound templates only

    @Column(length = 100)
    private String buttonPayload;        // inbound taps — the ID we route on

    @Column(length = 100)
    private String buttonTitle;

    @Column(columnDefinition = "TEXT")
    private String contentText;

    @Column(length = 20)
    private String errorCode;

    @Column(length = 500)
    private String errorReason;

    private LocalDateTime createdAt;
    private LocalDateTime statusUpdatedAt;

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

