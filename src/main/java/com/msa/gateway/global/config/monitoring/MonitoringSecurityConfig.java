package com.msa.gateway.global.config.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import reactor.core.publisher.Mono;

/** 메트릭은 Gateway 업무 라우트와 JWT 필터 체인을 거치지 않고 전용 토큰으로만 조회합니다. */
@Configuration(proxyBeanMethods = false)
public class MonitoringSecurityConfig {
    @Bean
    @Order(-100)
    public SecurityWebFilterChain monitoringSecurity(
            ServerHttpSecurity http, @Value("${monitoring.scrape-token:}") String token) {
        return http.securityMatcher(
                        ServerWebExchangeMatchers.pathMatchers(
                                "/actuator/prometheus", "/actuator/prometheus/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(
                        a ->
                                a.anyExchange()
                                        .access(
                                                (authentication, context) ->
                                                        Mono.just(
                                                                new AuthorizationDecision(
                                                                        MonitoringAccess.allowed(
                                                                                token,
                                                                                context.getExchange()
                                                                                        .getRequest()
                                                                                        .getHeaders()
                                                                                        .getFirst(
                                                                                                "Authorization"),
                                                                                context.getExchange()
                                                                                        .getRequest()
                                                                                        .getMethod()
                                                                                        .name())))))
                .build();
    }
}
