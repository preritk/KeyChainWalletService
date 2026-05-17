package com.keychain.wallet;

import com.keychain.wallet.dto.response.WalletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WalletCreateApiTest extends AbstractIntegrationTest {

    // Test 1: happy path
    @Test
    void createWallet_success() {
        ResponseEntity<WalletResponse> response = rest.exchange(
                "/wallets", HttpMethod.POST,
                jsonEntity(null, "cust-001"),
                WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WalletResponse body = response.getBody();
        assertThat(body.id()).isNotBlank();
        assertThat(body.customerId()).isEqualTo("cust-001");
        assertThat(body.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.currency()).isEqualTo("INR");
        assertThat(body.status()).isEqualTo("ACTIVE");
    }

    // Test 2: duplicate wallet for same customer → 409
    @Test
    void createWallet_duplicateCustomer_409() {
        createWallet("cust-001");

        ResponseEntity<String> response = rest.exchange(
                "/wallets", HttpMethod.POST,
                jsonEntity(null, "cust-001"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("cust-001");
    }

    // Test 3: no auth → 401
    @Test
    void createWallet_noAuth_401() {
        ResponseEntity<String> response = rest.exchange(
                "/wallets", HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
