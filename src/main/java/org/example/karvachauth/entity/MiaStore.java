package org.example.karvachauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Mia stores — same columns as the DT "mia_store" table (copied into the KC database).
 * KC finds the nearest stores from here, no call to the DT server.
 */
@Entity
@Table(name = "kc_mia_store")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MiaStore {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "latitude", precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "pincode", length = 10)
    private String pincode;

    @Column(name = "short_name", length = 255)
    private String shortName;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "store_code", length = 20)
    private String storeCode;

    @Column(name = "store_name", length = 255)
    private String storeName;
}