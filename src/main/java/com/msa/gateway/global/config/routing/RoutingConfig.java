package com.msa.gateway.global.config.routing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 일반 업무 API와 Product/Order Query GET을 연결합니다. Command는 PurchaseGateway에 두며 Eureka 등록만으로 경로를 공개하지
 * 않습니다. 예외는 Product Command의 공개 카테고리 카탈로그 GET 하나입니다.
 */
@Configuration
public class RoutingConfig {
    private final boolean couponEnabled;
    private final boolean aiEventsEnabled;

    public RoutingConfig(
            @org.springframework.beans.factory.annotation.Value("${GATEWAY_COUPON_ENABLED:false}")
                    boolean couponEnabled,
            @org.springframework.beans.factory.annotation.Value(
                            "${GATEWAY_AI_EVENTS_ENABLED:false}")
                    boolean aiEventsEnabled) {
        this.couponEnabled = couponEnabled;
        this.aiEventsEnabled = aiEventsEnabled;
    }

    @Bean
    public RouteLocator accountRoutes(
            RouteLocatorBuilder builder, @Qualifier("safeReadRetry") GatewayFilter safeReadRetry) {
        // safeReadRetry는 GET, HEAD만 재시도하고 쓰기 메서드는 그대로 한 번만 전달합니다.
        // 경로를 보존하고 Cookie/Set-Cookie/Authorization을 그대로 전달합니다.
        // CORS는 Gateway에서 처리하므로 Account에는 Origin을 전달하지 않습니다.
        return builder.routes()
                .route(
                        "account",
                        route ->
                                route.path(
                                                "/api/v1/auth/**",
                                                "/api/v1/signup-options",
                                                "/api/v1/accounts/me",
                                                "/api/v1/accounts/me/**",
                                                "/api/v1/seller-applications",
                                                "/api/v1/seller-applications/**",
                                                "/api/v1/sellers/me",
                                                "/api/v1/sellers/me/**",
                                                "/api/v1/admin/seller-applications",
                                                "/api/v1/admin/seller-applications/**",
                                                "/api/v1/support-inquiries",
                                                "/api/v1/support-inquiries/**",
                                                "/api/v1/admin/support-inquiries",
                                                "/api/v1/admin/support-inquiries/**",
                                                "/.well-known/jwks.json")
                                        .filters(
                                                filters ->
                                                        filters.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin"))
                                        .uri("lb://account-service"))
                // Query는 GET만 허용합니다. 상품 문의 POST와 판매자 변경은 PurchaseGateway 책임입니다.
                .route(
                        "product-query",
                        route ->
                                route.path(
                                                "/api/v1/products", "/api/v1/products/*",
                                                "/api/v1/products/*/options",
                                                        "/api/v1/products/*/faqs",
                                                "/api/v1/products/*/inquiries",
                                                        "/api/v1/categories")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.GET)
                                        .filters(
                                                filters ->
                                                        filters.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://product-query-service"))
                // 공개 카테고리 카탈로그 GET만 Product Command로 연결합니다. 공개 자료라 인증 헤더와 쿠키는 넘기지 않습니다.
                .route(
                        "product-category-catalog",
                        route ->
                                route.path("/api/v1/category-catalog")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.GET)
                                        .filters(
                                                filters ->
                                                        filters.removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie")
                                                                .removeRequestHeader(
                                                                        "Authorization"))
                                        .uri("lb://product-command-service"))
                // 주문 목록·상세·문의 GET만 Query로 연결하며 checkout/편집 조회는 구매 Gateway에 둡니다.
                .route(
                        "order-query",
                        route ->
                                route.path(
                                                "/api/v1/orders", "/api/v1/orders/*",
                                                "/api/v1/orders/*/inquiries",
                                                        "/api/v1/orders/*/inquiries/*",
                                                "/api/v1/seller/orders", "/api/v1/seller/orders/*",
                                                "/api/v1/seller/orders/*/inquiries",
                                                        "/api/v1/seller/orders/*/inquiries/*")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.GET)
                                        .filters(
                                                filters ->
                                                        filters.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://order-query-service"))
                // Payment 경로 예약이며 내부·Actuator 경로는 외부로 공개하지 않습니다.
                .route(
                        "payment",
                        route ->
                                route.path("/api/v1/payments", "/api/v1/payments/**")
                                        .and()
                                        .method(
                                                org.springframework.http.HttpMethod.GET,
                                                org.springframework.http.HttpMethod.POST)
                                        .filters(
                                                filters ->
                                                        filters.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://payment-service"))
                // AI 원본 변경 요청은 Product Command에 두고 본인 결과 조회만 연결합니다.
                .route(
                        "ai-analysis",
                        route ->
                                route.path("/api/v1/ai/product-analyses/*")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.GET)
                                        .filters(
                                                filters ->
                                                        filters.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://ai-service"))
                .route(
                        "ai-analytics-read",
                        route ->
                                route.path(
                                                "/api/v1/ai/analytics/identity",
                                                "/api/v1/ai/analytics/profile")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.GET)
                                        .filters(
                                                filters ->
                                                        filters.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://ai-service"))
                .route(
                        "ai-analytics-consent",
                        route ->
                                route.path("/api/v1/ai/analytics/identity")
                                        .and()
                                        .method(
                                                org.springframework.http.HttpMethod.POST,
                                                org.springframework.http.HttpMethod.DELETE)
                                        .filters(
                                                filters ->
                                                        filters.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://ai-service"))
                // 행동 이벤트 수집은 쓰기이므로 재시도하지 않고 플래그를 켰을 때만 연결합니다.
                .route(
                        "ai-behavior-events",
                        route ->
                                route.path("/api/v1/ai/events")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.POST)
                                        .and()
                                        .predicate(exchange -> aiEventsEnabled)
                                        .filters(
                                                filters ->
                                                        filters.removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://ai-service"))
                // Notification의 알림함과 구독 API만 공개합니다. 내부 수신 자격 API는 제외합니다.
                .route(
                        "notifications-read",
                        r ->
                                r.path("/api/v1/notifications", "/api/v1/notifications/*")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.GET)
                                        .filters(
                                                f ->
                                                        f.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://notification-service"))
                .route(
                        "notifications-subscribe",
                        r ->
                                r.path("/api/v1/notifications/push-subscriptions")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.POST)
                                        .filters(
                                                f ->
                                                        f.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://notification-service"))
                .route(
                        "notifications-read-state",
                        r ->
                                r.path(
                                                "/api/v1/notifications/*/read",
                                                "/api/v1/notifications/read-all")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.PATCH)
                                        .filters(
                                                f ->
                                                        f.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://notification-service"))
                .route(
                        "notifications-unsubscribe",
                        r ->
                                r.path("/api/v1/notifications/push-subscriptions/*")
                                        .and()
                                        .method(org.springframework.http.HttpMethod.DELETE)
                                        .filters(
                                                f ->
                                                        f.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://notification-service"))
                // 상품 상세의 '알림 받기' 신청, 취소, 단건 조회입니다.
                .route(
                        "notifications-product-alert",
                        r ->
                                r.path("/api/v1/notifications/product-alerts/*")
                                        .and()
                                        .method(
                                                org.springframework.http.HttpMethod.GET,
                                                org.springframework.http.HttpMethod.PUT,
                                                org.springframework.http.HttpMethod.DELETE)
                                        .filters(
                                                f ->
                                                        f.filter(safeReadRetry)
                                                                .removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://notification-service"))
                // 쿠폰 공개 조회와 인증 API만 연결하고 내부 잠금 경로는 공개하지 않습니다.
                .route(
                        "coupon",
                        r ->
                                r.path(
                                                "/api/v1/coupons",
                                                "/api/v1/coupons/**",
                                                "/api/v1/admin/coupons/**")
                                        .and()
                                        .predicate(exchange -> couponEnabled)
                                        .filters(
                                                f ->
                                                        f.removeRequestHeader("Origin")
                                                                .removeRequestHeader("Cookie"))
                                        .uri("lb://coupon-service"))
                .build();
    }
}
