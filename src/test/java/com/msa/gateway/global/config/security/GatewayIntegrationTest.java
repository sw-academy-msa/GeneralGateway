package com.msa.gateway.global.config.security;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.time.Duration;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 실제 HTTP 프록시와 RSA 검증을 결합하되 외부 서비스 대신 독립 fixture를 사용합니다. */
@ActiveProfiles("test")
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayIntegrationTest.FailureRoutes.class)
class GatewayIntegrationTest {
    /** 실패를 유도하는 경로는 테스트 컨텍스트에만 등록합니다. */
    @TestConfiguration
    static class FailureRoutes {
        @Bean RouteLocator failureRoutes(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("missing-test", r -> r.path("/test/missing").uri("lb://missing-service"))
                    .route("offline-test", r -> r.path("/test/offline").uri("lb://offline")).build();
        }
    }
    static final RSAKey KEY;
    static final HttpServer ACCOUNT;
    static final HttpServer DEAD;
    static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            DEAD = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int deadPort = DEAD.getAddress().getPort();
            DEAD.start(); DEAD.stop(0);
            System.setProperty("gateway.test.dead-port", String.valueOf(deadPort));
            ACCOUNT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ACCOUNT.createContext("/", exchange -> {
                String path = exchange.getRequestURI().toString();
                REQUESTS.add(path);
                String body = "{\"ok\":true}";
                int status = 200;
                if (path.equals("/.well-known/jwks.json")) body = new JWKSet(KEY.toPublicJWK()).toString();
                else if (path.equals("/api/v1/auth/csrf")) {
                    body = "{\"headerName\":\"X-XSRF-TOKEN\",\"token\":\"fixture-csrf\"}";
                    exchange.getResponseHeaders().add("Set-Cookie", "stockdrop_csrf=fixture-csrf; Path=/; HttpOnly; SameSite=Lax");
                } else if (List.of("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").contains(path)) {
                    // Account 책임인 CSRF 검증의 경계를 흉내 내며 실제 Account 흐름과 구별합니다.
                    boolean csrf = "fixture-csrf".equals(exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN"))
                            && Optional.ofNullable(exchange.getRequestHeaders().getFirst("Cookie")).orElse("").contains("stockdrop_csrf=fixture-csrf");
                    status = csrf ? 200 : 403;
                    if (csrf) exchange.getResponseHeaders().add("Set-Cookie",
                            "stockdrop_refresh=fixture-refresh; Path=/api/v1/auth; HttpOnly; SameSite=Lax"
                                    + (path.endsWith("logout") ? "; Max-Age=0" : ""));
                }
                if (exchange.getRequestHeaders().containsKey("Origin")
                        || exchange.getRequestHeaders().containsKey("X-User-Id")
                        || exchange.getRequestHeaders().containsKey("Forwarded")) status = 500;
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            ACCOUNT.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.auth.jwk-set-uri", () -> "http://127.0.0.1:" + ACCOUNT.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("spring.cloud.discovery.client.simple.instances.account-service[0].uri",
                () -> "http://127.0.0.1:" + ACCOUNT.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.offline[0].uri",
                () -> "http://127.0.0.1:" + System.getProperty("gateway.test.dead-port"));
    }
    @LocalServerPort int port;
    WebTestClient client;
    // CI의 첫 요청은 JWKS 조회·HTTP 클라이언트 초기화로 기본 5초 대기를 넘을 수 있어 응답 대기 시간을 늘립니다.
    @BeforeEach void client() { client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(30)).build(); }
    @AfterAll static void stop() { ACCOUNT.stop(0); }

    @Test void publicRoutePreservesPathAndRemovesUntrustedHeaders() {
        client.get().uri("/api/v1/signup-options?probe=1").header("X-User-Id", "spoof")
                .header("Forwarded", "proto=https;host=evil.example").exchange().expectStatus().isOk()
                .expectHeader().exists("X-Request-Id");
        Assertions.assertTrue(REQUESTS.contains("/api/v1/signup-options?probe=1"));
    }
    @Test void authenticationAndManagementBoundaries() throws Exception {
        String valid = token("http://localhost:8080", "stockdrop-api", "access", 300);
        client.get().uri("/api/v1/accounts/me").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_UNAUTHORIZED").jsonPath("$.traceId").isNotEmpty();
        client.get().uri("/api/v1/accounts/me").headers(h -> h.setBearerAuth("invalid"))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/accounts/me").headers(h -> h.setBearerAuth(valid))
                .exchange().expectStatus().isOk();
        client.get().uri("/actuator/env").headers(h -> h.setBearerAuth(valid))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/unpublished").headers(h -> h.setBearerAuth(valid))
                .exchange().expectStatus().isNotFound();
    }
    @Test void invalidClaimsAreRejected() throws Exception {
        for (String jwt : List.of(token("wrong", "stockdrop-api", "access", 300),
                token("http://localhost:8080", "wrong", "access", 300),
                token("http://localhost:8080", "stockdrop-api", "refresh", 300),
                token("http://localhost:8080", "stockdrop-api", "access", -120),
                token("http://localhost:8080", "stockdrop-api", "access", 0))) {
            client.get().uri("/api/v1/accounts/me").headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isUnauthorized();
        }
    }
    @Test void unavailableInstancesAndConnectionFailure() throws Exception {
        String jwt = token("http://localhost:8080", "stockdrop-api", "access", 300);
        client.get().uri("/test/missing").headers(h -> h.setBearerAuth(jwt)).exchange()
                .expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("GATEWAY_SERVICE_UNAVAILABLE");
        client.get().uri("/test/offline").headers(h -> h.setBearerAuth(jwt)).exchange()
                .expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("GATEWAY_BAD_GATEWAY");
    }
    @Test void corsAndCookieContract() {
        client.options().uri("/api/v1/auth/refresh").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-XSRF-TOKEN")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
        client.options().uri("/api/v1/auth/refresh").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/auth/csrf").header("Origin", "http://localhost:5173").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectCookie().httpOnly("stockdrop_csrf", true).expectCookie().path("stockdrop_csrf", "/");
        for (String endpoint : List.of("login", "refresh", "logout")) {
            client.post().uri("/api/v1/auth/" + endpoint).exchange().expectStatus().isForbidden();
            client.post().uri("/api/v1/auth/" + endpoint).cookie("stockdrop_csrf", "fixture-csrf")
                    .cookie("stockdrop_refresh", "fixture-refresh").header("X-XSRF-TOKEN", "fixture-csrf")
                    .exchange().expectStatus().isOk().expectCookie().httpOnly("stockdrop_refresh", true)
                    .expectCookie().path("stockdrop_refresh", "/api/v1/auth");
        }
    }
    // 테스트에서만 사용하는 독립 키로 서명하며 토큰 원문은 출력하지 않습니다.
    static String token(String issuer, String audience, String use, int seconds) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(UUID.randomUUID().toString())
                .issueTime(new Date()).claim("roles", List.of("BUYER")).claim("token_use", use);
        if (seconds != 0) claims.expirationTime(Date.from(Instant.now().plusSeconds(seconds)));
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
}
