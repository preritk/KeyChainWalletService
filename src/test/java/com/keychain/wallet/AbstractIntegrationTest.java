package com.keychain.wallet;

import com.keychain.wallet.dto.response.DeductResponse;
import com.keychain.wallet.dto.response.TopUpResponse;
import com.keychain.wallet.dto.response.WalletResponse;
import com.keychain.wallet.repository.IdempotencyRecordRepository;
import com.keychain.wallet.repository.WalletRepository;
import com.keychain.wallet.repository.WalletTransactionRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    // Started once per JVM run; Ryuk cleans it up on JVM exit.
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15")
                    .withDatabaseName("walletdb_test")
                    .withUsername("wallet_user")
                    .withPassword("wallet_pass")
                    .withInitScript("db/init.sql");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("jwt.secret", () -> "keychain-wallet-service-super-secret-key-minimum-32-chars-hs256");
    }

    @LocalServerPort
    private int port;

    protected RestTemplate rest;

    @Autowired
    protected WalletRepository walletRepository;

    @Autowired
    protected WalletTransactionRepository walletTransactionRepository;

    @Autowired
    protected IdempotencyRecordRepository idempotencyRecordRepository;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void setup() {
        rest = new RestTemplate();
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory("http://localhost:" + port);
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        rest.setUriTemplateHandler(factory);
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        idempotencyRecordRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletRepository.deleteAll();
    }

    private String token(String subject, String role) {
        try {
            MACSigner signer = new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8));
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("roles", java.util.List.of(role))
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plus(24, ChronoUnit.HOURS)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test JWT", e);
        }
    }

    protected String userToken(String subject)    { return token(subject, "USER"); }
    protected String serviceToken(String subject) { return token(subject, "SERVICE"); }

    protected <T> HttpEntity<T> jsonEntity(T body, String jwtSubject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userToken(jwtSubject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    protected <T> HttpEntity<T> serviceJsonEntity(T body, String jwtSubject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(serviceToken(jwtSubject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    protected HttpEntity<Void> authEntity(String jwtSubject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userToken(jwtSubject));
        return new HttpEntity<>(headers);
    }

    protected HttpEntity<Void> serviceAuthEntity(String jwtSubject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(serviceToken(jwtSubject));
        return new HttpEntity<>(headers);
    }

    protected String createWallet(String customerId) {
        ResponseEntity<WalletResponse> response = rest.exchange(
                "/wallets", HttpMethod.POST,
                jsonEntity(null, customerId),
                WalletResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    protected TopUpResponse topUp(String walletId, String customerId, BigDecimal amount) {
        ResponseEntity<TopUpResponse> response = rest.exchange(
                "/wallets/" + walletId + "/topup",
                HttpMethod.POST,
                jsonEntity(Map.of("amount", amount), customerId),
                TopUpResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    protected ResponseEntity<DeductResponse> deduct(
            String walletId, String orderId,
            String customerId, BigDecimal amount, String callerSubject) {
        Map<String, Object> body = Map.of(
                "orderId", orderId,
                "customerId", customerId,
                "amount", amount);
        return rest.exchange(
                "/wallets/" + walletId + "/deduct",
                HttpMethod.POST,
                serviceJsonEntity(body, callerSubject),
                DeductResponse.class);
    }

    protected ResponseEntity<String> deductRaw(
            String walletId, Map<String, Object> body, String callerSubject) {
        return rest.exchange(
                "/wallets/" + walletId + "/deduct",
                HttpMethod.POST,
                serviceJsonEntity(body, callerSubject),
                String.class);
    }
}
