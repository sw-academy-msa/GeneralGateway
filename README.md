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
- Account는 `lb://account-service`, Product Query의 GET은 `lb://product-query-service`, Payment 예약 경로 `/api/v1/payments` 및 하위 GET/POST는 `lb://payment-service`로 연결합니다. Payment는 BUYER 전용 GET /api/v1/payments/client-configuration으로 공개 SDK 키를 제공하며 승인·조회·보상은 Order 전용 Kafka로 처리합니다. 미구현 HTTP 경로도 인증되지 않은 요청은 401, 인증 후 HTTP 메서드 또는 역할 규칙에 거절된 요청은 403, 해당 규칙을 통과했으나 일치하는 라우트가 없는 요청은 404를 반환합니다. Product·Order Command는 PurchaseGateway 책임입니다.
- Order Query의 주문 목록, 상세, 주문 문의 GET은 `lb://order-query-service`로 연결합니다. 구매자는 본인 구매 주문, 판매자는 본인 판매 주문만 조회하며 소유권은 서비스에서도 검증합니다. checkout, 문의 편집, 배송 변경은 PurchaseGateway에 유지합니다.
- `.env`, 개인키, 토큰은 커밋하지 않습니다. `.env`는 bootRun에서 자동으로 읽지 않습니다.
- 상세 문서는 Account의 기존 규칙에 맞춰 로컬 관리하며 Git에서 제외합니다. README는 기존 추적 상태를 유지합니다.

## AI 개인화 분석 참여

AIService의 BUYER 전용 POST/GET/DELETE /api/v1/ai/analytics/identity와 GET /api/v1/ai/analytics/profile을 lb://ai-service로 연결합니다. 기존 SELLER 분석 결과 GET은 유지하며 내부 /internal/v1/personalization/eligibility는 외부에 라우팅하지 않습니다.
