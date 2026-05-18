package com.keychain.wallet.entity;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import com.keychain.wallet.enums.TransactionReferenceType;
import com.keychain.wallet.enums.TransactionStatus;
import com.keychain.wallet.enums.TransactionType;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "wallet_transaction")
public class WalletTransaction {

    @Id
    @Column(length = 21)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 18, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 18, scale = 2)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "reference_id", length = 128)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 32)
    private TransactionReferenceType referenceType;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    @PrePersist
    void prePersist() {
        id = NanoIdUtils.randomNanoId();
        createdAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public Wallet getWallet() { return wallet; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public TransactionStatus getStatus() { return status; }
    public String getReferenceId() { return referenceId; }
    public TransactionReferenceType getReferenceType() { return referenceType; }
    public String getFailureReason() { return failureReason; }
    public String getMetadata() { return metadata; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }

    public void setWallet(Wallet wallet) { this.wallet = wallet; }
    public void setType(TransactionType type) { this.type = type; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setBalanceBefore(BigDecimal balanceBefore) { this.balanceBefore = balanceBefore; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public void setStatus(TransactionStatus status) { this.status = status; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
    public void setReferenceType(TransactionReferenceType referenceType) { this.referenceType = referenceType; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
