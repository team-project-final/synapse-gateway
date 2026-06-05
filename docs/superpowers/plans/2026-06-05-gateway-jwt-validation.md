# Gateway JWT 엣지 검증 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `synapse-gateway`에서 platform 발급 RS256 JWT를 엣지에서 검증하고(서명·만료·issuer·type=ACCESS), 통과 시 신원 헤더(`X-User-Id`/`X-User-Roles`)를 다운스트림에 전파하며 클라이언트 위조 헤더를 제거한다.

**Architecture:** Spring Cloud Gateway(WebFlux) + Spring Security 7 리액티브 리소스서버. `NimbusReactiveJwtDecoder`(정적 RSA 공개키, PEM 우선·base64 폴백) + 커스텀 `OAuth2TokenValidator`(issuer·timestamp·type) 로 토큰을 검증하고, `GlobalFilter`로 신원 헤더를 주입한다. 다운스트림 서비스의 자체 JWT 검증은 유지(이중 방어).

**Tech Stack:** Java 21, Spring Boot 4.0.6, Spring Cloud Gateway 2025.1.1(server-webflux), Spring Security 7(reactive), Nimbus JOSE(테스트 토큰 생성), JUnit 5 + Reactor StepVerifier + WebTestClient.

**Spec:** `docs/superpowers/specs/2026-06-05-gateway-jwt-validation-design.md`

**Branch:** `feat/gateway-jwt-validation` (이미 origin/main 기준 생성됨)

---

## File Structure

| 파일 | 책임 |
|------|------|
| `build.gradle.kts` (수정) | security + oauth2-resource-server + spring-security-test 의존성 추가 |
| `security/GatewayJwtProperties.java` (신규) | `gateway.jwt.*` 바인딩(공개키·issuer·공개경로). 기본 공개경로 목록 보유 |
| `security/RsaPublicKeyParser.java` (신규) | PEM/base64 X.509 공개키 파싱 + fail-fast |
| `config/JwtDecoderConfig.java` (신규) | `ReactiveJwtDecoder` 빈 + issuer/timestamp/type 검증기 결합 |
| `config/SecurityConfig.java` (신규) | `SecurityWebFilterChain` + 401 JSON 진입점 |
| `filter/IdentityForwardingFilter.java` (신규) | `GlobalFilter` — 위조 헤더 제거 + 신원 헤더 주입 |
| `resources/application.yml` (수정) | `gateway.jwt.public-key`/`issuer` env 매핑 |
| `test/.../security/TestKeys.java` (신규) | 테스트용 RSA 키페어 + 서명 토큰 팩토리 |
| `test/.../security/RsaPublicKeyParserTest.java` (신규) | 파서 단위 테스트 |
| `test/.../security/GatewayJwtPropertiesTest.java` (신규) | 기본값 적용 테스트 |
| `test/.../config/JwtValidationTest.java` (신규) | 디코더+검증기 단위 테스트(StepVerifier) |
| `test/.../filter/IdentityForwardingFilterTest.java` (신규) | 헤더 주입/제거 단위 테스트 |
| `test/.../config/SecurityEntryPointTest.java` (신규) | 401 JSON 진입점 단위 테스트 |
| `test/.../security/GatewaySecurityIntegrationTest.java` (신규) | WebTestClient 엔드투엔드 |
| `test/.../SynapseGatewayApplicationTests.java` (수정) | fail-fast 디코더용 테스트 키 주입 |

> 모든 신규 파일은 base package `com.synapse.gateway` 하위. 실제 경로 예: `src/main/java/com/synapse/gateway/security/RsaPublicKeyParser.java`.

빌드/테스트 명령은 `synapse-gateway` 디렉터리에서 실행. PowerShell은 `.\gradlew.bat`, bash는 `./gradlew`.

---

## Task 1: 의존성 추가

**Files:**
- Modify: `build.gradle.kts:28-33` (dependencies 블록)

- [ ] **Step 1: 의존성 추가**

`build.gradle.kts`의 `dependencies { ... }` 블록을 아래로 교체:

```kotlin
dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")
}
```

- [ ] **Step 2: 의존성 해석 확인**

