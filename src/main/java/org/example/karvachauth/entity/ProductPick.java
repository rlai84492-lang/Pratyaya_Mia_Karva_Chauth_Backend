package org.example.karvachauth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Append-only analytics log of every product interaction — "which pendant got
 * added most", "which SKU got the most Buy Online taps". The wishlist itself
 * lives in WishlistItem; this table is never read by the flow.
 *
 * actionType: VIEWED / ADDED / HINT_SENT / BUY_CLICKED / STORE_INTENT / RESERVED
 */
@Entity
@Table(name = "product_picks", indexes = {
        @Index(name = "idx_picks_sku", columnList = "sku"),
        @Index(name = "idx_picks_action_created", columnList = "actionType, createdAt"),
        @Index(name = "idx_picks_session",columnList = "sessionId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductPick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    private Long sessionId;

    @Column(nullable = false, length = 50)
    private String sku;

    @Column(nullable = false, length = 20)
    private String actionType;

    @Column(length = 20)
    private String path;

    @Column(length = 30)
    private String category;

    @Column(length = 50)
    private String sourceStep;

    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
    }
}