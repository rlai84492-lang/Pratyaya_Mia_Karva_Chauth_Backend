package org.example.karvachauth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Catalogue for the carousels (1B / 2B). Loaded from the Mia team's sheet.
 * Budget filtering is done on price, so there's no budget column to keep in sync.
 */
@Entity
@Table(name = "products", indexes = {
        @Index(name = "idx_products_browse", columnList = "category, active, price")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String sku;

    @Column(length = 200)
    private String name;

    @Column(length = 30, nullable = false)
    private String category;             // CAT_* constant

    private Integer price;               // in rupees

    @Column(length = 50)
    private String collections;

    @Column(length = 500)
    private String imageUrl;             // must be a public HTTPS URL for carousel headers

    @Column(length = 500)
    private String buyLink;              // per-product link used in 1G hint and 2C "Buy Online"

    @Builder.Default
    private Integer displayOrder = 0;    // lets the Mia team control carousel order

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = LocalDateTime.now();
    }
}
