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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 Gateway와 RS256/JWKS·LoadBalancer를 검증합니다. 결제 업무 구현 테스트는 아닙니다. */
@ActiveProfiles("test")
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponRoutingIntegrationTest {
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
        p.add("GATEWAY_COUPON_ENABLED", () -> "true");
        p.add(
                "gateway.auth.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        p.add(
                "spring.cloud.discovery.client.simple.instances.coupon-service[0].uri",
                () -> "http://127.0.0.1:" + PAYMENT.getAddress().getPort());
    }

    @LocalServerPort int port;
    WebTestClient client;

    @BeforeEach
    void prepare() {
        // CI의 첫 요청은 JWKS 조회·HTTP 클라이언트 초기화로 기본 5초 대기를 넘을 수 있어 응답 대기 시간을 늘립니다.
        client =
                WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port)
                        .responseTimeout(Duration.ofSeconds(30))
                        .build();
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
        PAYMENT.stop(0);
    }

    @Test
    void campaignGetIsAnonymousButClaimAndAdminRequireRoles() {
        client.get().uri("/api/v1/coupons/campaigns/CLAIM").exchange().expectStatus().isOk();
        int before = CALLS.get();
        client.post()
                .uri("/api/v1/coupons/campaigns/CLAIM/claims")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get().uri("/api/v1/coupons/me").exchange().expectStatus().isUnauthorized();
        client.get()
                .uri("/api/v1/admin/coupons/definitions")
                .header("Authorization", "Bearer " + token("BUYER"))
                .exchange()
                .expectStatus()
                .isForbidden();
        Assertions.assertEquals(before, CALLS.get());
        client.post()
                .uri("/api/v1/coupons/campaigns/CLAIM/claims")
                .header("Authorization", "Bearer " + token("BUYER"))
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri("/api/v1/admin/coupons/definitions")
                .header("Authorization", "Bearer " + token("ADMIN"))
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri("/internal/v1/coupon-locks")
                .header("Authorization", "Bearer " + token("BUYER"))
                .exchange()
                .expectStatus()
                .isNotFound();
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
