package com.synapse.gateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/** JVM당 1회 생성되는 RSA 키페어로 테스트용 공개키(PEM/base64)와 서명 JWT를 제공한다. */
public final class TestKeys {

    public static final String ISSUER = "synapse-auth";
    private static final RSAKey RSA_KEY = generate();

    private TestKeys() {
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-kid").generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static RSAPublicKey publicKey() {
        try {
            return RSA_KEY.toRSAPublicKey();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey().getEncoded());
    }

    public static String publicKeyPem() {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(publicKey().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    public static String accessToken(String subject, List<String> roles) {
        return token(subject, roles, "ACCESS", ISSUER, Instant.now().plusSeconds(900));
    }

    public static String token(String subject, List<String> roles, String type, String issuer, Instant expiry) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .issueTime(Date.from(expiry.minusSeconds(900)))
                    .expirationTime(Date.from(expiry))
                    .claim("roles", roles)
                    .claim("type", type)
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-kid").build(),
                    claims);
            jwt.sign(new RSASSASigner(RSA_KEY));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
