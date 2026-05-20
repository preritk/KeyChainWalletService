package com.keychain.wallet;

import com.keychain.wallet.dto.response.DeductResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalletFlowApiTest extends AbstractIntegrationTest {

    // ── TopUp → Deduct flows ──────────────────────────────────────────────────

    // Test 40: topup then deduct — balance and transaction count are correct
    @Test
    void flow_topUp_thenDeduct_balanceCorrect() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));
        deduct(walletId, "order-001", "cust-001",
                new BigDecimal("100"), "order-service");

        ResponseEntity<String> balance = rest.exchange(
                "/wallets/" + walletId + "/balance",
                HttpMethod.GET, authEntity("cust-001"), String.class);
        assertThat(balance.getBody()).contains("400");
        assertThat(walletTransactionRepository.findAll()).hasSize(2);
    }

    // Test 41: multiple topups then one large deduct
    @Test
    void flow_multipleTopUps_thenDeduct() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("100"));
        topUp(walletId, "cust-001", new BigDecimal("200"));
        topUp(walletId, "cust-001", new BigDecimal("300"));

        ResponseEntity<DeductResponse> response = deduct(walletId, "order-001",
                "cust-001", new BigDecimal("250"), "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balanceBefore()).isEqualByComparingTo(new BigDecimal("600"));
        assertThat(response.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("350"));
        assertThat(walletTransactionRepository.findAll()).hasSize(4);
    }

    // Test 42: one topup, multiple sequential deducts — balance decreases correctly
    @Test
    void flow_topUp_multipleDeducts_balanceDecreases() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));

        deduct(walletId, "order-001", "cust-001",
                new BigDecimal("200"), "order-service");
        deduct(walletId, "order-002", "cust-001",
                new BigDecimal("150"), "order-service");
        ResponseEntity<DeductResponse> last = deduct(walletId, "order-003",
                "cust-001", new BigDecimal("100"), "order-service");

        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(last.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(walletTransactionRepository.findAll()).hasSize(4);
    }

    // Test 43: deduct all balance, then try to deduct more → 422
    @Test
    void flow_deductAll_thenDeductMore_fails() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("100"));
        deduct(walletId, "order-001", "cust-001",
                new BigDecimal("100"), "order-service");

        ResponseEntity<String> overflow = deductRaw(walletId,
                Map.of("orderId", "order-002",
                        "customerId", "cust-001", "amount", new BigDecimal("1")),
                "order-service");

        assertThat(overflow.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<String> balance = rest.exchange(
                "/wallets/" + walletId + "/balance",
                HttpMethod.GET, authEntity("cust-001"), String.class);
        assertThat(balance.getBody()).contains("0");
    }

    // Test 44: multiple small topups, then one large deduct that spans them all
    @Test
    void flow_multipleSmallTopUps_largeDeduct_succeeds() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("50"));
        topUp(walletId, "cust-001", new BigDecimal("50"));
        topUp(walletId, "cust-001", new BigDecimal("50"));

        ResponseEntity<DeductResponse> response = deduct(walletId, "order-001",
                "cust-001", new BigDecimal("130"), "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("20"));
    }

    // Test 45: interleaved topups and deducts — final balance and ordering correct
    @Test
    void flow_interleavedTopUpsAndDeducts() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("100"));
        deduct(walletId, "order-001", "cust-001",
                new BigDecimal("50"), "order-service");
        topUp(walletId, "cust-001", new BigDecimal("200"));
        deduct(walletId, "order-002", "cust-001",
                new BigDecimal("30"), "order-service");

        ResponseEntity<String> balance = rest.exchange(
                "/wallets/" + walletId + "/balance",
                HttpMethod.GET, authEntity("cust-001"), String.class);
        assertThat(balance.getBody()).contains("220");

        ResponseEntity<String> txns = rest.exchange(
                "/wallets/" + walletId + "/transactions",
                HttpMethod.GET, authEntity("cust-001"), String.class);
        assertThat(walletTransactionRepository.findAll()).hasSize(4);
        // newest first: last deduct (30) should appear before first topup (100)
        String body = txns.getBody();
        assertThat(body.indexOf("DEDUCTION")).isLessThan(body.lastIndexOf("TOPUP"));
    }

    // Test 46: exhaust balance, topup again, deduct again — full lifecycle
    @Test
    void flow_runToExhaustion_thenTopUp_thenDeduct() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("100"));
        deduct(walletId, "order-001", "cust-001",
                new BigDecimal("100"), "order-service");

        ResponseEntity<String> overflow = deductRaw(walletId,
                Map.of("orderId", "order-002",
                        "customerId", "cust-001", "amount", new BigDecimal("1")),
                "order-service");
        assertThat(overflow.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        topUp(walletId, "cust-001", new BigDecimal("200"));
        ResponseEntity<DeductResponse> final_ = deduct(walletId, "order-003",
                "cust-001", new BigDecimal("150"), "order-service");

        assertThat(final_.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(final_.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("50"));
        // 4 successful transactions: 2 topups + 2 successful deducts
        assertThat(walletTransactionRepository.findAll()).hasSize(4);
    }

    // ── Idempotency deep tests ─────────────────────────────────────────────────

    // Test 47: replaying the same request 3 times → only 1 deduction, all responses identical
    @Test
    void idempotency_replayThreeTimes_singleDeduction() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));

        ResponseEntity<DeductResponse> r1 = deduct(walletId, "order-001", "cust-001",
                new BigDecimal("100"), "order-service");
        ResponseEntity<DeductResponse> r2 = deduct(walletId, "order-001", "cust-001",
                new BigDecimal("100"), "order-service");
        ResponseEntity<DeductResponse> r3 = deduct(walletId, "order-001", "cust-001",
                new BigDecimal("100"), "order-service");

        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getBody().transactionId()).isEqualTo(r1.getBody().transactionId());
        assertThat(r3.getBody().transactionId()).isEqualTo(r1.getBody().transactionId());
        assertThat(walletTransactionRepository.findAll()).hasSize(2); // 1 topup + 1 deduct
        assertThat(idempotencyRecordRepository.findAll()).hasSize(1);

        ResponseEntity<String> balance = rest.exchange(
                "/wallets/" + walletId + "/balance",
                HttpMethod.GET, authEntity("cust-001"), String.class);
        assertThat(balance.getBody()).contains("400"); // deducted only once
    }

    // Test 48: same key, different amount → 422 (hash mismatch)
    @Test
    void idempotency_sameKey_differentAmount_422() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));

        deduct(walletId, "order-001", "cust-001", new BigDecimal("100"), "order-service");

        ResponseEntity<String> mismatch = deductRaw(walletId,
                Map.of("orderId", "order-001",
                        "customerId", "cust-001", "amount", new BigDecimal("200")),
                "order-service");

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // balance unchanged — only first deduct applied
        ResponseEntity<String> balance = rest.exchange(
                "/wallets/" + walletId + "/balance",
                HttpMethod.GET, authEntity("cust-001"), String.class);
        assertThat(balance.getBody()).contains("400");
    }

    // Test 49: different orderId → distinct idempotency keys → treated as two independent deductions
    @Test
    void idempotency_differentOrderId_treatedAsDistinctKeys() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));

        // key = "order-001"
        deduct(walletId, "order-001", "cust-001", new BigDecimal("100"), "order-service");

        // key = "order-002" — different key, treated as a fresh deduction
        ResponseEntity<DeductResponse> second = deduct(walletId, "order-002", "cust-001",
                new BigDecimal("100"), "order-service");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("300"));
        assertThat(walletTransactionRepository.findAll()).hasSize(3); // 1 topup + 2 distinct deducts
    }

    // Test 50: same key, different customerId in body → 422 (hash mismatch, checked before ownership)
    @Test
    void idempotency_sameKey_differentCustomerId_422() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));

        deduct(walletId, "order-001", "cust-001", new BigDecimal("100"), "order-service");

        // same key, different customerId → SHA-256 hash differs → 422 before ownership check
        ResponseEntity<String> mismatch = deductRaw(walletId,
                Map.of("orderId", "order-001",
                        "customerId", "cust-002", "amount", new BigDecimal("100")),
                "order-service");

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // Test 51: failed deduct (insufficient balance) leaves no idempotency record;
    // retrying the same orderId with corrected amount succeeds
    @Test
    void idempotency_freshKeyAfterFailedAttempt_succeeds() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("50"));

        // first attempt: insufficient balance → 422 → no idempotency record stored
        ResponseEntity<String> failed = deductRaw(walletId,
                Map.of("orderId", "order-001",
                        "customerId", "cust-001", "amount", new BigDecimal("100")),
                "order-service");
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(idempotencyRecordRepository.findAll()).isEmpty(); // nothing persisted

        // same orderId, corrected lower amount → succeeds (no record was stored from failed attempt)
        ResponseEntity<DeductResponse> success = deduct(walletId, "order-001",
                "cust-001", new BigDecimal("30"), "order-service");
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(success.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(idempotencyRecordRepository.findAll()).hasSize(1);
    }

    // Test 52: multiple orders each with unique keys — all succeed, correct record count
    @Test
    void idempotency_multipleOrders_uniqueKeys_allSucceed() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("300"));

        deduct(walletId, "order-001", "cust-001",
                new BigDecimal("100"), "order-service");
        deduct(walletId, "order-002", "cust-001",
                new BigDecimal("100"), "order-service");
        ResponseEntity<DeductResponse> third = deduct(walletId, "order-003",
                "cust-001", new BigDecimal("100"), "order-service");

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(third.getBody().balanceAfter()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(walletTransactionRepository.findAll()).hasSize(4); // 1 topup + 3 deducts
        assertThat(idempotencyRecordRepository.findAll()).hasSize(3);
    }

    // Test 53: replaying K1 after K2 was also processed — returns K1's original cached response
    @Test
    void idempotency_replay_afterAnotherDeduct_stillReturnsCached() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));

        ResponseEntity<DeductResponse> r1 = deduct(walletId, "order-001", "cust-001",
                new BigDecimal("100"), "order-service");
        deduct(walletId, "order-002", "cust-001",
                new BigDecimal("100"), "order-service");

        // Replay order-001: should return cached response from when balance was 500→400, not current 300
        ResponseEntity<DeductResponse> replay = deduct(walletId, "order-001", "cust-001",
                new BigDecimal("100"), "order-service");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().transactionId()).isEqualTo(r1.getBody().transactionId());
        assertThat(replay.getBody().balanceBefore()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(replay.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("400"));
    }
}
