package com.keychain.wallet.manager;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.keychain.wallet.dto.request.DeductRequest;
import com.keychain.wallet.dto.response.BalanceResponse;
import com.keychain.wallet.dto.response.DeductResponse;
import com.keychain.wallet.dto.response.CursorPagedResponse;
import com.keychain.wallet.dto.response.TopUpResponse;
import com.keychain.wallet.dto.response.TransactionResponse;
import com.keychain.wallet.dto.response.WalletResponse;
import com.keychain.wallet.entity.IdempotencyRecord;
import com.keychain.wallet.entity.Wallet;
import com.keychain.wallet.entity.WalletTransaction;
import com.keychain.wallet.enums.TransactionReferenceType;
import com.keychain.wallet.enums.TransactionStatus;
import com.keychain.wallet.enums.TransactionType;
import com.keychain.wallet.enums.WalletStatus;
import com.keychain.wallet.exception.IdempotencyKeyMismatchException;
import com.keychain.wallet.exception.InsufficientBalanceException;
import com.keychain.wallet.exception.InvalidCursorException;
import com.keychain.wallet.exception.WalletAccessDeniedException;
import com.keychain.wallet.exception.WalletAlreadyExistsException;
import com.keychain.wallet.exception.WalletNotActiveException;
import com.keychain.wallet.exception.WalletNotFoundException;
import com.keychain.wallet.repository.IdempotencyRecordRepository;
import com.keychain.wallet.repository.WalletRepository;
import com.keychain.wallet.repository.WalletTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

