package com.synapse.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoutesConfig {

    @Value("${PLATFORM_SVC_URI:http://platform-svc:8080}")
    private String platformUri;

    @Value("${ENGAGEMENT_SVC_URI:http://engagement-svc:8080}")
    private String engagementUri;

    @Value("${KNOWLEDGE_SVC_URI:http://knowledge-svc:8080}")
    private String knowledgeUri;

    @Value("${LEARNING_SVC_URI:http://learning-card-svc:8080}")
    private String learningUri;

    @Bean
    public RedisRateLimiter redisRateLimiter() {
        // replenishRate=1/sec, burstCapacity=60, requestedTokens=1
        return new RedisRateLimiter(1, 60, 1);
    }

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder,
                                           RedisRateLimiter rateLimiter) {
        return builder.routes()
            .route("platform-svc", r -> r
                .path("/api/platform/**")
                .filters(f -> f
                    .stripPrefix(2)
                    .requestRateLimiter(c -> c.setRateLimiter(rateLimiter)))
                .uri(platformUri))
            .route("engagement-svc", r -> r
                .path("/api/engagement/**")
                .filters(f -> f
                    .stripPrefix(2)
                    .requestRateLimiter(c -> c.setRateLimiter(rateLimiter)))
                .uri(engagementUri))
            .route("knowledge-svc", r -> r
                .path("/api/knowledge/**")
                .filters(f -> f
                    .stripPrefix(2)
                    .requestRateLimiter(c -> c.setRateLimiter(rateLimiter)))
                .uri(knowledgeUri))
            .route("learning-svc", r -> r
                .path("/api/learning/**")
                .filters(f -> f
                    .stripPrefix(2)
                    .requestRateLimiter(c -> c.setRateLimiter(rateLimiter)))
                .uri(learningUri))
            .build();
    }
}
