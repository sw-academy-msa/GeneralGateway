package com.msa.gateway.global.config.security;

import com.msa.gateway.global.error.GatewayErrors;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.*;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import java.util.List;

/** Bearer JWT 경계를 보호하며 쿠키 인증의 CSRF 검증은 Account가 계속 담당합니다. */
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain security(ServerHttpSecurity http, AuthProperties properties, GatewayErrors errors) {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http
                // Gateway는 세션·Refresh 인증을 하지 않으므로 이중 CSRF 토큰을 생성하지 않습니다.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(cors(properties)))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint((exchange, exception) -> errors.write(exchange, HttpStatus.UNAUTHORIZED,
                                "COMMON_UNAUTHORIZED", "인증이 필요합니다."))
                        .accessDeniedHandler((exchange, exception) -> errors.write(exchange, HttpStatus.FORBIDDEN,
                                "COMMON_FORBIDDEN", "요청을 수행할 권한이 없습니다.")))
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                                "/api/v1/auth/csrf", "/.well-known/jwks.json", "/api/v1/signup-options").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/email-verifications",
                                "/api/v1/auth/email-verifications/confirm", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout", "/api/v1/auth/password-resets", "/api/v1/auth/password-resets/confirm",
                                "/api/v1/auth/password-resets/complete").permitAll()
                        .pathMatchers("/actuator/**").denyAll()
                        // 최신 권한과 데이터 소유권은 각 업무 서비스에서 다시 검사합니다.
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter)))
                        .authenticationEntryPoint((exchange, exception) -> errors.write(exchange, HttpStatus.UNAUTHORIZED,
                                "COMMON_UNAUTHORIZED", "인증이 필요합니다.")))
                .build();
    }

    private UrlBasedCorsConfigurationSource cors(AuthProperties properties) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN", "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
