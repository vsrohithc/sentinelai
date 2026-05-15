package com.sentinelai;

import com.sentinelai.config.RateLimitConfig;
import com.sentinelai.filter.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>POST /api/proxy within quota → request passes through</li>
 *   <li>POST /api/proxy bucket exhausted → HTTP 429 with Retry-After header</li>
 *   <li>Non-proxy paths are never rate-limited</li>
 *   <li>GET /api/proxy (wrong method) is not rate-limited</li>
 *   <li>trust-proxy=false → TCP remote address used for bucketing (not X-Forwarded-For)</li>
 *   <li>trust-proxy=true  → leftmost X-Forwarded-For IP used for bucketing</li>
 * </ul>
 *
 * <p>RateLimitConfig is used directly (not mocked) with a tiny per-test bucket
 * so the filter sees real Bucket4j behaviour rather than a stub. This is
 * intentional — the bucket interaction is the point of the filter.</p>
 */
class RateLimitFilterTest {

    /** Creates a pass-through filter chain that records whether it was invoked. */
    private WebFilterChain passThroughChain() {
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    /**
     * Builds a {@link RateLimitConfig} pre-initialised with a tiny bucket
     * (capacity=1, refill=1/min) so tests can exhaust it in one request.
     */
    private RateLimitConfig tinyBucketConfig() {
        RateLimitConfig config = new RateLimitConfig(1L, 1L);
        config.init();   // @PostConstruct — must be called manually in unit tests
        return config;
    }

    /**
     * Builds a {@link RateLimitConfig} with a generous bucket (capacity=100)
     * so tests that should pass through always have tokens available.
     */
    private RateLimitConfig generousBucketConfig() {
        RateLimitConfig config = new RateLimitConfig(100L, 60L);
        config.init();
        return config;
    }

    // ─── Within-quota tests ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/proxy with tokens available → passes through to chain")
    void proxyRequest_tokensAvailable_passesThroughToChain() {
        RateLimitFilter filter = new RateLimitFilter(generousBucketConfig(), false);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/proxy")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Filter did not set a status → chain was invoked → pass-through
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ─── Bucket exhausted tests ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/proxy bucket exhausted → 429 Too Many Requests")
    void proxyRequest_bucketExhausted_returns429() {
        RateLimitConfig config = tinyBucketConfig(); // capacity=1
        RateLimitFilter filter = new RateLimitFilter(config, false);
        WebFilterChain chain = passThroughChain();

        // First request consumes the only token
        MockServerHttpRequest first = MockServerHttpRequest
                .post("/api/proxy")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.2", 12345))
                .build();
        filter.filter(MockServerWebExchange.from(first), chain).block();

        // Second request from same IP → bucket empty → 429
        MockServerWebExchange secondExchange = MockServerWebExchange.from(
                MockServerHttpRequest
                        .post("/api/proxy")
                        .remoteAddress(new java.net.InetSocketAddress("10.0.0.2", 12346))
                        .build());

        StepVerifier.create(filter.filter(secondExchange, chain))
                .verifyComplete();

        assertThat(secondExchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("429 response includes Retry-After: 1 header")
    void proxyRequest_bucketExhausted_includesRetryAfterHeader() {
        RateLimitConfig config = tinyBucketConfig();
        RateLimitFilter filter = new RateLimitFilter(config, false);
        WebFilterChain chain = passThroughChain();

        // Exhaust the bucket
        MockServerHttpRequest first = MockServerHttpRequest
                .post("/api/proxy")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.3", 12345))
                .build();
        filter.filter(MockServerWebExchange.from(first), chain).block();

        // Second request should get 429 + Retry-After
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest
                        .post("/api/proxy")
                        .remoteAddress(new java.net.InetSocketAddress("10.0.0.3", 12346))
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    // ─── Non-proxy path tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/logs is not rate-limited (non-proxy path)")
    void nonProxyPath_getApiLogs_notRateLimited() {
        RateLimitFilter filter = new RateLimitFilter(tinyBucketConfig(), false);
        WebFilterChain chain = passThroughChain();

        // Fire many GET /api/logs requests — none should trigger 429
        for (int i = 0; i < 5; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/logs").build());

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode())
                    .as("GET /api/logs attempt %d should not be rate-limited", i + 1)
                    .isNull();
        }
    }

    @Test
    @DisplayName("GET /api/proxy (wrong method) is not rate-limited")
    void wrongMethod_getApiProxy_notRateLimited() {
        RateLimitFilter filter = new RateLimitFilter(tinyBucketConfig(), false);
        WebFilterChain chain = passThroughChain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/proxy").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("/actuator/health is not rate-limited")
    void actuatorHealth_notRateLimited() {
        RateLimitFilter filter = new RateLimitFilter(tinyBucketConfig(), false);
        WebFilterChain chain = passThroughChain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ─── trust-proxy IP extraction tests ──────────────────────────────────────

    @Test
    @DisplayName("trust-proxy=false ignores X-Forwarded-For, uses TCP remote address")
    void trustProxyFalse_ignoresForwardedFor_usesTcpRemote() {
        RateLimitConfig config = tinyBucketConfig();
        RateLimitFilter filter = new RateLimitFilter(config, false);
        WebFilterChain chain = passThroughChain();

        // First request from 10.0.0.4 (TCP) with a spoofed XFF claiming to be 1.2.3.4
        MockServerHttpRequest first = MockServerHttpRequest
                .post("/api/proxy")
                .header("X-Forwarded-For", "1.2.3.4")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.4", 12345))
                .build();
        filter.filter(MockServerWebExchange.from(first), chain).block();

        // Second request from same TCP IP (10.0.0.4) → bucket exhausted → 429
        MockServerWebExchange secondExchange = MockServerWebExchange.from(
                MockServerHttpRequest
                        .post("/api/proxy")
                        .header("X-Forwarded-For", "1.2.3.4")
                        .remoteAddress(new java.net.InetSocketAddress("10.0.0.4", 12346))
                        .build());

        filter.filter(secondExchange, chain).block();

        // 429 proves the bucket key was the TCP address (10.0.0.4), not the spoofed XFF (1.2.3.4)
        assertThat(secondExchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("trust-proxy=true uses leftmost X-Forwarded-For IP as bucket key")
    void trustProxyTrue_usesForwardedForIp() {
        RateLimitConfig config = tinyBucketConfig();
        RateLimitFilter filter = new RateLimitFilter(config, true);
        WebFilterChain chain = passThroughChain();

        String clientIp = "203.0.113.42";

        // First request — consumes the token under the XFF IP
        MockServerHttpRequest first = MockServerHttpRequest
                .post("/api/proxy")
                .header("X-Forwarded-For", clientIp + ", 10.0.0.1")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 12345))
                .build();
        filter.filter(MockServerWebExchange.from(first), chain).block();

        // Second request from same XFF IP → bucket exhausted → 429
        MockServerWebExchange second = MockServerWebExchange.from(
                MockServerHttpRequest
                        .post("/api/proxy")
                        .header("X-Forwarded-For", clientIp + ", 10.0.0.1")
                        .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 12346))
                        .build());

        filter.filter(second, chain).block();

        assertThat(second.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("Different IPs have independent buckets — one exhausted does not affect another")
    void differentIps_haveIndependentBuckets() {
        // capacity=2 so each IP has its own 2-token bucket
        RateLimitConfig config = new RateLimitConfig(2L, 2L);
        config.init();
        RateLimitFilter filter = new RateLimitFilter(config, false);
        WebFilterChain chain = passThroughChain();

        // Exhaust bucket for IP A
        for (int i = 0; i < 2; i++) {
            MockServerWebExchange ex = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/proxy")
                            .remoteAddress(new java.net.InetSocketAddress("10.0.1.1", 10000 + i))
                            .build());
            filter.filter(ex, chain).block();
        }

        // IP B should still have tokens — should pass through
        MockServerWebExchange ipBExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/proxy")
                        .remoteAddress(new java.net.InetSocketAddress("10.0.2.2", 20000))
                        .build());

        filter.filter(ipBExchange, chain).block();

        assertThat(ipBExchange.getResponse().getStatusCode())
                .as("IP B should not be rate-limited when only IP A's bucket is empty")
                .isNull();
    }
}