@Service
public class WalletManager {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public WalletManager(WalletRepository walletRepository,
                         WalletTransactionRepository walletTransactionRepository,
                         IdempotencyRecordRepository idempotencyRecordRepository,
                         ObjectMapper objectMapper) {
        this.walletRepository = walletRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WalletResponse createWallet(String customerId) {
        if (walletRepository.existsByCustomerId(customerId)) {
            throw new WalletAlreadyExistsException(customerId);
        }
        Wallet wallet = new Wallet();
        wallet.setCustomerId(customerId);
        wallet.setCreatedBy(customerId);
        wallet.setUpdatedBy(customerId);
        Wallet saved = walletRepository.save(wallet);
        return new WalletResponse(saved.getId(), saved.getCustomerId(),
                saved.getBalance(), saved.getCurrency(), saved.getStatus().name());
    }

    @Transactional
    public TopUpResponse topUp(String walletId, String requesterId, BigDecimal amount) {
        Wallet wallet = fetchWalletForUpdate(walletId);
        requireOwnership(wallet, requesterId);
        requireActive(wallet);

        BigDecimal before = wallet.getBalance();
        BigDecimal after = before.add(amount);
        wallet.setBalance(after);
        wallet.setUpdatedBy(requesterId);
        walletRepository.save(wallet);

        WalletTransaction txn = buildAndSaveTransaction(wallet, TransactionType.TOPUP,
                amount, before, after, null, TransactionReferenceType.CUSTOMER_TOPUP, requesterId);

        return new TopUpResponse(wallet.getId(), wallet.getCustomerId(), txn.getId(),
                amount, before, after, wallet.getCurrency());
    }

    @Transactional
    public DeductResponse deduct(String walletId, DeductRequest request, String callerSubject) {
        String idempotencyKey = request.orderId() + "_" + request.requestTimestamp();
        String hash = computeHash(walletId, request.orderId(), request.customerId(), request.amount());

        DeductResponse cached = checkIdempotency(idempotencyKey, hash);
        if (cached != null) return cached;

        Wallet wallet = fetchWalletForUpdate(walletId);

        cached = checkIdempotency(idempotencyKey, hash);
        if (cached != null) return cached;

        requireOwnership(wallet, request.customerId());
        requireActive(wallet);
        if (wallet.getBalance().compareTo(request.amount()) < 0)
            throw new InsufficientBalanceException(wallet.getBalance(), request.amount());

        BigDecimal before = wallet.getBalance();
        BigDecimal after = before.subtract(request.amount());
        wallet.setBalance(after);
        wallet.setUpdatedBy(callerSubject);
        walletRepository.save(wallet);

        WalletTransaction txn = buildAndSaveTransaction(wallet, TransactionType.DEDUCTION,
                request.amount(), before, after,
                request.orderId(), TransactionReferenceType.ORDER_DEDUCTION, callerSubject);

        DeductResponse response = new DeductResponse(
                wallet.getId(), wallet.getCustomerId(), txn.getId(),
                request.orderId(), request.amount(), before, after, wallet.getCurrency());

        try {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setIdempotencyKey(idempotencyKey);
            record.setRequestHash(hash);
            record.setResponseBody(objectMapper.writeValueAsString(response));
            idempotencyRecordRepository.save(record);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize deduct response for idempotency record", e);
        }

        return response;
    }

    private DeductResponse checkIdempotency(String key, String hash) {
        return idempotencyRecordRepository.findById(key).map(rec -> {
            if (!rec.getRequestHash().equals(hash))
                throw new IdempotencyKeyMismatchException(key);
            try {
                return objectMapper.readValue(rec.getResponseBody(), DeductResponse.class);
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to deserialize cached deduct response", e);
            }
        }).orElse(null);
    }

    private Wallet fetchWallet(String walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    private Wallet fetchWalletForUpdate(String walletId) {
        return walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    private void requireOwnership(Wallet wallet, String callerId) {
        if (callerId != null && !wallet.getCustomerId().equals(callerId))
            throw new WalletAccessDeniedException();
    }

    private void requireActive(Wallet wallet) {
        if (wallet.getStatus() != WalletStatus.ACTIVE)
            throw new WalletNotActiveException(wallet.getStatus().name());
    }

    private WalletTransaction buildAndSaveTransaction(
            Wallet wallet, TransactionType type, BigDecimal amount,
            BigDecimal before, BigDecimal after,
            String referenceId, TransactionReferenceType referenceType, String createdBy) {
        WalletTransaction txn = new WalletTransaction();
        txn.setWallet(wallet);
        txn.setType(type);
        txn.setAmount(amount);
        txn.setBalanceBefore(before);
        txn.setBalanceAfter(after);
        txn.setStatus(TransactionStatus.SUCCESS);
        txn.setReferenceId(referenceId);
        txn.setReferenceType(referenceType);
        txn.setCreatedBy(createdBy);
        return walletTransactionRepository.save(txn);
    }

    private String computeHash(String walletId, String orderId, String customerId, BigDecimal amount) {
        String data = walletId + "|" + orderId + "|" + customerId + "|"
                + amount.stripTrailingZeros().toPlainString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String walletId, String callerId) {
        Wallet wallet = fetchWallet(walletId);
        requireOwnership(wallet, callerId);
        return new BalanceResponse(wallet.getId(), wallet.getCustomerId(),
                wallet.getBalance(), wallet.getCurrency());
    }

    @Transactional(readOnly = true)
    public CursorPagedResponse<TransactionResponse> getTransactions(
            String walletId, String callerId, int size, String nextToken) {
        Wallet wallet = fetchWallet(walletId);
        requireOwnership(wallet, callerId);

        int cappedSize = Math.min(Math.max(size, 1), 50);
        int fetchLimit = cappedSize + 1;

        List<WalletTransaction> rows;
        if (nextToken == null) {
            rows = walletTransactionRepository.findFirstPage(wallet.getId(), fetchLimit);
        } else {
            try {
                String decoded = new String(Base64.getDecoder().decode(nextToken), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", 2);
                if (parts.length != 2) throw new InvalidCursorException();
                OffsetDateTime cursorCreatedAt = OffsetDateTime.parse(parts[0]);
                String cursorId = parts[1];
                rows = walletTransactionRepository.findNextPage(
                        wallet.getId(), cursorCreatedAt, cursorId, fetchLimit);
            } catch (InvalidCursorException e) {
                throw e;
            } catch (Exception e) {
                throw new InvalidCursorException();
            }
        }

        boolean hasMore = rows.size() > cappedSize;
        List<WalletTransaction> page = hasMore ? rows.subList(0, cappedSize) : rows;

        String responseNextToken = null;
        if (hasMore) {
            WalletTransaction last = page.get(page.size() - 1);
            String raw = last.getCreatedAt().toString() + "|" + last.getId();
            responseNextToken = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        List<TransactionResponse> content = page.stream()
                .map(t -> new TransactionResponse(t.getId(), t.getType().name(), t.getAmount(),
                        t.getBalanceBefore(), t.getBalanceAfter(), t.getStatus().name(),
                        t.getReferenceId(),
                        t.getReferenceType() != null ? t.getReferenceType().name() : null,
                        t.getCreatedAt()))
                .toList();

        return new CursorPagedResponse<>(content, page.size(), responseNextToken);
    }
}
