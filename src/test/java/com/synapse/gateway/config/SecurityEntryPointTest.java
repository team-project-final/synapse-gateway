package com.synapse.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import reactor.test.StepVerifier;

class SecurityEntryPointTest {

    private final ServerAuthenticationEntryPoint entryPoint =
            new SecurityConfig().jwtAuthenticationEntryPoint();

    @Test
    void writesUnauthorizedJson() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/platform/x"));

        entryPoint.commence(exchange, new BadCredentialsException("nope")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body).contains("\"error\":\"unauthorized\""))
                .verifyComplete();
    }
}
