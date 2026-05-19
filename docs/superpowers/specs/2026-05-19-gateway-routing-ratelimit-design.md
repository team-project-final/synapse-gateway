# Spring Cloud Gateway — 라우팅 + Rate Limit 설계

> **작성일**: 2026-05-19
> **대상**: @team-lead — TASK Step 6
> **레포**: synapse-gateway
> **연관**: synapse-shared docker-compose.yml (gateway stub → 실제 이미지 교체)

---

## 1. 목표

Spring Cloud Gateway에서 4개 서비스로의 path-prefix 기반 라우팅과 Redis 기반 Rate Limit(분당 60회/IP)을 설정한다.

### Done When (TASK 기준)
- Gateway → platform-svc 라우팅 동작
- Gateway → engagement-svc 라우팅 동작
- Gateway → knowledge-svc 라우팅 동작
- Gateway → learning-svc 라우팅 동작
- Rate Limit 설정 적용 (Redis 기반)
- Rate Limit 초과 시 429 응답 확인

### Out of Scope
- JWT 검증 필터 (향후 Step)
- Circuit Breaker (W3)
- WebSocket 프록시

## 2. 기술 스택

| 항목 | 버전 |
|------|------|
| Spring Boot | 4.0.6 |
| Spring Cloud | 2025.1.1 (Oakwood) |
| Spring Cloud Gateway | 5.0.1 (gateway-server-webflux) |
| Java | 21 (Temurin) |
| Redis | 7 (synapse-shared compose에서 제공) |

### 의존성 변경 사항 (vs 이전 계획)
- `spring-cloud-starter-gateway` → `spring-cloud-gateway-server-webflux` (5.0부터 artifact 이름 변경)
- Spring Cloud BOM: `2023.0.2` → `2025.1.1`
- Spring Cloud 2025.0.0은 Boot 4.0.1+와 비호환 → 반드시 2025.1.x 사용

## 3. 프로젝트 구조

```
synapse-gateway/
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
├── src/main/java/com/synapse/gateway/
│   ├── SynapseGatewayApplication.java
│   └── config/
│       └── RateLimiterConfig.java
├── src/main/resources/
│   └── application.yml
└── src/test/java/com/synapse/gateway/
    └── SynapseGatewayApplicationTests.java
```

## 4. 라우팅 규칙

| 경로 패턴 | 대상 서비스 URI | StripPrefix | 예시 |
|-----------|----------------|:-----------:|------|
| `/api/platform/**` | `http://platform-svc:8080` | 2 | `/api/platform/users` → `/users` |
| `/api/engagement/**` | `http://engagement-svc:8080` | 2 | `/api/engagement/posts` → `/posts` |
| `/api/knowledge/**` | `http://knowledge-svc:8080` | 2 | `/api/knowledge/notes` → `/notes` |
| `/api/learning/**` | `http://learning-card-svc:8080` | 2 | `/api/learning/cards` → `/cards` |

## 5. Rate Limit

- **알고리즘**: Redis 토큰 버킷 (Spring Cloud Gateway 내장)
- **replenishRate**: 1 token/sec
- **burstCapacity**: 60 tokens
- **requestedTokens**: 1 per request
- **결과**: 분당 60회, 버스트 60회까지 허용
- **Key**: 클라이언트 IP (`RemoteAddress`)
- **초과 시**: HTTP 429 Too Many Requests

### KeyResolver

`RateLimiterConfig.java`에 IP 기반 `KeyResolver` 빈을 정의한다:
```java
@Bean
public KeyResolver ipKeyResolver() {
    return exchange -> Mono.just(
        exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
    );
}
```

## 6. CORS 설정

```yaml
spring.cloud.gateway.globalcors:
  cors-configurations:
    '[/**]':
      allowed-origins:
        - "http://localhost:3000"
        - "http://localhost:8080"
      allowed-methods: [GET, POST, PUT, DELETE, PATCH, OPTIONS]
      allowed-headers: "*"
      allow-credentials: true
      max-age: 3600
```

## 7. Health Check

- Actuator 노출: `/actuator/health`, `/actuator/info`
- `management.endpoint.health.show-details: always`

## 8. Dockerfile

Multi-stage build:
- Stage 1: `eclipse-temurin:21-jdk-alpine` — Gradle 빌드
- Stage 2: `eclipse-temurin:21-jre-alpine` — 런타임 (JAR 실행)
- 포트: 8080

## 9. Docker Compose 연동

synapse-shared `docker-compose.yml`의 gateway stub을 실제 빌드로 교체:
```yaml
gateway:
  build:
    context: ../synapse-gateway
    dockerfile: Dockerfile
  container_name: synapse-gateway
  ports:
    - "8080:8080"
  depends_on:
    redis:
      condition: service_healthy
  environment:
    SPRING_DATA_REDIS_HOST: redis
    SPRING_DATA_REDIS_PORT: 6379
    SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD:-redis_local_pw}
  healthcheck:
    test: ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 5
  networks:
    - synapse-net
```

## 10. 검증 방법

| 항목 | 명령 | 기대 결과 |
|------|------|-----------|
| Health | `curl http://localhost:8080/actuator/health` | `{"status":"UP"}` |
| 라우팅 | `curl -v http://localhost:8080/api/platform/health` | 503 (upstream stub) 또는 200 (실제 서비스) |
| Rate Limit | 61회 연속 curl | 61번째부터 429 |
| CORS | `curl -H "Origin: http://localhost:3000" -X OPTIONS http://localhost:8080/api/platform/` | CORS 헤더 포함 |
