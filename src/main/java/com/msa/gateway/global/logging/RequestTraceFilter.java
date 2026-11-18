package com.msa.gateway.global.logging;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.util.UUID;

/** Reactor 요청 속성으로 추적 ID를 전달합니다. 토큰·쿠키·본문은 로깅하지 않습니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        // 임의 문자열·개행·과도한 길이가 로그 추적 ID로 전파되지 않도록 제한합니다.
        String traceId = incoming != null && incoming.matches("[A-Za-z0-9_-]{1,64}")
                ? incoming : UUID.randomUUID().toString();
        exchange.getAttributes().put("traceId", traceId);
        exchange.getResponse().getHeaders().set("X-Request-Id", traceId);
        // 하위 서비스도 같은 헤더를 반환하므로 프록시 응답 병합 후 하나의 추적 ID로 확정합니다.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set("X-Request-Id", traceId);
            return Mono.empty();
        });
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.set("X-Request-Id", traceId);
            // 서비스는 JWT를 직접 검증하며 외부의 신원 주장 헤더는 전달하지 않습니다.
            headers.remove("X-User-Id");
            headers.remove("X-User-Roles");
            headers.remove("X-Account-Id");
            // 현재는 forwarded 정보를 사용하지 않습니다. 클라이언트가 Account에 위조 값을 전달하지 못하게 합니다.
            headers.remove("Forwarded");
            headers.headerNames().stream().filter(name -> name.regionMatches(true, 0, "X-Forwarded-", 0, 12))
                    .toList().forEach(headers::remove);
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}
