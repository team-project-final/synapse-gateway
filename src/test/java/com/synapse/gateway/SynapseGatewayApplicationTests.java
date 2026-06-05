package com.synapse.gateway;

import com.synapse.gateway.security.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class SynapseGatewayApplicationTests {

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.jwt.public-key", TestKeys::publicKeyPem);
        registry.add("gateway.jwt.issuer", () -> TestKeys.ISSUER);
    }

    @Test
    void contextLoads() {
    }
}
