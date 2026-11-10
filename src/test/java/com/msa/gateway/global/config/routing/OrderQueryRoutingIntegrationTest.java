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
import org.springframework.http.HttpMethod;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 일반 Gateway/JWKS/LoadBalancer로 Query GET과 PurchaseGateway Command 경계만 검증합니다. */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderQueryRoutingIntegrationTest {
    static final RSAKey KEY;
    static final HttpServer JWKS, QUERY;
    static final AtomicInteger CALLS = new AtomicInteger();

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("order-query-routing-test").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext(
                    "/jwks",
                    exchange -> {
                        try (exchange) {
                            byte[] payload =
                                    new JWKSet(KEY.toPublicJWK())
                                            .toString()
                                            .getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, payload.length);
                            exchange.getResponseBody().write(payload);
                        }
                    });
            JWKS.start();
            QUERY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            QUERY.createContext(
                    "/",
                    exchange -> {
                        try (exchange) {
                            CALLS.incrementAndGet();
                            boolean clean =
                                    exchange.getRequestHeaders().getFirst("Authorization") != null
                                            && !exchange.getRequestHeaders().containsKey("Origin")
                                            && !exchange.getRequestHeaders().containsKey("Cookie")
                                            && !exchange.getRequestHeaders()
                                                    .containsKey("X-User-Id");
                            byte[] payload =
                                    ("{\"service\":\"order-query-fixture\",\"clean\":"
                                                    + clean
                                                    + "}")
                                            .getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, payload.length);
                            exchange.getResponseBody().write(payload);
                        }
                    });
            QUERY.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "gateway.auth.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        registry.add(
                "spring.cloud.discovery.client.simple.instances.order-query-service[0].uri",
                () -> "http://127.0.0.1:" + QUERY.getAddress().getPort());
    }

    @AfterAll
    static void closeFixtures() {
        JWKS.stop(0);
        QUERY.stop(0);
    }

    @LocalServerPort int port;
    WebTestClient client;

    @BeforeEach
    void prepare() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** summary 정적 경로도 SELLER 전용 Query 라우트로 전달되어야 합니다. */
    @Test
    void sellerSummaryIsRoutedAndBuyerIsDenied() throws Exception {
        String sellerToken = token("SELLER");
        String buyerToken = token("BUYER");
        client.get()
                .uri("/api/v1/seller/orders/summary")
                .headers(h -> h.setBearerAuth(sellerToken))
                .exchange()
                .expectStatus()
                .isOk();
        int before = CALLS.get();
        client.get()
                .uri("/api/v1/seller/orders/summary")
                .headers(h -> h.setBearerAuth(buyerToken))
                .exchange()
                .expectStatus()
                .isForbidden();
        Assertions.assertEquals(before, CALLS.get());
    }

    @Test
    void buyerAndSellerReadPathsUseGeneralQueryRoute() throws Exception {
        for (String role : List.of("BUYER", "SELLER")) {
            String base = role.equals("BUYER") ? "/api/v1/orders" : "/api/v1/seller/orders";
            String token = token(role);
            for (String suffix :
                    List.of(
                            "",
                            "/inquiries",
                            "/order-1",
                            "/order-1/inquiries",
                            "/order-1/inquiries/inquiry-1")) {
                client.get()
                        .uri(base + suffix)
                        .headers(headers -> headers.setBearerAuth(token))
                        .header("Origin", "http://localhost:5173")
                        .header("Cookie", "synthetic=1")
                        .header("X-User-Id", "forged")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.service")
                        .isEqualTo("order-query-fixture")
                        .jsonPath("$.clean")
                        .isEqualTo(true);
            }
        }
    }

    @Test
    void anonymousAndWrongRoleRequestsNeverReachQuery() throws Exception {
        int before = CALLS.get();
        String buyer = token("BUYER"), seller = token("SELLER");
        client.get().uri("/api/v1/orders").exchange().expectStatus().isUnauthorized();
        client.get()
                .uri("/api/v1/orders")
                .headers(h -> h.setBearerAuth(seller))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("/api/v1/seller/orders")
                .headers(h -> h.setBearerAuth(buyer))
                .exchange()
                .expectStatus()
                .isForbidden();
        Assertions.assertEquals(before, CALLS.get());
    }

    @Test
    void checkoutEditingAndMutationsRemainInPurchaseGateway() throws Exception {
        int before = CALLS.get();
        String buyer = token("BUYER"), seller = token("SELLER");
        for (String path :
                List.of(
                        "/api/v1/orders/order-1/checkout",
                        "/api/v1/orders/order-1/inquiries/inquiry-1/editing",
                        "/api/v1/orders/order-1/inquiry-images/image-1/download")) {
            client.get()
                    .uri(path)
                    .headers(h -> h.setBearerAuth(buyer))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
        client.get()
                .uri("/api/v1/seller/orders/order-1/fulfillment")
                .headers(h -> h.setBearerAuth(seller))
                .exchange()
                .expectStatus()
                .isForbidden();
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE)) {
            client.method(method)
                    .uri("/api/v1/orders")
                    .headers(h -> h.setBearerAuth(buyer))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
        Assertions.assertEquals(before, CALLS.get());
    }

    private String token(String role) throws Exception {
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
        var signed =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                        claims);
        signed.sign(new RSASSASigner(KEY));
        return signed.serialize();
    }
}