Run: `./gradlew dependencies --configuration runtimeClasspath -q | Select-String "oauth2-resource-server|spring-security-web|nimbus-jose-jwt"`
(PowerShell. bash는 `./gradlew dependencies --configuration runtimeClasspath -q | grep -E "oauth2-resource-server|spring-security-web|nimbus-jose-jwt"`)
Expected: 세 항목 모두 출력됨 (nimbus-jose-jwt는 oauth2-jose 경유 transitive).

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava -q`
Expected: BUILD SUCCESSFUL (기존 코드 그대로 컴파일).

- [ ] **Step 4: 커밋**

```bash
git add build.gradle.kts
git commit -m "build(gateway): spring-security + oauth2-resource-server 의존성 추가"
```

---

## Task 2: RSA 공개키 파서 + 테스트 키 헬퍼

**Files:**
- Create: `src/test/java/com/synapse/gateway/security/TestKeys.java`
- Create: `src/main/java/com/synapse/gateway/security/RsaPublicKeyParser.java`
- Test: `src/test/java/com/synapse/gateway/security/RsaPublicKeyParserTest.java`

- [ ] **Step 1: 테스트 키 헬퍼 작성**

`src/test/java/com/synapse/gateway/security/TestKeys.java`:

```java
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
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
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
```

- [ ] **Step 2: 실패하는 파서 테스트 작성**

`src/test/java/com/synapse/gateway/security/RsaPublicKeyParserTest.java`:

```java
package com.synapse.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;

class RsaPublicKeyParserTest {

    @Test
    void parsesPemEncodedKey() {
        RSAPublicKey parsed = RsaPublicKeyParser.parse(TestKeys.publicKeyPem());
        assertThat(parsed.getEncoded()).isEqualTo(TestKeys.publicKey().getEncoded());
    }

