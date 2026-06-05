package com.synapse.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapse.gateway.security.GatewayJwtProperties;
import com.synapse.gateway.security.TestKeys;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.test.StepVerifier;

class JwtValidationTest {

    private final ReactiveJwtDecoder decoder = new JwtDecoderConfig().jwtDecoder(
            new GatewayJwtProperties(TestKeys.publicKeyPem(), TestKeys.ISSUER, null));

    @Test
    void acceptsValidAccessToken() {
        String token = TestKeys.accessToken("user-1", List.of("USER"));
        StepVerifier.create(decoder.decode(token))
                .assertNext(jwt -> {
                    assertThat(jwt.getSubject()).isEqualTo("user-1");
                    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
                })
                .verifyComplete();
    }

    @Test
    void rejectsExpiredToken() {
        String token = TestKeys.token("user-1", List.of("USER"), "ACCESS",
                TestKeys.ISSUER, Instant.now().minusSeconds(600));
        StepVerifier.create(decoder.decode(token))
                .expectError(JwtValidationException.class)
                .verify();
    }

    @Test
    void rejectsWrongIssuer() {
        String token = TestKeys.token("user-1", List.of("USER"), "ACCESS",
                "evil-issuer", Instant.now().plusSeconds(900));
        StepVerifier.create(decoder.decode(token))
                .expectError(JwtValidationException.class)
                .verify();
    }

    @Test
    void rejectsRefreshToken() {
        String token = TestKeys.token("user-1", List.of("USER"), "REFRESH",
                TestKeys.ISSUER, Instant.now().plusSeconds(900));
        StepVerifier.create(decoder.decode(token))
                .expectError(JwtValidationException.class)
                .verify();
    }
}
