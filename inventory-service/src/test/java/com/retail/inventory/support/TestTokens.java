package com.retail.inventory.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Firma tokens JWT HS256 para las pruebas, incluidos los inválidos (otro secreto, otro emisor, expirados).
 */
public final class TestTokens {

    /** Secreto de las pruebas (al menos 32 bytes). Se pasa como {@code inventory.jwt.secret}. */
    public static final String SECRET = "integration-test-secret-of-32-bytes!";
    public static final String ISSUER = "retail-demo-auth";

    private TestTokens() {
    }

    /** Token válido con los permisos indicados (separados por espacio; vacío para ninguno). */
    public static String withScope(String scope) {
        return sign(SECRET, ISSUER, scope, Instant.now().plus(1, ChronoUnit.HOURS));
    }

    public static String sign(String secret, String issuer, String scope, Instant expiresAt) {
        Instant issuedAt = expiresAt.minus(2, ChronoUnit.HOURS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("test-client")
                .claim("scope", scope)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            token.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not sign test token", exception);
        }
        return token.serialize();
    }
}
