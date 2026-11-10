package com.msa.gateway.global.config.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.config.EnableWebFlux;

/** 실제 WebFlux 보안 체인이 업무 JWT를 요구하지 않고 전용 토큰만 허용하는지 검증합니다. */
@SpringJUnitConfig(MonitoringSecurityTest.TestConfig.class)
@TestPropertySource(properties = "monitoring.scrape-token=synthetic-monitoring-token-for-test-only")
class MonitoringSecurityTest {
    @Autowired org.springframework.context.ApplicationContext context;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebFlux
    @EnableWebFluxSecurity
    @Import({MonitoringSecurityConfig.class, Endpoint.class})
    static class TestConfig {}

    @RestController
    static class Endpoint {
        @GetMapping("/actuator/prometheus")
        String metrics() {
            return "synthetic_metric 1";
        }
    }

    @Test
    void scrapeChainRequiresDedicatedCredential() {
        var client = WebTestClient.bindToApplicationContext(context).build();
        client.get().uri("/actuator/prometheus").exchange().expectStatus().is4xxClientError();
        client.get()
                .uri("/actuator/prometheus")
                .header("Authorization", "Bearer buyer-jwt")
                .exchange()
                .expectStatus()
                .is4xxClientError();
        client.get()
                .uri("/actuator/prometheus")
                .header("Authorization", "Bearer synthetic-monitoring-token-for-test-only")
                .exchange()
                .expectStatus()
                .isOk();
        client.post()
                .uri("/actuator/prometheus")
                .header("Authorization", "Bearer synthetic-monitoring-token-for-test-only")
                .exchange()
                .expectStatus()
                .is4xxClientError();
    }
}
