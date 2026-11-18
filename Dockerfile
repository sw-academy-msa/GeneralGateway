# Ubuntu 기반 Temurin은 amd64/arm64 공통 태그를 제공합니다.
# JAR는 아키텍처 독립적이므로 빌드는 호스트에서 수행해 ARM 에뮬레이션 비용을 줄입니다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
RUN chmod +x gradlew
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# 런타임은 비특권 사용자로 실행합니다.
RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=builder --chown=spring:spring /workspace/build/libs/*.jar app.jar
USER spring:spring
EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
