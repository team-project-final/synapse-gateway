# Spring Cloud Gateway 라우팅 + Rate Limit 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring Cloud Gateway에서 4개 서비스로의 라우팅과 Redis 기반 Rate Limit(분당 60회/IP)을 설정한다.

**Architecture:** Spring Cloud Gateway 5.0.1 (WebFlux) 기반 단일 API Gateway 프로젝트. application.yml로 라우팅/Rate Limit/CORS를 선언적으로 설정하고, Java 코드는 Application 클래스 + KeyResolver 빈 1개만 작성한다. synapse-shared의 Docker Compose와 연동하여 통합 검증한다.

**Tech Stack:** Spring Boot 4.0.6, Spring Cloud 2025.1.1, Spring Cloud Gateway 5.0.1 (gateway-server-webflux), Java 21, Redis 7, Gradle

---

## 파일 구조

### 생성할 파일

| 파일 | 역할 |
|------|------|
| `settings.gradle.kts` | 프로젝트 이름 |
| `build.gradle.kts` | 의존성 + 빌드 설정 |
| `src/main/java/com/synapse/gateway/SynapseGatewayApplication.java` | Spring Boot main 클래스 |
| `src/main/java/com/synapse/gateway/config/RateLimiterConfig.java` | IP 기반 KeyResolver 빈 |
| `src/main/resources/application.yml` | 라우팅 + Rate Limit + CORS + Actuator |
| `src/test/java/com/synapse/gateway/SynapseGatewayApplicationTests.java` | 컨텍스트 로드 테스트 |
| `Dockerfile` | Multi-stage 빌드 (Gradle → JRE 21) |
| `.gitignore` | IDE/빌드 산출물 제외 |

### 수정할 파일 (synapse-shared 레포)

| 파일 | 변경 |
|------|------|
| `C:/.../synapse-shared/docker-compose.yml` | gateway stub → 실제 빌드 이미지 교체 |

---

## Task 1: Gradle 프로젝트 스캐폴딩

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `.gitignore`

- [ ] **Step 1: .gitignore 생성**

```gitignore
# IDE
.idea/
*.iml
.vscode/
*.swp

# Environment
.env
.env.*
!.env.example

# OS
.DS_Store
Thumbs.db

# Java / Gradle
build/
.gradle/
bin/
out/
*.class
*.jar
*.war
hs_err_pid*

# Gradle wrapper must be committed
!gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 2: settings.gradle.kts 생성**

```kotlin
rootProject.name = "synapse-gateway"
```

- [ ] **Step 3: build.gradle.kts 생성**

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.synapse"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

extra["springCloudVersion"] = "2025.1.1"

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Gradle wrapper 생성**

Run:
```bash
cd C:/workspace/team-project-manager/team-project-final/synapse-gateway
gradle wrapper --gradle-version 8.12
```

Expected: `gradle/wrapper/gradle-wrapper.jar` + `gradlew` + `gradlew.bat` 생성

- [ ] **Step 5: gradlew 실행 권한 추가**

Run:
```bash
chmod +x gradlew
```

- [ ] **Step 6: 빌드 검증**

Run:
```bash
./gradlew dependencies --configuration compileClasspath | head -30
```

Expected: `spring-cloud-gateway-server-webflux`, `spring-boot-starter-data-redis-reactive`, `spring-boot-starter-actuator` 확인

- [ ] **Step 7: 커밋**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat
git commit -m "chore: init Gradle project — Boot 4.0.6 + Cloud 2025.1.1 + Gateway 5.0.1"
```

---

## Task 2: Application 클래스 + 컨텍스트 로드 테스트

**Files:**
- Create: `src/main/java/com/synapse/gateway/SynapseGatewayApplication.java`
- Create: `src/test/java/com/synapse/gateway/SynapseGatewayApplicationTests.java`
- Create: `src/main/resources/application.yml` (최소 설정)

- [ ] **Step 1: application.yml 최소 설정 생성**

```yaml
server:
  port: 8080

spring:
  application:
    name: synapse-gateway
  # Redis 비활성화 (테스트용 — Rate Limit 없이 컨텍스트 로드)
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      password: ${SPRING_DATA_REDIS_PASSWORD:}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 2: SynapseGatewayApplication.java 생성**

```java
package com.synapse.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SynapseGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(SynapseGatewayApplication.class, args);
    }
}
```

- [ ] **Step 3: 컨텍스트 로드 테스트 작성**

```java
package com.synapse.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SynapseGatewayApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 4: 빌드 + 테스트 실행**

Run:
```bash
./gradlew clean build --no-daemon
```

