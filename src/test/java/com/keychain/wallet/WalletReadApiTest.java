package com.keychain.wallet;

import com.keychain.wallet.dto.response.BalanceResponse;
import com.keychain.wallet.entity.Wallet;
import com.keychain.wallet.entity.WalletTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalletReadApiTest extends AbstractIntegrationTest {

    // ── GET /wallets/{id}/balance ──────────────────────────────────────────────

    // Test 27: returns correct balance
    @Test
    void getBalance_success() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("300"));

        ResponseEntity<BalanceResponse> response = rest.exchange(
                "/wallets/" + walletId + "/balance",
                HttpMethod.GET,
                authEntity("cust-001"),
                BalanceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BalanceResponse body = response.getBody();
        assertThat(body.walletId()).isEqualTo(walletId);
        assertThat(body.customerId()).isEqualTo("cust-001");
        assertThat(body.balance()).isEqualByComparingTo(new BigDecimal("300"));
        assertThat(body.currency()).isEqualTo("INR");
    }

    // Test 28: wallet not found → 404
    @Test
    void getBalance_walletNotFound_404() {
        ResponseEntity<String> response = rest.exchange(
                "/wallets/nonexistent/balance",
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Test 29: different JWT owner → 403
    @Test
    void getBalance_wrongOwner_403() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/balance",
                HttpMethod.GET,
                authEntity("cust-002"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // Test 30: no auth → 401
    @Test
    void getBalance_noAuth_401() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/balance",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── GET /wallets/{id}/transactions ─────────────────────────────────────────

    // Test 31: new wallet with no transactions → empty content, nextToken null
    @Test
    void getTransactions_emptyWallet() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/transactions",
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"content\":[]");
        assertThat(response.getBody()).contains("\"nextToken\":null");
    }

    // Test 32: transactions returned in reverse chronological order (newest first)
    @Test
    void getTransactions_reverseChronologicalOrder() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("100"));
        topUp(walletId, "cust-001", new BigDecimal("200"));

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/transactions",
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        int idx200 = body.indexOf("200");
        int idx100 = body.lastIndexOf("100");
        assertThat(idx200).isLessThan(idx100);
        assertThat(body).contains("\"nextToken\":null");
    }

    // Test 33: first page of 15 txns (default size=10) → 10 rows, non-null nextToken
    @Test
    void getTransactions_firstPage_hasNextToken() {
        String walletId = createWallet("cust-001");
        insertTransactions(walletId, 15);

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/transactions",
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("\"size\":10");
        assertThat(body).doesNotContain("\"nextToken\":null");
    }

    // Test 34: use nextToken from first page → returns remaining 5 rows, nextToken null
    @Test
    void getTransactions_secondPage_viaNextToken() {
        String walletId = createWallet("cust-001");
        insertTransactions(walletId, 15);

        // first page
        ResponseEntity<String> first = rest.exchange(
                "/wallets/" + walletId + "/transactions?size=10",
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);
        String token = extractNextToken(first.getBody());
        assertThat(token).isNotNull();

        // second page
        ResponseEntity<String> second = rest.exchange(
                "/wallets/" + walletId + "/transactions?size=10&nextToken=" + token,
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("\"size\":5");
        assertThat(second.getBody()).contains("\"nextToken\":null");
    }

    // Test 35: custom size=3 on 5 txns → 3 rows + token, then 2 rows + null token
    @Test
    void getTransactions_customSizePagination() {
        String walletId = createWallet("cust-001");
        insertTransactions(walletId, 5);

        ResponseEntity<String> first = rest.exchange(
                "/wallets/" + walletId + "/transactions?size=3",
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("\"size\":3");
        String token = extractNextToken(first.getBody());
        assertThat(token).isNotNull();

        ResponseEntity<String> second = rest.exchange(
                "/wallets/" + walletId + "/transactions?size=3&nextToken=" + token,
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(second.getBody()).contains("\"size\":2");
        assertThat(second.getBody()).contains("\"nextToken\":null");
    }

    // Test 36: size > 50 is silently capped to 50
    @Test
    void getTransactions_sizeCapped() {
        String walletId = createWallet("cust-001");
        insertTransactions(walletId, 55);

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/transactions?size=100",
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"size\":50");
    }

    // Test 37: malformed nextToken → 400
    @Test
    void getTransactions_invalidNextToken_400() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/transactions?nextToken=not-valid-base64!!!",
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Test 38: wallet not found → 404
    @Test
    void getTransactions_walletNotFound_404() {
        ResponseEntity<String> response = rest.exchange(
                "/wallets/nonexistent/transactions",
                HttpMethod.GET,
                authEntity("cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Test 39: different JWT owner → 403
    @Test
    void getTransactions_wrongOwner_403() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/transactions",
                HttpMethod.GET,
                authEntity("cust-002"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Service-token access (both callers) ───────────────────────────────────

    // Test 39a: service token can read any wallet's balance (no ownership check)
    @Test
    void getBalance_serviceToken_200() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("200"));

        ResponseEntity<BalanceResponse> response = rest.exchange(
                "/wallets/" + walletId + "/balance",
                HttpMethod.GET,
                serviceAuthEntity("order-service"),
                BalanceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo(new BigDecimal("200"));
    }

    // Test 39b: service token can read any wallet's transactions (no ownership check)
    @Test
    void getTransactions_serviceToken_200() {
        String walletId = createWallet("cust-001");
        topUp(walletId, "cust-001", new BigDecimal("100"));

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/transactions",
                HttpMethod.GET,
                serviceAuthEntity("order-service"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"size\":1");
        assertThat(response.getBody()).contains("\"nextToken\":null");
    }

    // Test 39c: service token calling POST /wallets → 403 (USER role required)
    @Test
    void createWallet_serviceToken_403() {
        ResponseEntity<String> response = rest.exchange(
                "/wallets", HttpMethod.POST,
                serviceJsonEntity(null, "order-service"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // Test 39d: service token calling POST /topup → 403 (USER role required)
    @Test
    void topUp_serviceToken_403() {
        String walletId = createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                serviceJsonEntity(Map.of("amount", new BigDecimal("100")), "order-service"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Extracts the raw (URL-safe) nextToken string from a JSON response body, or null if absent/null. */
    private String extractNextToken(String json) {
        int idx = json.indexOf("\"nextToken\":");
        if (idx == -1) return null;
        String after = json.substring(idx + 12).trim();
        if (after.startsWith("null")) return null;
        // value is a quoted string
        int start = after.indexOf('"') + 1;
        int end = after.indexOf('"', start);
        return after.substring(start, end);
    }

    private void insertTransactions(String walletId, int count) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        for (int i = 0; i < count; i++) {
            WalletTransaction txn = new WalletTransaction();
            txn.setWallet(wallet);
            txn.setType("TOPUP");
            txn.setAmount(new BigDecimal("10"));
            txn.setBalanceBefore(BigDecimal.ZERO);
            txn.setBalanceAfter(new BigDecimal("10"));
            txn.setStatus("SUCCESS");
            txn.setCreatedBy("test");
            walletTransactionRepository.save(txn);
        }
    }
}