    @Test
    void parsesBase64DerKey() {
        RSAPublicKey parsed = RsaPublicKeyParser.parse(TestKeys.publicKeyBase64());
        assertThat(parsed.getEncoded()).isEqualTo(TestKeys.publicKey().getEncoded());
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> RsaPublicKeyParser.parse("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsGarbageKey() {
        assertThatThrownBy(() -> RsaPublicKeyParser.parse("not-a-valid-key"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests "com.synapse.gateway.security.RsaPublicKeyParserTest" -q`
Expected: 컴파일 실패 — `RsaPublicKeyParser` 심볼 없음.

- [ ] **Step 4: 파서 구현**

`src/main/java/com/synapse/gateway/security/RsaPublicKeyParser.java`:

```java
package com.synapse.gateway.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 공개키를 PEM(-----BEGIN PUBLIC KEY-----, X.509 SubjectPublicKeyInfo) 또는
 * base64 DER 문자열에서 파싱한다. 둘 다 실패하면 IllegalStateException으로 fail-fast.
 */
public final class RsaPublicKeyParser {

    private RsaPublicKeyParser() {
    }

    public static RSAPublicKey parse(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("gateway.jwt.public-key 가 설정되지 않았습니다");
        }
        String normalized = key
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception exception) {
            throw new IllegalStateException("gateway.jwt.public-key 가 유효한 RSA 공개키가 아닙니다", exception);
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.synapse.gateway.security.RsaPublicKeyParserTest" -q`
Expected: BUILD SUCCESSFUL, 4개 테스트 통과.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/synapse/gateway/security/RsaPublicKeyParser.java src/test/java/com/synapse/gateway/security/TestKeys.java src/test/java/com/synapse/gateway/security/RsaPublicKeyParserTest.java
git commit -m "feat(gateway): RSA 공개키 PEM/base64 파서 + 테스트 키 헬퍼"
```

---

## Task 3: 설정 프로퍼티 (gateway.jwt.*)

**Files:**
- Create: `src/main/java/com/synapse/gateway/security/GatewayJwtProperties.java`
- Test: `src/test/java/com/synapse/gateway/security/GatewayJwtPropertiesTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/synapse/gateway/security/GatewayJwtPropertiesTest.java`:

```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.synapse.gateway.security.GatewayJwtPropertiesTest" -q`
Expected: 컴파일 실패 — `GatewayJwtProperties` 심볼 없음.

- [ ] **Step 3: 프로퍼티 구현**

`src/main/java/com/synapse/gateway/security/GatewayJwtProperties.java`:

```java
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
```

> 공개경로 기본값은 여기(Java)에만 둔다(DRY) — application.yml은 중복 정의하지 않는다.
> `/api/platform/...` prefix는 Task 7 통합 테스트와 Task 8 로컬 검증에서 실제 라우팅으로 확인하고,
> 다르면 `gateway.jwt.public-paths`로 오버라이드한다(코드 변경 불필요).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.synapse.gateway.security.GatewayJwtPropertiesTest" -q`
Expected: BUILD SUCCESSFUL, 3개 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/synapse/gateway/security/GatewayJwtProperties.java src/test/java/com/synapse/gateway/security/GatewayJwtPropertiesTest.java
git commit -m "feat(gateway): gateway.jwt 설정 프로퍼티 + 공개경로 기본값"
```

---

## Task 4: JWT 디코더 + 검증기 (서명·만료·issuer·type)

**Files:**
- Create: `src/main/java/com/synapse/gateway/config/JwtDecoderConfig.java`
- Test: `src/test/java/com/synapse/gateway/config/JwtValidationTest.java`

- [ ] **Step 1: 실패하는 검증 테스트 작성**

`src/test/java/com/synapse/gateway/config/JwtValidationTest.java`:

```java
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
                TestKeys.ISSUER, Instant.now().minusSeconds(60));
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.synapse.gateway.config.JwtValidationTest" -q`
Expected: 컴파일 실패 — `JwtDecoderConfig` 심볼 없음.

- [ ] **Step 3: 디코더 설정 구현**

`src/main/java/com/synapse/gateway/config/JwtDecoderConfig.java`:

```java
package com.synapse.gateway.config;

import com.synapse.gateway.security.GatewayJwtProperties;
import com.synapse.gateway.security.RsaPublicKeyParser;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@Configuration
public class JwtDecoderConfig {

    @Bean
    public ReactiveJwtDecoder jwtDecoder(GatewayJwtProperties properties) {
        RSAPublicKey publicKey = RsaPublicKeyParser.parse(properties.publicKey());
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<String>("type", "ACCESS"::equals));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
```

> `JwtClaimValidator<String>("type", "ACCESS"::equals)` — `type` 클레임이 `ACCESS`가 아니거나
> 누락이면(null) 검증 실패. REFRESH 토큰의 API 접근을 차단한다.
> `setJwtValidator`는 기본 검증기를 대체하므로 `JwtTimestampValidator`(만료)를 명시적으로 포함한다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.synapse.gateway.config.JwtValidationTest" -q`
Expected: BUILD SUCCESSFUL, 4개 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/synapse/gateway/config/JwtDecoderConfig.java src/test/java/com/synapse/gateway/config/JwtValidationTest.java
git commit -m "feat(gateway): ReactiveJwtDecoder + issuer/timestamp/type 검증기"
```

---

## Task 5: 신원 전파 필터 (GlobalFilter)

**Files:**
- Create: `src/main/java/com/synapse/gateway/filter/IdentityForwardingFilter.java`
- Test: `src/test/java/com/synapse/gateway/filter/IdentityForwardingFilterTest.java`

- [ ] **Step 1: 실패하는 필터 테스트 작성**

`src/test/java/com/synapse/gateway/filter/IdentityForwardingFilterTest.java`:

```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.synapse.gateway.filter.IdentityForwardingFilterTest" -q`
Expected: 컴파일 실패 — `IdentityForwardingFilter` 심볼 없음.

- [ ] **Step 3: 필터 구현**

`src/main/java/com/synapse/gateway/filter/IdentityForwardingFilter.java`:

```java
package com.synapse.gateway.filter;

import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 인증된 JWT의 신원을 다운스트림 헤더로 전파한다.
 * - 인입 X-User-* 헤더는 항상 제거(클라이언트 스푸핑 방지).
 * - 인증 컨텍스트가 있으면 X-User-Id(sub) / X-User-Roles(roles 콤마조인) 주입.
 * 라우팅(프록시) 이전에 실행되도록 높은 우선순위로 정렬.
 */
@Component
public class IdentityForwardingFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_ROLES_HEADER);
                })
                .build();
        ServerWebExchange sanitized = exchange.mutate().request(sanitizedRequest).build();

        return sanitized.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> withIdentityHeaders(sanitized, authentication.getToken()))
                .defaultIfEmpty(sanitized)
                .flatMap(chain::filter);
    }

    private ServerWebExchange withIdentityHeaders(ServerWebExchange exchange, Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        String rolesHeader = roles == null ? "" : String.join(",", roles);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(USER_ID_HEADER, jwt.getSubject());
                    headers.set(USER_ROLES_HEADER, rolesHeader);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10000;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.synapse.gateway.filter.IdentityForwardingFilterTest" -q`
Expected: BUILD SUCCESSFUL, 2개 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/synapse/gateway/filter/IdentityForwardingFilter.java src/test/java/com/synapse/gateway/filter/IdentityForwardingFilterTest.java
git commit -m "feat(gateway): 신원 헤더 전파 GlobalFilter + 위조 헤더 제거"
```

---

## Task 6: 보안 필터 체인 + 401 진입점

**Files:**
- Create: `src/main/java/com/synapse/gateway/config/SecurityConfig.java`
- Test: `src/test/java/com/synapse/gateway/config/SecurityEntryPointTest.java`

- [ ] **Step 1: 실패하는 진입점 테스트 작성**

`src/test/java/com/synapse/gateway/config/SecurityEntryPointTest.java`:

```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.synapse.gateway.config.SecurityEntryPointTest" -q`
Expected: 컴파일 실패 — `SecurityConfig` 심볼 없음.

- [ ] **Step 3: 보안 설정 구현**

`src/main/java/com/synapse/gateway/config/SecurityConfig.java`:

```java
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
                        .pathMatchers(publicPaths).permitAll()
                        .anyExchange().authenticated())
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
```

> 기존 `CorsConfig`의 `CorsWebFilter` 빈은 유지된다. 프리플라이트(OPTIONS)는 `permitAll`로 허용.
> `.jwt(Customizer.withDefaults())`는 컨텍스트의 `ReactiveJwtDecoder` 빈(Task 4)을 자동 사용.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.synapse.gateway.config.SecurityEntryPointTest" -q`
Expected: BUILD SUCCESSFUL, 1개 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/synapse/gateway/config/SecurityConfig.java src/test/java/com/synapse/gateway/config/SecurityEntryPointTest.java
git commit -m "feat(gateway): SecurityWebFilterChain + 401 JSON 진입점"
```

---

## Task 7: 설정 배선 + 통합 테스트

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/synapse/gateway/SynapseGatewayApplicationTests.java`
- Create: `src/test/java/com/synapse/gateway/security/GatewaySecurityIntegrationTest.java`

- [ ] **Step 1: application.yml에 gateway.jwt 매핑 추가**

`src/main/resources/application.yml` 끝에 추가(기존 내용 유지):

```yaml
gateway:
  jwt:
    # 운영 공개키는 gateway-secret 의 JWT_PUBLIC_KEY(PEM, gitops PR #128)로 주입.
    # 미설정 시 디코더 빈이 기동 단계에서 fail-fast (의도된 동작).
    public-key: ${JWT_PUBLIC_KEY:}
    # platform synapse.jwt.issuer 와 동일해야 함 (application-{dev,staging,prod}.yml).
    issuer: ${JWT_ISSUER:synapse-auth}
    # public-paths 기본값은 GatewayJwtProperties.DEFAULT_PUBLIC_PATHS. 필요 시 여기서 오버라이드.
```

- [ ] **Step 2: 기존 컨텍스트 로드 테스트에 테스트 키 주입**

`src/test/java/com/synapse/gateway/SynapseGatewayApplicationTests.java` 전체 교체:

```java
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
```

- [ ] **Step 3: 통합 테스트 작성**

`src/test/java/com/synapse/gateway/security/GatewaySecurityIntegrationTest.java`:

```java
package com.synapse.gateway.security;

import static org.hamcrest.Matchers.not;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityIntegrationTest {

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.jwt.public-key", TestKeys::publicKeyPem);
        registry.add("gateway.jwt.issuer", () -> TestKeys.ISSUER);
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
                TestKeys.ISSUER, Instant.now().minusSeconds(60));
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
        // 공개경로 → 인증 통과(401 아님). 업스트림 부재로 5xx가 되지만 인증 결정만 검증.
        client.post().uri("/api/platform/api/v1/auth/login")
                .exchange()
                .expectStatus().value(not(401));
    }

    @Test
    void validAccessTokenPassesAuthentication() {
        String token = TestKeys.accessToken("u", List.of("USER"));
        client.get().uri("/api/platform/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().value(not(401));
    }
}
```

> `publicLoginPathBypassesAuthentication`/`validAccessTokenPassesAuthentication`은 인증 통과 후
> 업스트림(localhost:1, 연결거부) 또는 레이트리밋(Redis 부재)으로 5xx가 되지만, 단언은 "401이 아님"만
> 확인하므로 인프라 부재에 강건하다. 실제 업스트림 라우팅은 Task 8 로컬 검증에서 확인.

- [ ] **Step 4: 통합 테스트 통과 확인**

Run: `./gradlew test --tests "com.synapse.gateway.security.GatewaySecurityIntegrationTest" --tests "com.synapse.gateway.SynapseGatewayApplicationTests" -q`
Expected: BUILD SUCCESSFUL. 컨텍스트 로드 1 + 통합 6 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/application.yml src/test/java/com/synapse/gateway/SynapseGatewayApplicationTests.java src/test/java/com/synapse/gateway/security/GatewaySecurityIntegrationTest.java
git commit -m "feat(gateway): gateway.jwt 배선 + 보안 통합 테스트"
```

---

## Task 8: 전체 검증 + 로컬 엔드투엔드 + 마무리

**Files:** (코드 변경 없음 — 검증 단계)

- [ ] **Step 1: 전체 테스트 실행**

Run: `./gradlew clean test -q`
Expected: BUILD SUCCESSFUL. 전체 테스트 통과(RsaPublicKeyParser 4 + GatewayJwtProperties 3 + JwtValidation 4 + IdentityForwardingFilter 2 + SecurityEntryPoint 1 + GatewaySecurityIntegration 6 + contextLoads 1).

- [ ] **Step 2: 빌드(부트 jar) 확인**

Run: `./gradlew build -q`
Expected: BUILD SUCCESSFUL (테스트 포함).

- [ ] **Step 3: 로컬 엔드투엔드 검증 (수동, minikube)**

전제: minikube에 gitops `local-k8s`(PR #128 포함 — gateway-secret의 `JWT_PUBLIC_KEY`)가 배포되어 있고,
gateway 이미지가 이 브랜치로 재빌드/재적재되어 있어야 함. (이미지 재빌드/적재 절차는 별도 확인 — minikube
동일 :local 태그 재적재 시 stale 이미지 주의.) 또한 gateway 디플로이먼트에 `JWT_ISSUER` 주입 필요
(미설정 시 기본 `synapse-auth`).

검증 시나리오 (gateway 포트포워딩 또는 ingress 경유):
1. 무토큰 보호경로 → 401 `{"error":"unauthorized"}`:
   `curl -i http://<gateway>/api/platform/api/v1/users/me`
2. login(공개경로) → 200 + 토큰 수신:
   `curl -i -X POST http://<gateway>/api/platform/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"...","password":"..."}'`
3. 2의 액세스 토큰으로 보호경로 → 200 (platform까지 도달):
   `curl -i http://<gateway>/api/platform/api/v1/users/me -H "Authorization: Bearer <token>"`
4. platform 수신 헤더에 `X-User-Id`/`X-User-Roles`가 있고 클라이언트가 보낸 `X-User-Id: spoof`는 덮어써짐 확인
   (platform 로그 또는 에코 수단).

Expected: 1=401, 2=200(토큰), 3=200, 4=주입/제거 확인.
공개경로 prefix(`/api/platform/...`)가 실제와 다르면 `gateway.jwt.public-paths`를 조정(코드 변경 없이 config/env).

- [ ] **Step 4: 스펙 대비 자체 점검**

- 서명/만료/issuer/type=ACCESS 검증 → Task 4 ✓
- PEM 우선 + base64 폴백 + fail-fast → Task 2 ✓
- 신원 헤더 전파 + 스푸핑 제거 → Task 5 ✓
- 공개경로 allowlist 외부화 → Task 3 ✓
- 401 일관 JSON → Task 6 ✓
- 이중 방어(다운스트림 무변경) → 전체 ✓

- [ ] **Step 5: push/PR (사용자 확인 후)**

> svc 레포 push/PR은 사용자 확인 필수(프로젝트 규칙). 확인 전까지 로컬 커밋만 유지.
확인 받으면:

```bash
git push -u origin feat/gateway-jwt-validation
gh pr create --repo team-project-final/synapse-gateway --base main \
  --title "feat(gateway): JWT 엣지 검증 + 신원 헤더 전파" \
  --body "platform RS256 토큰을 게이트웨이에서 검증(서명·exp·iss·type=ACCESS), 유효 시 X-User-Id/X-User-Roles 전파 + 위조 헤더 제거. 다운스트림 자체 검증 유지(이중 방어). 공개키 PEM/base64 폴백 + fail-fast. Spec: docs/superpowers/specs/2026-06-05-gateway-jwt-validation-design.md"
```

---

## 참고: 배포 전제 (gitops 후속, 별도 확인)

- `gateway-secret`의 `JWT_PUBLIC_KEY`는 PR #128로 이미 존재(로컬). dev/staging/prod 오버레이에도 동일 키 주입 필요 여부 확인.
- gateway 디플로이먼트 env에 `JWT_ISSUER`(=platform issuer) 주입 권장. 미설정 시 기본 `synapse-auth`.
- 이 변경 후 gateway는 `JWT_PUBLIC_KEY` 없으면 기동 실패(fail-fast) — 배포 파이프라인에서 시크릿 주입 보장 필요.
