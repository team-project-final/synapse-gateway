# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
RUN apk add --no-cache bash dos2unix
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN dos2unix gradlew && chmod +x gradlew && ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew bootJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -g 101 -S app && adduser -u 101 -S -G app app
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown app:app app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
