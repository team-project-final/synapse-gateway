package com.synapse.gateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 게이트웨이 JWT 검증 설정.
 * 공개경로 기본값은 platform-svc SecurityConfig 의 permitAll 과 정합 (게이트웨이 인입 경로 기준).
 */
@ConfigurationProperties(prefix = "gateway.jwt")
public record GatewayJwtProperties(String publicKey, String issuer, List<String> publicPaths) {

    public static final List<String> DEFAULT_PUBLIC_PATHS = List.of(
            "/actuator/**",
            "/api/platform/oauth2/**",
            "/api/platform/login/**",
            "/api/platform/api/v1/auth/login",
            "/api/platform/api/v1/auth/signup",
            "/api/platform/api/v1/auth/refresh",
            "/api/platform/api/v1/auth/callback",
            "/api/platform/api/v1/billing/webhooks");

    public GatewayJwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "synapse-auth";
        }
        if (publicPaths == null || publicPaths.isEmpty()) {
            publicPaths = DEFAULT_PUBLIC_PATHS;
        }
    }
}
