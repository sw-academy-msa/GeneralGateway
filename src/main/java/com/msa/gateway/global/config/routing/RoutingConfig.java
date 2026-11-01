package com.msa.gateway.global.config.routing;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 검토된 Account 경로만 공개합니다. Product Command는 구매 Gateway 구성 전까지 연결하지 않습니다. Eureka 등록 자체는 외부 공개 권한이 아닙니다. */
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
                .build();
    }
}
