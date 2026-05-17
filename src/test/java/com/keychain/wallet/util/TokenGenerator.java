package com.keychain.wallet.util;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Properties;

/**
 * Standalone utility to generate a signed JWT for testing.
 * Run: ./mvnw test-compile exec:java \
 *        -Dexec.mainClass=com.keychain.wallet.util.TokenGenerator \
 *        -Dexec.args="<subject>" \
 *        -Dexec.classpathScope=test
 */
public class TokenGenerator {

    public static void main(String[] args) throws Exception {
        String subject = args.length > 0 ? args[0] : "test-user";
        String secret = loadSecret();

        MACSigner signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(subject)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plus(24, ChronoUnit.HOURS)))
            .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(signer);

        System.out.println(jwt.serialize());
    }

    private static String loadSecret() throws IOException {
        Properties props = new Properties();
        try (InputStream is = TokenGenerator.class
                .getClassLoader()
                .getResourceAsStream("application.properties")) {
            props.load(is);
        }
        return props.getProperty("jwt.secret");
    }
}
