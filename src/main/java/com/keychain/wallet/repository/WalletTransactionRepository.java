package com.keychain.wallet.repository;

import com.keychain.wallet.entity.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, String> {

    @Query("SELECT wt FROM WalletTransaction wt WHERE wt.wallet.id = :walletId ORDER BY wt.createdAt DESC")
    List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(@Param("walletId") String walletId);

    @Query(nativeQuery = true, value = """
            SELECT * FROM wallet_transaction
            WHERE wallet_id = :walletId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<WalletTransaction> findFirstPage(
            @Param("walletId") String walletId,
            @Param("limit") int limit);

    @Query(nativeQuery = true, value = """
            SELECT * FROM wallet_transaction
            WHERE wallet_id = :walletId
              AND (created_at < :cursorCreatedAt
                   OR (created_at = :cursorCreatedAt AND id < :cursorId))
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<WalletTransaction> findNextPage(
            @Param("walletId") String walletId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit);

}
