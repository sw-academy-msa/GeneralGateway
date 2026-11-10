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
class NotificationRoutingIntegrationTest {
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
                "spring.cloud.discovery.client.simple.instances.notification-service[0].uri",
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
    void allExplicitNotificationRoutesReachDiscoveredService() throws Exception {
        for (String path :
                List.of(
                        "/api/v1/notifications",
                        "/api/v1/notifications/unread-count",
                        "/api/v1/notifications/push-configuration",
                        "/api/v1/notifications/push-subscriptions",
                        "/api/v1/notifications/11111111-1111-1111-1111-111111111111"))
            client.get()
                    .uri(path)
                    .headers(h -> h.setBearerAuth(token("BUYER")))
                    .exchange()
                    .expectStatus()
                    .isOk();
        client.post()
                .uri("/api/v1/notifications/push-subscriptions")
                .headers(h -> h.setBearerAuth(token("BUYER")))
                .exchange()
                .expectStatus()
                .isOk();
        client.patch()
                .uri("/api/v1/notifications/read-all")
                .headers(h -> h.setBearerAuth(token("SELLER")))
                .exchange()
                .expectStatus()
                .isOk();
        client.patch()
                .uri("/api/v1/notifications/11111111-1111-1111-1111-111111111111/read")
                .headers(h -> h.setBearerAuth(token("BUYER")))
                .exchange()
                .expectStatus()
                .isOk();
        client.delete()
                .uri(
                        "/api/v1/notifications/push-subscriptions/11111111-1111-1111-1111-111111111111")
                .headers(h -> h.setBearerAuth(token("BUYER")))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void anonymousAndUnlistedMethodsNeverReachNotification() throws Exception {
        int before = CALLS.get();
        client.get().uri("/api/v1/notifications").exchange().expectStatus().isUnauthorized();
        client.post()
                .uri("/api/v1/notifications")
                .headers(h -> h.setBearerAuth(token("BUYER")))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("/internal/v1/notification-eligibility")
                .headers(h -> h.setBearerAuth(token("BUYER")))
                .exchange()
                .expectStatus()
                .isNotFound();
        org.assertj.core.api.Assertions.assertThat(CALLS.get()).isEqualTo(before);
    }

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
