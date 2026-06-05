# Gateway JWT 검증 (엣지 인증) 설계

- 날짜: 2026-06-05
- 대상 레포: `synapse-gateway`
- 상태: 승인됨 (구현 대기)

## 1. 배경 / 문제

`synapse-gateway`는 현재 라우팅(`RoutesConfig`) · 레이트리밋(`RateLimiterConfig`) · CORS(`CorsConfig`)만
수행하며 **JWT 검증을 전혀 하지 않는다**. `build.gradle.kts`에 spring-security / oauth2-resource-server /
jjwt 의존성이 없고, `JWT_PUBLIC_KEY`를 읽는 코드도 없다. 인증은 전적으로 다운스트림
`platform-svc`(`JwtTokenProvider` + `SecurityConfig`)에 위임되어 있다.

엣지(게이트웨이)에서 1차 인증을 수행하면:
- 무효 토큰 요청이 다운스트림에 도달하기 전 차단된다 (부하 · 공격면 감소).
- 검증된 신원을 헤더로 전파해 다운스트림이 일관된 신원 컨텍스트를 신뢰할 수 있다.
- 다운스트림의 자체 검증은 유지하여 **이중 방어(defense in depth)**를 이룬다.

gitops PR #128로 `gateway-secret`에 이미 `JWT_PUBLIC_KEY`(PEM)가 주입되어 있으나 앱이 소비하지 않는 상태다.

## 2. 토큰 계약 (platform-svc 발급 기준)

`platform-svc`의 `JwtTokenProvider`가 발급하는 액세스 토큰:

- 알고리즘: **RS256** (RSA)
- 헤더: `kid` (단일 키 운영 — 게이트웨이는 정보용으로만 취급, JWKS 미사용)
- 클레임:
  - `sub` — userId (UUID 문자열)
  - `iss` — issuer (`synapse.jwt.issuer`)
  - `iat`, `exp` — 발급/만료 (액세스 TTL 15분)
  - `roles` — `List<String>` (권한)
  - `type` — `ACCESS` | `REFRESH`

게이트웨이는 이 계약을 변경하지 않고 **검증만** 한다.

## 3. 아키텍처

Spring Cloud Gateway(WebFlux)에 **Spring Security 리액티브 리소스서버**를 결합한다.

```
Client ──Authorization: Bearer <JWT>──▶ Gateway
  [SecurityWebFilterChain]
    - 공개경로 allowlist 매칭 → permitAll 통과
    - 그 외 → ReactiveJwtDecoder 검증 (서명 · exp · iss · type=ACCESS)
        - 실패 → 401 unauthorized
        - 성공 → 인증 컨텍스트 확립
  [IdentityForwardingFilter (GlobalFilter)]
    - 인입 X-User-* 헤더 무조건 제거 (스푸핑 방지)
    - 인증 성공 시 X-User-Id, X-User-Roles 주입
  ──▶ RoutesConfig 라우팅 (stripPrefix(2))
  ──▶ platform / engagement / knowledge / learning-svc (자체 JWT 재검증 유지)
```

다운스트림 서비스의 자체 검증은 제거하지 않는다(이중 방어). 게이트웨이가 주입한 신원 헤더의
신뢰 여부는 각 서비스가 선택한다(기본은 자체 JWT 재검증).

## 4. 컴포넌트 (신규)

| 파일 | 책임 |
|------|------|
| `config/SecurityConfig.java` | `SecurityWebFilterChain` 빈 — CSRF/세션 비활성(STATELESS), 공개경로 `permitAll`, 그 외 `authenticated`, `oauth2ResourceServer().jwt()` 연결, 401 진입점(`ServerAuthenticationEntryPoint`) |
| `config/JwtDecoderConfig.java` | `ReactiveJwtDecoder` 빈 — RSA 공개키(PEM 우선, base64 DER 폴백) 로딩, `issuer` + `type=ACCESS` `OAuth2TokenValidator` 결합 |
| `security/GatewayJwtProperties.java` | `@ConfigurationProperties("gateway.jwt")` — `publicKey`, `issuer`, `publicPaths(List<String>)` 바인딩 |
| `security/RsaPublicKeyParser.java` | PEM/base64 양형식 파싱 유틸 (정적 메서드, 단위 테스트 대상) |
| `filter/IdentityForwardingFilter.java` | `GlobalFilter` (Order 높음) — 인입 `X-User-*` 제거 후, 인증 시 `X-User-Id`/`X-User-Roles` 주입 |

각 단위는 단일 책임을 가지며 독립적으로 테스트 가능하다.

## 5. 공개 경로 (기본 allowlist)

`platform-svc` `SecurityConfig`의 `permitAll`과 정합. **게이트웨이 인입 경로** 기준 패턴
(`SecurityWebFilterChain`은 `stripPrefix` 적용 *이전*의 인입 경로로 평가):

기본값 (`gateway.jwt.public-paths`, 설정으로 오버라이드 가능):
- `/actuator/**` — 게이트웨이 자체 헬스/인포
- `**/oauth2/**`
- `**/login/**`
- `**/api/v1/auth/login`
- `**/api/v1/auth/signup`
- `**/api/v1/auth/refresh`
- `**/api/v1/auth/callback`
- `**/api/v1/billing/webhooks`

