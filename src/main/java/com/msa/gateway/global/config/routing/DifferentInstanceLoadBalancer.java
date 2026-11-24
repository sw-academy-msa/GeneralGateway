package com.msa.gateway.global.config.routing;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

/**
 * 라운드 로빈으로 고르되, 같은 요청에서 앞서 고른 주소(host+port)는 제외합니다. 이전 선택은 Gateway가 요청 속성에 남긴 값을 읽으므로
 * 동시 요청이 많아도 재시도가 같은 서버로 가지 않습니다.
 */
final class DifferentInstanceLoadBalancer implements ReactorServiceInstanceLoadBalancer {
    private final ObjectProvider<ServiceInstanceListSupplier> supplier;
    // 재시도 선택이 첫 시도 순번을 밀면 실패 인스턴스가 매번 첫 시도로 걸리므로 순번을 따로 둡니다.
    private final AtomicInteger position = new AtomicInteger();
    private final AtomicInteger retryPosition = new AtomicInteger();

    DifferentInstanceLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> supplier) {
        this.supplier = supplier;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        return supplier.getObject()
                .get(request)
                .next()
                .map(instances -> select(instances, previous(request)));
    }

    private static ServiceInstance previous(Request request) {
        if (request.getContext() instanceof RequestDataContext context
                && context.getClientRequest() != null
                && context.getClientRequest().getAttributes() != null
                && context.getClientRequest().getAttributes().get(GATEWAY_LOADBALANCER_RESPONSE_ATTR)
                        instanceof Response<?> selected
                && selected.getServer() instanceof ServiceInstance instance) {
            return instance;
        }
        return null;
    }

    private Response<ServiceInstance> select(List<ServiceInstance> instances, ServiceInstance excluded) {
        // 인스턴스 ID가 바뀌어도 같은 접속 주소는 제외합니다. 대체 인스턴스가 없으면 같은 곳에 다시 보내지 않고 503으로 끝냅니다.
        List<ServiceInstance> candidates =
                instances.stream().filter(instance -> !sameAddress(instance, excluded)).toList();
        if (candidates.isEmpty()) {
            return new EmptyResponse();
        }
        AtomicInteger cursor = excluded == null ? position : retryPosition;
        return new DefaultResponse(
                candidates.get(Math.floorMod(cursor.getAndIncrement(), candidates.size())));
    }

    private static boolean sameAddress(ServiceInstance instance, ServiceInstance excluded) {
        return excluded != null
                && instance.getHost().equals(excluded.getHost())
                && instance.getPort() == excluded.getPort();
    }
}
