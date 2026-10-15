package org.example.karvachauth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A store visit from C2. Created as PENDING when she picks a store, CONFIRMED when she sends a
 * valid date & time. bookingRef (MIA-XXXXXX) is shown to the customer.
 */
@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_phone", columnList = "phone"),
        @Index(name = "idx_bookings_store_date", columnList = "storeCode, visitDate"),
        @Index(name = "idx_bookings_created", columnList = "createdAt"),
        @Index(name = "idx_bookings_session_status", columnList = "sessionId, status")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String bookingRef;           // MIA-XXXXXX

    @Column(nullable = false)
    private Long customerId;

    private Long sessionId;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 20)
    private String path;                 // WIFE / HUSBAND / SPARKLE

    @Column(length = 30)
    private String storeCode;            // not sent by the nearby-store API yet — null for now

    @Column(length = 200)
    private String storeName;            // API shortName, e.g. "Mia by Tanishq, Nexus Mall, Koramangala, Bengaluru"

    @Column(length = 50)
    private String city;                 // API city

    private LocalDate visitDate;

    @Column(length = 30)
    private String timeSlot;             // visit time, 24h "17:30" (sorts correctly)

    @Column(length = 1000)
    private String reservedSkus;         // comma-separated: her list, or the one piece she picked

    @Column(length = 20)
    @Builder.Default
    private String status = "PENDING";   // PENDING (store picked) / CONFIRMED / CANCELLED / VISITED

    private LocalDateTime createdAt;     // when she picked the store

    private LocalDateTime confirmedAt;   // when she sent a valid date & time

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
    }
}