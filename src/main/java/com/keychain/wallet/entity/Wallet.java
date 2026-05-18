package com.keychain.wallet.entity;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import com.keychain.wallet.enums.WalletStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "wallet")
public class Wallet {

    @Id
    @Column(length = 21)
    private String id;

    @Column(name = "customer_id", unique = true, nullable = false, length = 64)
    private String customerId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletStatus status = WalletStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    @PrePersist
    void prePersist() {
        id = NanoIdUtils.randomNanoId();
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public WalletStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }

    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public void setStatus(WalletStatus status) { this.status = status; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
