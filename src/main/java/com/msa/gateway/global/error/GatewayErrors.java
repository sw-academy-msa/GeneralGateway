package com.msa.gateway.global.error;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

/** 보안 필터와 프록시 실패를 Account의 공통 JSON 형식으로 응답합니다. */
@Component
public class GatewayErrors {
    private final ObjectMapper mapper;
    public GatewayErrors(ObjectMapper mapper) { this.mapper = mapper; }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        var response = exchange.getResponse();
        if (response.isCommitted()) return Mono.empty();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        byte[] body = mapper.writeValueAsBytes(new ErrorBody(code, message,
                exchange.getAttribute("traceId"), List.of()));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** HTTP 오류 응답이며 예외 원문·주소·인증정보를 포함하지 않습니다. */
    public record ErrorBody(String code, String message, String traceId, List<Object> violations) { }
}
