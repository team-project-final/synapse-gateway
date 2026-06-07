package com.synapse.gateway.config;

import com.synapse.gateway.security.GatewayJwtProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewayJwtProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            GatewayJwtProperties properties,
            ServerAuthenticationEntryPoint authenticationEntryPoint) {
        String[] publicPaths = properties.publicPaths().toArray(String[]::new);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 공개 경로(actuator·auth 엔드포인트 등)는 /api/** 인증 규칙보다 먼저 매칭.
                        .pathMatchers(publicPaths).permitAll()
                        // API는 인증 필수 (공개 경로 제외).
                        .pathMatchers("/api/**").authenticated()
                        // 그 외(SPA 셸·정적 자산·딥링크)는 공개 — gateway가 frontend로 프록시.
                        .anyExchange().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    public ServerAuthenticationEntryPoint jwtAuthenticationEntryPoint() {
        return (exchange, exception) -> {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        };
    }
}
