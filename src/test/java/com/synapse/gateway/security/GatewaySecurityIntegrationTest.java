package com.synapse.gateway.security;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityIntegrationTest {

    @Value("${local.server.port}")
    int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.jwt.public-key", TestKeys::publicKeyPem);
        registry.add("gateway.jwt.issuer", () -> TestKeys.ISSUER);
        // 테스트 환경엔 Redis가 없으므로 health indicator 비활성화(health=UP 유지).
        registry.add("management.health.redis.enabled", () -> "false");
        // 업스트림은 즉시 연결거부되는 주소로 고정 (인증 통과 후 다운스트림 라우팅 분리).
        registry.add("PLATFORM_SVC_URI", () -> "http://localhost:1");
    }

    @Test
    void publicHealthEndpointIsAccessible() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void protectedRouteWithoutTokenReturnsUnauthorized() {
        client.get().uri("/api/platform/api/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("unauthorized");
    }

    @Test
    void expiredTokenReturnsUnauthorized() {
        String expired = TestKeys.token("u", List.of("USER"), "ACCESS",
                TestKeys.ISSUER, Instant.now().minusSeconds(600));
        client.get().uri("/api/platform/api/v1/users/me")
                .header("Authorization", "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void refreshTokenReturnsUnauthorizedOnApiRoute() {
        String refresh = TestKeys.token("u", List.of("USER"), "REFRESH",
                TestKeys.ISSUER, Instant.now().plusSeconds(900));
        client.get().uri("/api/platform/api/v1/users/me")
                .header("Authorization", "Bearer " + refresh)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void publicLoginPathBypassesAuthentication() {
        // 공개경로 → 인증 통과. 업스트림/레이트리밋 부재로 5xx (401이 아님 = 인증 통과 증명).
        client.post().uri("/api/platform/api/v1/auth/login")
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void validAccessTokenPassesAuthentication() {
        String token = TestKeys.accessToken("u", List.of("USER"));
        client.get().uri("/api/platform/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().is5xxServerError();
    }
}
