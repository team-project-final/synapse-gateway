package com.synapse.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class IdentityForwardingFilterTest {

    private final IdentityForwardingFilter filter = new IdentityForwardingFilter();

    @Test
    void injectsIdentityHeadersFromJwtAndStripsSpoofed() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/api/v1/users/me")
                .header("X-User-Id", "spoofed-id")
                .header("X-User-Roles", "SUPERADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("user-123")
                .claim("roles", List.of("USER", "ADMIN"))
                .build();
        ServerWebExchange authenticated = exchange.mutate()
                .principal(Mono.just(new JwtAuthenticationToken(jwt)))
                .build();

        CapturingChain chain = new CapturingChain();
        filter.filter(authenticated, chain).block();

        HttpHeaders forwarded = chain.captured.getRequest().getHeaders();
        assertThat(forwarded.getFirst("X-User-Id")).isEqualTo("user-123");
        assertThat(forwarded.getFirst("X-User-Roles")).isEqualTo("USER,ADMIN");
    }

    @Test
    void stripsSpoofedHeadersWhenUnauthenticated() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/api/v1/auth/login")
                .header("X-User-Id", "spoofed-id")
                .header("X-User-Roles", "ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        HttpHeaders forwarded = chain.captured.getRequest().getHeaders();
        assertThat(forwarded.getFirst("X-User-Id")).isNull();
        assertThat(forwarded.getFirst("X-User-Roles")).isNull();
    }

    static final class CapturingChain implements GatewayFilterChain {
        private ServerWebExchange captured;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.captured = exchange;
            return Mono.empty();
        }
    }
}