매처는 `PathPatternParserServerWebExchangeMatcher` 기반. 정확한 prefix(`/api/platform/...`)는
실제 클라이언트 base path로 구현 단계에서 최종 검증한다(현 `RoutesConfig`는 `/api/{svc}/**`
+ `stripPrefix(2)`). allowlist를 설정 외부화하므로 다운스트림 공개 경로 추가 시 코드 변경 없이 대응.

## 6. 키 형식 처리 (PEM 우선 + base64 폴백)

`RsaPublicKeyParser.parse(String)`:
1. 입력에 `-----BEGIN PUBLIC KEY-----` 포함 → PEM 헤더/푸터/개행 제거 후 base64 디코딩 → X.509(`X509EncodedKeySpec`).
2. 아니면 base64 DER로 직접 시도.
3. 둘 다 실패 → `IllegalStateException`으로 **기동 시 fail-fast** (런타임 401 폭주 방지, platform #69와 동일 철학).

gitops PR #128이 `gateway-secret`에 PEM 멀티라인으로 주입하므로 1번 경로가 기본 동작.

## 7. 토큰 검증 규칙

`ReactiveJwtDecoder` + `DelegatingOAuth2TokenValidator`:
- 서명: RS256, 게이트웨이 공개키
- `exp` 만료 검증 (`JwtTimestampValidator`)
- `iss` == `gateway.jwt.issuer` (`JwtIssuerValidator`)
- `type` == `ACCESS` (커스텀 `OAuth2TokenValidator<Jwt>` — REFRESH 토큰의 API 접근 차단)
- 실패 시 일관된 `401` 응답: `{"error":"unauthorized"}` (게이트웨이 표준 `ErrorWebExceptionHandler`/진입점)

## 8. 신원 헤더 전파

`IdentityForwardingFilter` (`GlobalFilter`, 라우팅 이전 실행):
- **항상** 인입 요청의 `X-User-Id`, `X-User-Roles` 제거 (클라이언트 스푸핑 차단) — 공개 경로 포함 전 구간.
- 인증 컨텍스트 존재 시:
  - `X-User-Id` = `sub`
  - `X-User-Roles` = `roles` 콤마 조인 (예: `USER,ADMIN`)
- 다운스트림은 이 헤더를 신뢰하거나 자체 JWT 재검증(기본)을 선택.

## 9. 의존성 / 설정

`build.gradle.kts` 추가:
- `org.springframework.boot:spring-boot-starter-security`
- `org.springframework.boot:spring-boot-starter-oauth2-resource-server`

(기존 webflux/redis-reactive/actuator 스택과 호환. 리액티브 모듈 사용.)

`application.yml` 추가:
```yaml
gateway:
  jwt:
    public-key: ${JWT_PUBLIC_KEY:}
    issuer: ${JWT_ISSUER:synapse-auth}   # platform: application-{dev,staging,prod}.yml 의 synapse.jwt.issuer 와 동일
    public-paths:
      - /actuator/**
      - "**/oauth2/**"
      - "**/login/**"
      - "**/api/v1/auth/login"
      - "**/api/v1/auth/signup"
      - "**/api/v1/auth/refresh"
      - "**/api/v1/auth/callback"
      - "**/api/v1/billing/webhooks"
```

> issuer 값은 platform의 `synapse.jwt.issuer`와 동일해야 한다(현재 `synapse-auth` — platform
> `application-{dev,staging,prod}.yml`). `JWT_ISSUER`가 gateway config/secret에 없으면 기본값
> `synapse-auth`로 동작하며, 환경별 변경 시 gitops에서 `JWT_ISSUER` 주입 필요(별도 확인).

## 10. 테스트 전략

- `RsaPublicKeyParserTest` — PEM 로딩, base64 DER 로딩, 깨진 키 예외.
- `JwtDecoderConfigTest` — issuer 불일치 거부, 만료 거부, `type=REFRESH` 거부, 유효 ACCESS 통과.
- `SecurityConfigTest` (`WebTestClient`) — 공개경로 무토큰 200, 보호경로 무토큰 401, 위조/만료 401,
  REFRESH 토큰 401, 유효 ACCESS 200(라우팅은 모킹/스텁).
- `IdentityForwardingFilterTest` — 인증 시 `X-User-Id`/`X-User-Roles` 주입, 인입 위조 `X-User-Id` 제거.
- 통합: 테스트용 RSA 키페어로 토큰 생성 → 필터 체인 통과 검증.

## 11. 범위 밖 (YAGNI)

- JWKS 엔드포인트 / 키 로테이션 자동화 (단일 키 운영, 정적 공개키로 충분).
- 게이트웨이단 RBAC(경로별 role 인가) — 인가는 다운스트림 책임으로 유지.
- 토큰 폐기(블랙리스트/introspection) — 액세스 TTL 15분으로 수용.

## 12. 영향 / 마이그레이션

- 다운스트림 코드 변경 없음(이중 방어 유지).
- 배포 시 `gateway-secret`의 `JWT_PUBLIC_KEY`(PR #128 존재) + `JWT_ISSUER` 환경변수 필요.
- 잘못된/누락 키 → 기동 fail-fast로 즉시 가시화.
