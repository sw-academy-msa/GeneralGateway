package com.msa.gateway.global.config.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.msa.gateway.GatewayApplication;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 실제 Gateway에서 조회만 다른 인스턴스로 1회 재시도하는지 검증합니다. 각 서비스의 0번 인스턴스가 실패하는 fixture이고, 라운드 로빈이라
 * 연속 요청의 절반은 실패 인스턴스를 먼저 고릅니다. 쓰기 요청은 실패 인스턴스를 고르면 그대로 실패해야 합니다.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SafeReadRetryIntegrationTest {
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final int DEAD_PORT;
    static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    static final List<HttpServer> SERVERS = new ArrayList<>();

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("retry-test").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", e -> {
                var body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type", "application/json");
                e.sendResponseHeaders(200, body.length);
                e.getResponseBody().write(body);
                e.close();
            });
            JWKS.start();
            // 포트를 잡았다가 닫아 연결 거절되는 주소를 만듭니다.
            try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
                DEAD_PORT = socket.getLocalPort();
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** 이름별 호출 수를 세고 지정 상태로 응답하는 fixture입니다. */
    static String server(String name, int status, long delayMillis) {
        try {
            CALLS.put(name, new AtomicInteger());
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", e -> {
                CALLS.get(name).incrementAndGet();
                try {
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if ("HEAD".equals(e.getRequestMethod())) {
                    e.sendResponseHeaders(status, -1);
                } else {
                    var body = ("{\"server\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(status, body.length);
                    e.getResponseBody().write(body);
                }
                e.close();
            });
            server.start();
            SERVERS.add(server);
            return "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String dead() {
        return "http://127.0.0.1:" + DEAD_PORT;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        String prefix = "spring.cloud.discovery.client.simple.instances.";
        p.add("gateway.auth.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        // 응답 시간 초과 무재시도를 빠르게 확인하기 위해 이 테스트에서만 줄입니다.
        p.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "1s");
        p.add(prefix + "notification-service[0].uri", SafeReadRetryIntegrationTest::dead);
        p.add(prefix + "notification-service[1].uri", server("notification-ok", 200, 0)::toString);
        p.add(prefix + "account-service[0].uri", SafeReadRetryIntegrationTest::dead);
        p.add(prefix + "account-service[1].uri", server("account-ok", 200, 0)::toString);
        p.add(prefix + "ai-service[0].uri", server("ai-503", 503, 0)::toString);
        p.add(prefix + "ai-service[1].uri", server("ai-ok", 200, 0)::toString);
        p.add(prefix + "order-query-service[0].uri", server("order-500", 500, 0)::toString);
        p.add(prefix + "order-query-service[1].uri", server("order-ok", 200, 0)::toString);
        p.add(prefix + "product-query-service[0].uri", server("product-503-only", 503, 0)::toString);
        p.add(prefix + "payment-service[0].uri", server("payment-slow", 200, 2000)::toString);
        p.add(prefix + "payment-service[1].uri", server("payment-ok", 200, 0)::toString);
    }

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired LoadBalancerClientFactory balancers;
    WebTestClient client;

    @BeforeEach
    void prepare() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(30)).build();
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
        SERVERS.forEach(s -> s.stop(0));
    }

    static int calls(String name) {
        return CALLS.get(name).get();
    }

    @Test
    void loadBalancerIsRegisteredOnlyInServiceContexts() {
        assertThat(context.getBeanNamesForType(ReactorServiceInstanceLoadBalancer.class)).isEmpty();
        assertThat(balancers.getInstance("notification-service")).isInstanceOf(DifferentInstanceLoadBalancer.class);
    }

    @Test
    void getFailsOverFromRefusedConnection() {
        int before = calls("notification-ok");
        for (int i = 0; i < 6; i++) {
            client.get().uri("/api/v1/notifications").headers(h -> h.setBearerAuth(token("BUYER")))
                    .exchange().expectStatus().isOk();
        }
        assertThat(calls("notification-ok") - before).isEqualTo(6);
    }

    @Test
    void headFailsOverFromRefusedConnection() {
        int before = calls("account-ok");
        for (int i = 0; i < 4; i++) {
            client.head().uri("/api/v1/accounts/me").headers(h -> h.setBearerAuth(token("BUYER")))
                    .exchange().expectStatus().isOk();
        }
        assertThat(calls("account-ok") - before).isEqualTo(4);
    }

    @Test
    void getFailsOverFrom503ToAnotherInstance() {
        int failed = calls("ai-503");
        int ok = calls("ai-ok");
        for (int i = 0; i < 6; i++) {
            client.get().uri("/api/v1/ai/analytics/profile").headers(h -> h.setBearerAuth(token("BUYER")))
                    .exchange().expectStatus().isOk();
        }
        assertThat(calls("ai-ok") - ok).isEqualTo(6);
        assertThat(calls("ai-503") - failed).isBetween(1, 6);
    }

    @Test
    void concurrentGetsNeverRetryOnTheSameInstance() throws Exception {
        int before = calls("notification-ok");
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                results.add(pool.submit(() -> client.get().uri("/api/v1/notifications")
                        .headers(h -> h.setBearerAuth(token("BUYER"))).exchange()
                        .returnResult(String.class).getStatus().value()));
            }
            for (var result : results) assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(calls("notification-ok") - before).isEqualTo(24);
    }

    @Test
    void writeMethodsAreNeverRetried() {
        record Write(HttpMethod method, String path, String role) {}
        var writes = List.of(
                new Write(HttpMethod.POST, "/api/v1/notifications/push-subscriptions", "BUYER"),
                new Write(HttpMethod.PATCH, "/api/v1/notifications/read-all", "BUYER"),
                new Write(HttpMethod.PUT, "/api/v1/notifications/product-alerts/11111111-1111-1111-1111-111111111111", "BUYER"),
                new Write(HttpMethod.DELETE, "/api/v1/notifications/push-subscriptions/11111111-1111-1111-1111-111111111111", "BUYER"));
        for (Write write : writes) {
            int before = calls("notification-ok");
            int failures = 0;
            // 라운드 로빈이므로 연속 두 번 중 한 번은 연결 거절 인스턴스를 고릅니다.
            for (int i = 0; i < 2; i++) {
                int status = client.method(write.method()).uri(write.path())
                        .headers(h -> h.setBearerAuth(token(write.role()))).exchange()
                        .returnResult(String.class).getStatus().value();
                if (status != 200) {
                    assertThat(status).isEqualTo(502);
                    failures++;
                }
            }
            assertThat(failures).as(write.method().name()).isEqualTo(1);
            assertThat(calls("notification-ok") - before).as(write.method().name()).isEqualTo(1);
        }
    }

    @Test
    void writeIsNotRetriedOn503() {
        int failed = calls("ai-503");
        int ok = calls("ai-ok");
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            statuses.add(client.delete().uri("/api/v1/ai/analytics/identity")
                    .headers(h -> h.setBearerAuth(token("BUYER"))).exchange()
                    .returnResult(String.class).getStatus().value());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 503);
        assertThat(calls("ai-503") - failed).isEqualTo(1);
        assertThat(calls("ai-ok") - ok).isEqualTo(1);
    }

    @Test
    void getIsNotRetriedOn500() {
        int failed = calls("order-500");
        int ok = calls("order-ok");
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            statuses.add(client.get().uri("/api/v1/orders").headers(h -> h.setBearerAuth(token("BUYER")))
                    .exchange().returnResult(String.class).getStatus().value());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 500);
        assertThat(calls("order-500") - failed).isEqualTo(1);
        assertThat(calls("order-ok") - ok).isEqualTo(1);
    }

    @Test
    void getIsNotRetriedOnResponseTimeout() {
        int slow = calls("payment-slow");
        int ok = calls("payment-ok");
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            statuses.add(client.get().uri("/api/v1/payments").headers(h -> h.setBearerAuth(token("BUYER")))
                    .exchange().returnResult(String.class).getStatus().value());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 504);
        assertThat(calls("payment-slow") - slow).isEqualTo(1);
        assertThat(calls("payment-ok") - ok).isEqualTo(1);
    }

    @Test
    void getWithoutAlternativeIsNotResentToTheSameInstance() {
        int before = calls("product-503-only");
        client.get().uri("/api/v1/products").exchange().expectStatus().isEqualTo(503);
        assertThat(calls("product-503-only") - before).isEqualTo(1);
    }

    private String token(String role) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .issuer("http://localhost:8080")
                    .audience("stockdrop-api")
                    .subject(UUID.randomUUID().toString())
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("roles", List.of(role))
                    .claim("token_use", "access")
                    .build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 토큰 생성 실패");
        }
    }
}
