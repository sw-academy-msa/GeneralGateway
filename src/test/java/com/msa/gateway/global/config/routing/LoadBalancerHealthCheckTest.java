package com.msa.gateway.global.config.routing;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * LoadBalancer 상태 확인(health-check)은 컨텍스트의 WebClient.Builder로 각 인스턴스를 호출합니다. Builder가 없으면 오류 없이 모든
 * 인스턴스가 제외되어 전 라우트가 503이 되므로, Boot 4의 WebClient 자동 구성이 포함되어 있는지 확인합니다.
 */
class LoadBalancerHealthCheckTest {
    @Test
    void webClientBuilderIsAvailableForHealthChecks() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebClientAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(WebClient.Builder.class));
    }
}
