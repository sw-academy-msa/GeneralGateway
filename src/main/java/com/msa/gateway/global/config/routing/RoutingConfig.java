package com.msa.gateway.global.config.routing;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Account와 Product Query GET만 공개합니다. Product Command는 PurchaseGateway에 둡니다. Eureka 등록 자체는 외부 공개 권한이 아닙니다. */
@Configuration
public class RoutingConfig {
    @Bean
    public RouteLocator accountRoutes(RouteLocatorBuilder builder) {
        // 경로를 보존하고 Cookie/Set-Cookie/Authorization을 그대로 전달합니다.
        // CORS는 Gateway에서 처리하므로 Account에는 Origin을 전달하지 않습니다.
        return builder.routes().route("account", route -> route.path(
                "/api/v1/auth/**", "/api/v1/signup-options", "/api/v1/accounts/me", "/api/v1/accounts/me/**",
                "/api/v1/seller-applications", "/api/v1/seller-applications/**",
                "/api/v1/sellers/me", "/api/v1/sellers/me/**",
                "/api/v1/admin/seller-applications", "/api/v1/admin/seller-applications/**",
                "/.well-known/jwks.json")
                .filters(filters -> filters.removeRequestHeader("Origin"))
                .uri("lb://account-service"))
                // Query는 GET만 허용합니다. 상품 문의 POST와 판매자 변경은 PurchaseGateway 책임입니다.
                .route("product-query", route -> route.path(
                        "/api/v1/products", "/api/v1/products/*",
                        "/api/v1/products/*/options", "/api/v1/products/*/faqs",
                        "/api/v1/products/*/inquiries", "/api/v1/categories")
                        .and().method(org.springframework.http.HttpMethod.GET)
                        .filters(filters -> filters.removeRequestHeader("Origin").removeRequestHeader("Cookie"))
                        .uri("lb://product-query-service"))
                // Payment 경로 예약이며 내부·Actuator 경로는 외부로 공개하지 않습니다.
                .route("payment", route -> route.path("/api/v1/payments", "/api/v1/payments/**")
                        .and().method(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.POST)
                        .filters(filters -> filters.removeRequestHeader("Origin").removeRequestHeader("Cookie"))
                        .uri("lb://payment-service"))
                .build();
    }
}