Expected: `BUILD SUCCESSFUL` (Redis 미연결이라 Rate Limiter 자동 설정이 실패할 수 있음 — Spring Boot는 Redis 미접속 시에도 컨텍스트 로드 가능)

> 만약 Redis 연결 오류로 테스트 실패 시, `@SpringBootTest` 대신 `@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")` 사용.

- [ ] **Step 5: 커밋**

```bash
git add src/ 
git commit -m "feat: add Application class + context load test"
```

---

## Task 3: 라우팅 + Rate Limit + CORS 설정

**Files:**
- Create: `src/main/java/com/synapse/gateway/config/RateLimiterConfig.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: RateLimiterConfig.java 생성**

```java
package com.synapse.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
            exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
        );
    }
}
```

- [ ] **Step 2: application.yml 전체 설정으로 교체**

```yaml
server:
  port: 8080

spring:
  application:
    name: synapse-gateway
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins:
              - "http://localhost:3000"
              - "http://localhost:8080"
            allowed-methods:
              - GET
              - POST
              - PUT
              - DELETE
              - PATCH
              - OPTIONS
            allowed-headers: "*"
            allow-credentials: true
            max-age: 3600
      default-filters:
        - name: RequestRateLimiter
          args:
            redis-rate-limiter.replenishRate: 1
            redis-rate-limiter.burstCapacity: 60
            redis-rate-limiter.requestedTokens: 1
            key-resolver: "#{@ipKeyResolver}"
      routes:
        - id: platform-svc
          uri: ${PLATFORM_SVC_URI:http://platform-svc:8080}
          predicates:
            - Path=/api/platform/**
          filters:
            - StripPrefix=2
        - id: engagement-svc
          uri: ${ENGAGEMENT_SVC_URI:http://engagement-svc:8080}
          predicates:
            - Path=/api/engagement/**
          filters:
            - StripPrefix=2
        - id: knowledge-svc
          uri: ${KNOWLEDGE_SVC_URI:http://knowledge-svc:8080}
          predicates:
            - Path=/api/knowledge/**
          filters:
            - StripPrefix=2
        - id: learning-svc
          uri: ${LEARNING_SVC_URI:http://learning-card-svc:8080}
          predicates:
            - Path=/api/learning/**
          filters:
            - StripPrefix=2
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      password: ${SPRING_DATA_REDIS_PASSWORD:}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 3: 빌드 확인**

Run:
```bash
./gradlew clean build --no-daemon
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add src/
git commit -m "feat(gateway): add routing rules, rate limit, and CORS config

- Routes: /api/platform/**, /api/engagement/**, /api/knowledge/**, /api/learning/**
- Rate Limit: 1 req/sec refill, 60 burst (IP-based KeyResolver)
- CORS: localhost:3000 + localhost:8080
- Actuator: /actuator/health, /actuator/info"
```

---

## Task 4: Dockerfile

**Files:**
- Create: `Dockerfile`

- [ ] **Step 1: Dockerfile 생성**

```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew bootJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Docker 빌드 테스트**

Run:
```bash
cd C:/workspace/team-project-manager/team-project-final/synapse-gateway
docker build -t synapse-gateway:local .
```

Expected: `Successfully tagged synapse-gateway:local`

- [ ] **Step 3: 커밋**

```bash
git add Dockerfile
git commit -m "chore: add multi-stage Dockerfile (JDK 21 build → JRE 21 runtime)"
```

---

## Task 5: Docker Compose 연동 + 통합 검증

**Files:**
- Modify: `C:/workspace/team-project-manager/team-project-final/synapse-shared/docker-compose.yml`

- [ ] **Step 1: synapse-shared docker-compose.yml의 gateway stub을 실제 빌드로 교체**

기존:
```yaml
  gateway:
    image: eclipse-temurin:21-jre-alpine
    container_name: synapse-gateway
    entrypoint: ["sh", "-c", "echo 'gateway stub — replace with real Gateway image' && sleep infinity"]
    ports:
      - "8080:8080"
    depends_on:
      redis:
        condition: service_healthy
    networks:
      - synapse-net
```

변경:
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
      PLATFORM_SVC_URI: http://platform-svc:8080
      ENGAGEMENT_SVC_URI: http://engagement-svc:8080
      KNOWLEDGE_SVC_URI: http://knowledge-svc:8080
      LEARNING_SVC_URI: http://learning-card-svc:8080
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s
    networks:
      - synapse-net
```

- [ ] **Step 2: Docker Compose로 전체 환경 기동**

Run:
```bash
cd C:/workspace/team-project-manager/team-project-final/synapse-shared
docker compose up -d --build gateway
docker compose up -d
```

Expected: gateway 빌드 + 전체 서비스 기동

- [ ] **Step 3: Gateway Health 확인**

Run:
```bash
curl http://localhost:8080/actuator/health
```

Expected: `{"status":"UP",...}` (Redis 연결 포함)

- [ ] **Step 4: 라우팅 동작 확인 (4개 서비스)**

Run:
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/platform/health
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/engagement/health
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/knowledge/health
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/learning/health
```

Expected: 각각 `503` (upstream 서비스가 stub이므로 연결 거부 → 503). Gateway가 라우팅을 시도했다는 것 자체가 라우팅 동작 확인.

> 만약 `404`가 나오면 라우팅 규칙이 매치되지 않은 것 — application.yml 확인 필요.

- [ ] **Step 5: Rate Limit 429 확인**

Run:
```bash
for i in $(seq 1 65); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/platform/health)
  if [ "$STATUS" = "429" ]; then
    echo "429 at request #$i"
    break
  fi
done
```

Expected: `429 at request #61` (또는 그 근처 — 토큰 버킷 타이밍에 따라 ±1)

- [ ] **Step 6: CORS preflight 확인**

Run:
```bash
curl -v -X OPTIONS \
  -H "Origin: http://localhost:3000" \
  -H "Access-Control-Request-Method: POST" \
  http://localhost:8080/api/platform/health 2>&1 | grep -i "access-control"
```

Expected:
```
< Access-Control-Allow-Origin: http://localhost:3000
< Access-Control-Allow-Methods: GET,POST,PUT,DELETE,PATCH,OPTIONS
```

- [ ] **Step 7: 환경 정리**

Run:
```bash
cd C:/workspace/team-project-manager/team-project-final/synapse-shared
docker compose down
```

- [ ] **Step 8: synapse-shared 커밋**

```bash
cd C:/workspace/team-project-manager/team-project-final/synapse-shared
git add docker-compose.yml
git commit -m "feat(infra): replace gateway stub with real build in Docker Compose"
```

- [ ] **Step 9: synapse-gateway 커밋 (검증 완료 기록)**

```bash
cd C:/workspace/team-project-manager/team-project-final/synapse-gateway
git add -A
git commit --allow-empty -m "chore: integration test passed — routing, rate limit 429, CORS verified"
```

---

## Task 6: 프로젝트 관리 문서 갱신

**Files:**
- Modify: `C:/.../synapse-shared/docs/project-management/task/TASK_team-lead.md`
- Modify: `C:/.../synapse-shared/docs/project-management/workflow/WORKFLOW_team-lead_W2.md`
- Modify: `C:/.../synapse-shared/docs/project-management/history/HISTORY_team-lead.md`

- [ ] **Step 1: TASK Step 6 Done When 체크 + Status → Done**

`TASK_team-lead.md` Step 6:
```markdown
- **Done When**:
  - [x] Gateway → platform-svc 라우팅 동작
  - [x] Gateway → engagement-svc 라우팅 동작
  - [x] Gateway → knowledge-svc 라우팅 동작
  - [x] Gateway → learning-svc 라우팅 동작
  - [x] Rate Limit 설정 적용 (Redis 기반)
  - [x] Rate Limit 초과 시 429 응답 확인
```

Status:
```markdown
**Status**: [ ] Not Started / [ ] In Progress / [x] Done
```

- [ ] **Step 2: WORKFLOW Step 6 나머지 체크박스 완료**

`WORKFLOW_team-lead_W2.md` Step 6:
- 6.7 Gateway 구현: 모든 항목 `[x]`
- 6.8 라우팅 테스트: 모든 항목 `[x]`
- Status: `[x] Done`

- [ ] **Step 3: HISTORY 갱신**

`HISTORY_team-lead.md`:
- W2 대시보드 Step 6: `Done` + 완료일 기재
- W2 진행률: `5/5 Steps 완료`
- 해당 날짜 로그에 Step 6 완료 기록

- [ ] **Step 4: synapse-shared 문서 커밋**

```bash
cd C:/workspace/team-project-manager/team-project-final/synapse-shared
git add docs/project-management/
git commit -m "docs: update Step 6 completion — Gateway routing + rate limit done"
```

---

## 실행 순서 요약

| Task | 내용 | 레포 |
|:----:|------|:----:|
| 1 | Gradle 프로젝트 스캐폴딩 | synapse-gateway |
| 2 | Application + 컨텍스트 테스트 | synapse-gateway |
| 3 | 라우팅 + Rate Limit + CORS | synapse-gateway |
| 4 | Dockerfile | synapse-gateway |
| 5 | Docker Compose 연동 + 통합 검증 | synapse-shared + synapse-gateway |
| 6 | 프로젝트 관리 문서 갱신 | synapse-shared |
