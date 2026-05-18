package com.keychain.wallet;

import com.keychain.wallet.dto.response.DeductResponse;
import com.keychain.wallet.entity.Wallet;
import com.keychain.wallet.enums.WalletStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalletDeductApiTest extends AbstractIntegrationTest {

    // Test 14: happy path
    @Test
    void deduct_success() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));

        ResponseEntity<DeductResponse> response = deduct(
                walletId, "order-001", uniqueTimestamp(), "cust-001",
                new BigDecimal("100"), "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DeductResponse body = response.getBody();
        assertThat(body.walletId()).isEqualTo(walletId);
        assertThat(body.customerId()).isEqualTo("cust-001");
        assertThat(body.transactionId()).isNotBlank();
        assertThat(body.orderId()).isEqualTo("order-001");
        assertThat(body.balanceBefore()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(body.balanceAfter()).isEqualByComparingTo(new BigDecimal("400"));
    }

    // Test 15: idempotent replay returns cached response, no double deduction
    @Test
    void deduct_idempotentReplay_returnsCachedResponse() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));
        long ts = uniqueTimestamp();

        ResponseEntity<DeductResponse> first = deduct(walletId, "order-001", ts, "cust-001",
                new BigDecimal("100"), "order-service");
        ResponseEntity<DeductResponse> replay = deduct(walletId, "order-001", ts, "cust-001",
                new BigDecimal("100"), "order-service");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().transactionId()).isEqualTo(first.getBody().transactionId());
        assertThat(replay.getBody().balanceAfter()).isEqualByComparingTo(new BigDecimal("400"));
        assertThat(walletTransactionRepository.findAll()).hasSize(2); // 1 topup + 1 deduct only
    }

    // Test 16: same idempotency key, different amount → 422
    @Test
    void deduct_idempotencyKeyMismatch_422() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));
        long ts = uniqueTimestamp();

        deduct(walletId, "order-001", ts, "cust-001", new BigDecimal("100"), "order-service");

        ResponseEntity<String> mismatch = deductRaw(walletId,
                Map.of("orderId", "order-001", "requestTimestamp", ts,
                        "customerId", "cust-001", "amount", new BigDecimal("200")),
                "order-service");

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(mismatch.getBody()).containsIgnoringCase("idempotency");
    }

    // Test 17: wallet not found → 404
    @Test
    void deduct_walletNotFound_404() {
        ResponseEntity<String> response = deductRaw("nonexistent-id",
                Map.of("orderId", "o1", "requestTimestamp", uniqueTimestamp(),
                        "customerId", "cust-001", "amount", new BigDecimal("100")),
                "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Test 18: customerId in body doesn't match wallet owner → 403
    @Test
    void deduct_customerIdMismatch_403() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));

        ResponseEntity<String> response = deductRaw(walletId,
                Map.of("orderId", "o1", "requestTimestamp", uniqueTimestamp(),
                        "customerId", "cust-999", "amount", new BigDecimal("100")),
                "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // Test 19: wallet is SUSPENDED → 400
    @Test
    void deduct_walletSuspended_400() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        wallet.setStatus(WalletStatus.SUSPENDED);
        wallet.setUpdatedBy("test");
        walletRepository.save(wallet);

        ResponseEntity<String> response = deductRaw(walletId,
                Map.of("orderId", "o1", "requestTimestamp", uniqueTimestamp(),
                        "customerId", "cust-001", "amount", new BigDecimal("100")),
                "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("SUSPENDED");
    }

    // Test 20: insufficient balance → 422
    @Test
    void deduct_insufficientBalance_422() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("50"));

        ResponseEntity<String> response = deductRaw(walletId,
                Map.of("orderId", "o1", "requestTimestamp", uniqueTimestamp(),
                        "customerId", "cust-001", "amount", new BigDecimal("100")),
                "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("Insufficient");
    }

    // Test 21: deduct exactly the full balance → 200, balanceAfter=0
    @Test
    void deduct_exactBalance_success() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("100"));

        ResponseEntity<DeductResponse> response = deduct(walletId, "order-001",
                uniqueTimestamp(), "cust-001", new BigDecimal("100"), "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balanceAfter()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Test 22: blank orderId → 400
    @Test
    void deduct_blankOrderId_400() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = deductRaw(walletId,
                Map.of("orderId", "", "requestTimestamp", uniqueTimestamp(),
                        "customerId", "cust-001", "amount", new BigDecimal("10")),
                "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("orderId");
    }

    // Test 23: missing requestTimestamp → 400
    @Test
    void deduct_missingRequestTimestamp_400() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = deductRaw(walletId,
                Map.of("orderId", "o1",
                        "customerId", "cust-001", "amount", new BigDecimal("10")),
                "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("requestTimestamp");
    }

    // Test 24: blank customerId → 400
    @Test
    void deduct_blankCustomerId_400() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = deductRaw(walletId,
                Map.of("orderId", "o1", "requestTimestamp", uniqueTimestamp(),
                        "customerId", "", "amount", new BigDecimal("10")),
                "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("customerId");
    }

    // Test 25: negative amount → 400
    @Test
    void deduct_invalidAmount_400() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = deductRaw(walletId,
                Map.of("orderId", "o1", "requestTimestamp", uniqueTimestamp(),
                        "customerId", "cust-001", "amount", new BigDecimal("-5")),
                "order-service");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Test 26a: user token calling deduct → 403 (SERVICE role required)
    @Test
    void deduct_userToken_403() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("500"));

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/deduct",
                HttpMethod.POST,
                jsonEntity(Map.of("orderId", "o1", "requestTimestamp", uniqueTimestamp(),
                        "customerId", "cust-001", "amount", new BigDecimal("10")), "cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // Test 26: no auth → 401
    @Test
    void deduct_noAuth_401() {
        String walletId = createWallet("cust-001");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/deduct",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("orderId", "o1", "requestTimestamp", uniqueTimestamp(),
                        "customerId", "cust-001", "amount", new BigDecimal("10")), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
