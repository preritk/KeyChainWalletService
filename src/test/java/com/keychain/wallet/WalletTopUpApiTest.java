package com.keychain.wallet;

import com.keychain.wallet.dto.response.TopUpResponse;
import com.keychain.wallet.entity.Wallet;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalletTopUpApiTest extends AbstractIntegrationTest {

    // Test 4: happy path
    @Test
    void topUp_success() {
        String walletId = createWallet("cust-001");

        ResponseEntity<TopUpResponse> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                jsonEntity(Map.of("amount", new BigDecimal("100.00")), "cust-001"),
                TopUpResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TopUpResponse body = response.getBody();
        assertThat(body.walletId()).isEqualTo(walletId);
        assertThat(body.customerId()).isEqualTo("cust-001");
        assertThat(body.transactionId()).isNotBlank();
        assertThat(body.balanceBefore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.balanceAfter()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(body.currency()).isEqualTo("INR");
    }

    // Test 5: balance accumulates across multiple top-ups
    @Test
    void topUp_accumulatesBalance() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("100"));

        ResponseEntity<TopUpResponse> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                jsonEntity(Map.of("amount", new BigDecimal("200")), "cust-001"),
                TopUpResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TopUpResponse body = response.getBody();
        assertThat(body.balanceBefore()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(body.balanceAfter()).isEqualByComparingTo(new BigDecimal("300"));
    }

    // Test 6: wallet not found → 404
    @Test
    void topUp_walletNotFound_404() {
        ResponseEntity<String> response = rest.exchange(
                "/wallets/nonexistent-id/topup",
                HttpMethod.POST,
                jsonEntity(Map.of("amount", new BigDecimal("100")), "cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Test 7: wrong JWT subject (not the wallet owner) → 403
    @Test
    void topUp_wrongOwner_403() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                jsonEntity(Map.of("amount", new BigDecimal("100")), "cust-002"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // Test 8: wallet is SUSPENDED → 400
    @Test
    void topUp_walletSuspended_400() {
        String walletId = createWallet("cust-001");
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        wallet.setStatus("SUSPENDED");
        wallet.setUpdatedBy("test");
        walletRepository.save(wallet);

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                jsonEntity(Map.of("amount", new BigDecimal("100")), "cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("SUSPENDED");
    }

    // Test 9: missing amount → 400
    @Test
    void topUp_nullAmount_400() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                jsonEntity(Map.of(), "cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("amount");
    }

    // Test 10: negative amount → 400
    @Test
    void topUp_negativeAmount_400() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                jsonEntity(Map.of("amount", new BigDecimal("-10")), "cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Test 11: zero amount → 400
    @Test
    void topUp_zeroAmount_400() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                jsonEntity(Map.of("amount", BigDecimal.ZERO), "cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Test 12: more than 2 decimal places → 400
    @Test
    void topUp_tooManyDecimals_400() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                jsonEntity(Map.of("amount", new BigDecimal("10.123")), "cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Test 13: no auth → 401
    @Test
    void topUp_noAuth_401() {
        String walletId = createWallet("cust-001");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", new BigDecimal("100")), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
