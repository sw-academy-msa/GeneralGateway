package com.msa.gateway.global.error;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/** 인스턴스 부재·연결 실패·시간 초과를 구분하고 내부 예외를 외부에 노출하지 않습니다. */
@Component
@Order(-2)
public class GatewayExceptionHandler implements WebExceptionHandler {
    private final GatewayErrors errors;
    public GatewayExceptionHandler(GatewayErrors errors) { this.errors = errors; }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        if (exchange.getResponse().isCommitted()) return Mono.error(exception);
        HttpStatus status = exception instanceof ResponseStatusException response
                ? HttpStatus.valueOf(response.getStatusCode().value()) : HttpStatus.INTERNAL_SERVER_ERROR;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) { status = HttpStatus.BAD_GATEWAY; break; }
            if (cause instanceof TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException) {
                status = HttpStatus.GATEWAY_TIMEOUT; break;
            }
        }
        String code = switch (status) {
            case SERVICE_UNAVAILABLE -> "GATEWAY_SERVICE_UNAVAILABLE";
            case BAD_GATEWAY -> "GATEWAY_BAD_GATEWAY";
            case GATEWAY_TIMEOUT -> "GATEWAY_TIMEOUT";
            case NOT_FOUND -> "COMMON_RESOURCE_NOT_FOUND";
            default -> "GATEWAY_REQUEST_FAILED";
        };
        return errors.write(exchange, status, code,
                status == HttpStatus.NOT_FOUND ? "요청한 리소스를 찾을 수 없습니다." : "요청을 처리할 수 없습니다.");
    }
}
