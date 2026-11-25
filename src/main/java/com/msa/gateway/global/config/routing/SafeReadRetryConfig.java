package com.msa.gateway.global.config.routing;

import io.netty.channel.ConnectTimeoutException;
import java.net.ConnectException;
import java.time.Duration;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory.RetryConfig;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * 조회(GET, HEAD) 요청만 다른 인스턴스로 한 번 재시도합니다. POST, PUT, PATCH, DELETE는 중복 처리 위험 때문에 재시도하지 않습니다.
 * 대상은 연결 거절, 연결 시간 초과(1초), 502/503/504 응답입니다. 500과 4xx, 응답 시간 초과(10초)는 서버가 이미 처리했거나 처리 중일
 * 수 있어 재시도하지 않습니다.
 */
@Configuration
@LoadBalancerClients(defaultConfiguration = ReadRetryLoadBalancerConfiguration.class)
public class SafeReadRetryConfig {
    // setExceptions가 제네릭 가변 인자라 생기는 경고만 억제합니다.
    @SuppressWarnings("unchecked")
    @Bean
    GatewayFilter safeReadRetry(RetryGatewayFilterFactory factory) {
        RetryConfig config =
                new RetryConfig()
                        .setRetries(1)
                        .setMethods(HttpMethod.GET, HttpMethod.HEAD)
                        // 기본값인 5xx 전체 대신 일시 오류 상태만 지정합니다.
                        .setSeries()
                        .setStatuses(
                                HttpStatus.BAD_GATEWAY,
                                HttpStatus.SERVICE_UNAVAILABLE,
                                HttpStatus.GATEWAY_TIMEOUT)
                        .setExceptions(ConnectException.class, ConnectTimeoutException.class)
                        .setBackoff(Duration.ofMillis(50), Duration.ofMillis(50), 1, false);
        // routeId를 지정하지 않아 요청 본문 캐시를 켜지 않습니다. 조회 요청은 본문을 다시 보낼 일이 없습니다.
        GatewayFilter retry = factory.apply(config);
        return (exchange, chain) -> {
            HttpMethod method = exchange.getRequest().getMethod();
            if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
                return retry.filter(exchange, chain);
            }
            return chain.filter(exchange);
        };
    }
}
