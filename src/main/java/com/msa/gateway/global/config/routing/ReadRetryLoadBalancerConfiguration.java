package com.msa.gateway.global.config.routing;

import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 서비스별 LoadBalancer 자식 컨텍스트에만 등록되는 구성입니다. 부모 컨텍스트에 함께 등록되지 않도록 @Configuration을 붙이지 않고
 * 다른 구성 클래스의 내부 클래스로 두지 않습니다. 인스턴스 목록은 기존 supplier(운영은 health-check)를 그대로 씁니다.
 */
class ReadRetryLoadBalancerConfiguration {
    @Bean
    ReactorServiceInstanceLoadBalancer differentInstanceLoadBalancer(
            Environment environment, LoadBalancerClientFactory factory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new DifferentInstanceLoadBalancer(
                factory.getLazyProvider(name, ServiceInstanceListSupplier.class));
    }
}
