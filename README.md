# STOCKDROP Gateway

Java 21 / Spring Boot 4.1.1 / Spring Cloud 2025.1.4 기반 Server WebFlux Gateway입니다.

```powershell
# Java 21을 JAVA_HOME에 지정하고 Eureka(8761), Account(8080)를 먼저 실행합니다.
.\gradlew.bat bootRun
.\gradlew.bat test bootJar
```

- 기본 주소: http://localhost:8000
- 상태 확인: http://localhost:8000/actuator/health
- 실행·인증·배포·검증 상세: [운영 안내](docs/gateway.md)
- Account만 `lb://account-service`로 연결합니다. Product Command 라우트는 구매 Gateway 구성 전까지 제거한 상태입니다. 경로와 Bearer 헤더를 보존합니다.
- `.env`, 개인키, 토큰은 커밋하지 않습니다. `.env`는 bootRun에서 자동으로 읽지 않습니다.
- 상세 문서는 Account의 기존 규칙에 맞춰 로컬 관리하며 Git에서 제외합니다. README는 기존 추적 상태를 유지합니다.
