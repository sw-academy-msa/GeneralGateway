package com.msa.gateway.global.config.routing;

import com.msa.gateway.GatewayApplication;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 Gateway와 RS256/JWKS·LoadBalancer를 검증합니다. 결제 업무 구현 테스트는 아닙니다. */
@ActiveProfiles("test")
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiRoutingIntegrationTest {
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final HttpServer PAYMENT;
    static final AtomicInteger CALLS = new AtomicInteger();

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("payment-routing-test").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext(
                    "/jwks",
                    e -> {
                        var body =
                                new JWKSet(KEY.toPublicJWK())
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8);
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(200, body.length);
                        e.getResponseBody().write(body);
                        e.close();
                    });
            JWKS.start();
            PAYMENT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PAYMENT.createContext(
                    "/",
                    e -> {
                        CALLS.incrementAndGet();
                        boolean cleaned =
                                e.getRequestHeaders().getFirst("Authorization") != null
                                        && !e.getRequestHeaders().containsKey("Cookie")
                                        && !e.getRequestHeaders().containsKey("Origin")
                                        && !e.getRequestHeaders().containsKey("X-User-Id");
                        var body =
                                ("{\"fixture\":true,\"headersCleaned\":" + cleaned + "}")
                                        .getBytes(StandardCharsets.UTF_8);
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(200, body.length);
                        e.getResponseBody().write(body);
                        e.close();
                    });
            PAYMENT.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add(
                "gateway.auth.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        p.add(
                "spring.cloud.discovery.client.simple.instances.ai-service[0].uri",
                () -> "http://127.0.0.1:" + PAYMENT.getAddress().getPort());
    }

    @LocalServerPort int port;
    WebTestClient client;

    @BeforeEach
    void prepare() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
        PAYMENT.stop(0);
    }

    @Test
    void anonymousAndWrongRoleNeverReachAi() throws Exception {
        int before = CALLS.get();
        client.get()
                .uri("/api/v1/ai/product-analyses/11111111-1111-1111-1111-111111111111")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/api/v1/ai/product-analyses/11111111-1111-1111-1111-111111111111")
                .headers(h -> h.setBearerAuth(token("BUYER")))
                .exchange()
                .expectStatus()
                .isForbidden();
        // AI 결과 조회 외 메서드는 판매자도 사용할 수 없습니다.
        client.post()
                .uri("/api/v1/ai/product-analyses/11111111-1111-1111-1111-111111111111")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.post()
                .uri("/api/v1/ai/product-analyses/11111111-1111-1111-1111-111111111111")
                .headers(h -> h.setBearerAuth(token("BUYER")))
                .exchange()
                .expectStatus()
                .isForbidden();
        Assertions.assertEquals(before, CALLS.get());
    }

    @Test
    void sellerGetUsesGeneralAiRoute() throws Exception {
        var token = token("SELLER");
        client.get()
                .uri("/api/v1/ai/product-analyses/11111111-1111-1111-1111-111111111111")
                .headers(h -> h.setBearerAuth(token))
                .header("Origin", "http://localhost:5173")
                .header("Cookie", "synthetic=1")
                .header("X-User-Id", "untrusted")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.headersCleaned")
                .isEqualTo(true);
        client.get()
                .uri("/api/v1/ai/product-analyses/11111111-1111-1111-1111-111111111111")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk();
        client.post()
                .uri("/api/v1/ai/product-analyses/11111111-1111-1111-1111-111111111111")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void unsupportedMethodAndInternalPathsAreNotProxied() throws Exception {
        var token = token("SELLER");
        int before = CALLS.get();
        client.delete()
                .uri(
                        "/api/v1/ai/product-analyses/11111111-1111-1111-1111-111111111111/fixture-only")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("/internal/v1/payments")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNotFound();
        Assertions.assertEquals(before, CALLS.get());
    }

    /** 개인정보 수집 참여 경로는 BUYER의 본인 설정용이며 실제 분석 API 대신 HTTP fixture로 라우팅만 검증합니다. */
    @Test
    void buyerAnalyticsRoutesUseGeneralGatewayAndRejectOtherMethods() throws Exception {
        int before = CALLS.get();
        client.get()
                .uri("/api/v1/ai/analytics/identity")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/api/v1/ai/analytics/profile")
                .headers(h -> h.setBearerAuth(token("SELLER")))
                .exchange()
                .expectStatus()
                .isForbidden();
        Assertions.assertEquals(before, CALLS.get());
        var buyer = token("BUYER");
        client.post()
                .uri("/api/v1/ai/analytics/identity")
                .headers(h -> h.setBearerAuth(buyer))
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri("/api/v1/ai/analytics/profile")
                .headers(h -> h.setBearerAuth(buyer))
                .exchange()
                .expectStatus()
                .isOk();
        client.delete()
                .uri("/api/v1/ai/analytics/identity")
                .headers(h -> h.setBearerAuth(buyer))
                .exchange()
                .expectStatus()
                .isOk();
        int after = CALLS.get();
        client.patch()
                .uri("/api/v1/ai/analytics/identity")
                .headers(h -> h.setBearerAuth(buyer))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("/internal/v1/personalization/eligibility")
                .headers(h -> h.setBearerAuth(buyer))
                .exchange()
                .expectStatus()
                .isNotFound();
        Assertions.assertEquals(after, CALLS.get());
    }

    // 테스트 키/토큰은 메모리에서만 사용하며 Account 개인키를 읽지 않습니다.
    private String token(String role) {
        try {
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer("http://localhost:8080")
                            .audience("stockdrop-api")
                            .subject(UUID.randomUUID().toString())
                            .issueTime(new Date())
                            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                            .claim("roles", List.of(role))
                            .claim("token_use", "access")
                            .build();
            var jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                            claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 토큰 생성 실패");
        }
    }
}
