package com.synapse.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayJwtPropertiesTest {

    @Test
    void appliesDefaultIssuerWhenNull() {
        GatewayJwtProperties properties = new GatewayJwtProperties("key", null, null);
        assertThat(properties.issuer()).isEqualTo("synapse-auth");
    }

    @Test
    void appliesDefaultPublicPathsWhenNull() {
        GatewayJwtProperties properties = new GatewayJwtProperties("key", "synapse-auth", null);
        assertThat(properties.publicPaths())
                .contains("/actuator/**", "/api/platform/api/v1/auth/login");
    }

    @Test
    void keepsExplicitValues() {
        GatewayJwtProperties properties =
                new GatewayJwtProperties("key", "custom-iss", List.of("/x/**"));
        assertThat(properties.issuer()).isEqualTo("custom-iss");
        assertThat(properties.publicPaths()).containsExactly("/x/**");
    }
}
