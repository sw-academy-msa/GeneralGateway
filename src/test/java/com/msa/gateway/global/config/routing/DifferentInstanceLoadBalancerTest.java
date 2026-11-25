package com.msa.gateway.global.config.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.ServiceInstanceListSuppliers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;

/** 재시도에서 앞서 고른 주소를 제외하는 선택 규칙만 검증합니다. */
class DifferentInstanceLoadBalancerTest {
    static final ServiceInstance A = instance("a", 8081);
    static final ServiceInstance B = instance("b", 8082);

    @Test
    void firstAttemptRotatesOverAllInstances() {
        var balancer = balancer(A, B);
        assertThat(List.of(choose(balancer, null), choose(balancer, null))).containsExactly(A, B);
    }

    @Test
    void retryNeverReturnsThePreviousAddress() {
        var balancer = balancer(A, B);
        for (int i = 0; i < 5; i++) assertThat(choose(balancer, A)).isEqualTo(B);
    }

    @Test
    void retryDoesNotShiftFirstAttemptRotation() {
        var balancer = balancer(A, B);
        // A 실패 후 B로 재시도해도 다음 첫 시도는 B, 그다음은 A로 고르게 돌아갑니다.
        assertThat(choose(balancer, null)).isEqualTo(A);
        assertThat(choose(balancer, A)).isEqualTo(B);
        assertThat(List.of(choose(balancer, null), choose(balancer, null))).containsExactly(B, A);
    }

    @Test
    void sameAddressWithAnotherIdIsAlsoExcluded() {
        var balancer = balancer(A, B);
        ServiceInstance renamed = new DefaultServiceInstance("other-id", "svc", "a", 8081, false);
        assertThat(choose(balancer, renamed)).isEqualTo(B);
    }

    @Test
    void noAlternativeEndsWithoutServer() {
        var balancer = balancer(A);
        assertThat(choose(balancer, A)).isNull();
    }

    private static DifferentInstanceLoadBalancer balancer(ServiceInstance... instances) {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("supplier", ServiceInstanceListSuppliers.from("svc", instances));
        return new DifferentInstanceLoadBalancer(factory.getBeanProvider(ServiceInstanceListSupplier.class));
    }

    private static ServiceInstance choose(DifferentInstanceLoadBalancer balancer, ServiceInstance previous) {
        Map<String, Object> attributes = new HashMap<>();
        if (previous != null) attributes.put(GATEWAY_LOADBALANCER_RESPONSE_ATTR, new DefaultResponse(previous));
        var data = new RequestData(HttpMethod.GET, URI.create("http://svc/x"), new HttpHeaders(),
                new LinkedMultiValueMap<>(), attributes);
        Response<ServiceInstance> response =
                balancer.choose(new DefaultRequest<>(new RequestDataContext(data))).block();
        return response.hasServer() ? response.getServer() : null;
    }

    private static ServiceInstance instance(String host, int port) {
        return new DefaultServiceInstance(host + "-id", "svc", host, port, false);
    }
}
