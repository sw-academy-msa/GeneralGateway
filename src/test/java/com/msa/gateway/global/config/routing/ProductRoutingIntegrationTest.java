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
import java.time.Duration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 실제 Gateway HTTP·공개키 검증·LoadBalancer 프록시를 검사하며 업무 API 구현 여부와 구분합니다. */
@ActiveProfiles("test")
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductRoutingIntegrationTest {
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final HttpServer PRODUCT;
    static final HttpServer QUERY;
    static final AtomicInteger QUERY_REQUESTS = new AtomicInteger();
    static final AtomicReference<String> LAST_BEARER = new AtomicReference<>();
    static final AtomicInteger PRODUCT_REQUESTS = new AtomicInteger();
    static {
        try {
            // Account 개인키 대신 테스트 메모리 키를 사용하며 토큰 원문은 출력하지 않습니다.
            KEY = new RSAKeyGenerator(2048).keyID("product-route-test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.start();
            PRODUCT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PRODUCT.createContext("/", exchange -> {
                PRODUCT_REQUESTS.incrementAndGet();
                LAST_BEARER.set(exchange.getRequestHeaders().getFirst("Authorization"));
                boolean cleaned = !exchange.getRequestHeaders().containsKey("Origin")
                        && !exchange.getRequestHeaders().containsKey("X-User-Id")
                        && !exchange.getRequestHeaders().containsKey("X-User-Roles");
                String path = exchange.getRequestURI().toString();
                // 응답은 테스트 fixture 식별값만 포함하고 헤더의 토큰은 반환하지 않습니다.
                String json = "{\"service\":\"product-fixture\",\"path\":\"" + path
                        + "\",\"headersCleaned\":" + cleaned + "}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("X-Request-Id", exchange.getRequestHeaders().getFirst("X-Request-Id"));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            PRODUCT.start();
            QUERY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            QUERY.createContext("/", exchange -> {
                QUERY_REQUESTS.incrementAndGet();
                boolean cleaned = !exchange.getRequestHeaders().containsKey("Origin")
                        && !exchange.getRequestHeaders().containsKey("Cookie");
                byte[] body = ("{\"service\":\"query-fixture\",\"headersCleaned\":" + cleaned + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            QUERY.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.auth.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("spring.cloud.discovery.client.simple.instances.product-query-service[0].uri",
                () -> "http://127.0.0.1:" + QUERY.getAddress().getPort());
        // 테스트는 실제 Eureka 대신 동일한 서비스 이름의 discovery fixture를 사용합니다.
        registry.add("spring.cloud.discovery.client.simple.instances.product-command-service[0].uri",
                () -> "http://127.0.0.1:" + PRODUCT.getAddress().getPort());
    }

    @LocalServerPort int port;
    WebTestClient client;
    // CI의 첫 요청은 JWKS 조회·HTTP 클라이언트 초기화로 기본 5초 대기를 넘을 수 있어 응답 대기 시간을 늘립니다.
    @BeforeEach void prepareClient() { client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(30)).build(); }
    @AfterAll static void stop() { JWKS.stop(0); PRODUCT.stop(0); QUERY.stop(0); LAST_BEARER.set(null); }

    @Test
    void productCommandPathsAreNotExposed() throws Exception {
        // 서비스가 탐색되어도 라우트가 없으면 전달하지 않아야 합니다.
        int before = PRODUCT_REQUESTS.get();
        String token = token();
        for (String path : List.of("/api/v1/seller/products", "/api/v1/seller/products/draft-1?probe=route",
                "/api/v1/admin/product-reviews", "/api/v1/admin/product-reviews/review-1")) {
            client.get().uri(path).headers(headers -> headers.setBearerAuth(token))
                    .exchange().expectStatus().isNotFound();
        }
        Assertions.assertEquals(before, PRODUCT_REQUESTS.get());
    }

    @Test
    void anonymousProductRequestStopsAtGateway() {
        int before = PRODUCT_REQUESTS.get();
        client.get().uri("/api/v1/seller/products").exchange().expectStatus().isUnauthorized();
        Assertions.assertEquals(before, PRODUCT_REQUESTS.get());
    }

    @Test
    void buyerQueryPathUsesQueryInsteadOfCommandRoute() throws Exception {
        int before = PRODUCT_REQUESTS.get();
        String signedToken = token();
        client.get().uri("/api/v1/products").headers(headers -> headers.setBearerAuth(signedToken))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("query-fixture");
        Assertions.assertEquals(before, PRODUCT_REQUESTS.get());
    }

    @Test
    void publicQueryReadsAreAnonymousButWritesAndOwnInquiriesRemainProtected() throws Exception {
        String signedToken = token();
        for (String path : List.of("/api/v1/products", "/api/v1/products/schedule", "/api/v1/products/p1",
                "/api/v1/products/p1/options", "/api/v1/products/p1/faqs", "/api/v1/categories")) {
            client.get().uri(path).header("Origin", "http://localhost:5173").header("Cookie", "fixture-only=1")
                    .exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.service").isEqualTo("query-fixture")
                    .jsonPath("$.headersCleaned").isEqualTo(true);
        }
        int before = QUERY_REQUESTS.get();
        client.get().uri("/api/v1/products/p1/inquiries").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/products").headers(h -> h.setBearerAuth(signedToken))
                .exchange().expectStatus().isForbidden();
        Assertions.assertEquals(before, QUERY_REQUESTS.get());
        client.get().uri("/api/v1/products/p1/inquiries").headers(h -> h.setBearerAuth(signedToken))
                .exchange().expectStatus().isOk();
    }
    private String token() throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("http://localhost:8080").audience("stockdrop-api")
                .subject(UUID.randomUUID().toString()).issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("roles", List.of("BUYER", "SELLER", "ADMIN")).claim("token_use", "access").build();
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        signed.sign(new RSASSASigner(KEY));
        return signed.serialize();
    }
}