package com.sentinelai;

import com.sentinelai.filter.ApiKeyFilter;
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
 * Unit tests for {@link ApiKeyFilter}.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>No keys configured → auth disabled → all requests pass through</li>
 *   <li>Keys configured, correct key present → request passes through</li>
 *   <li>Keys configured, missing header → 401 Unauthorized</li>
 *   <li>Keys configured, wrong key → 401 Unauthorized</li>
 *   <li>Audit log read endpoints (/api/logs, /api/dashboard, /api/license) are guarded</li>
 *   <li>Health endpoint (/api/health) is unguarded even with keys configured</li>
 * </ul>
 */
class ApiKeyFilterTest {

    private static final String VALID_KEY = "sk-sentinel-test-key";
    private static final String WRONG_KEY  = "sk-sentinel-wrong-key";

    /** Creates a pass-through filter chain mock that records whether it was invoked. */
    private WebFilterChain passThroughChain() {
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    @Test
    @DisplayName("isAuthEnabled() returns false when no keys are configured")
    void authDisabled_whenNoKeysConfigured() {
        ApiKeyFilter filter = new ApiKeyFilter("");
        assertThat(filter.isAuthEnabled()).isFalse();
    }

    @Test
    @DisplayName("isAuthEnabled() returns true when at least one key is configured")
    void authEnabled_whenKeysConfigured() {
        ApiKeyFilter filter = new ApiKeyFilter(VALID_KEY);
        assertThat(filter.isAuthEnabled()).isTrue();
    }

    @Test
    @DisplayName("no keys configured → POST /api/proxy passes through without header")
    void noKeys_proxyRequest_passesThroughWithoutHeader() {
        ApiKeyFilter filter = new ApiKeyFilter("");
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/proxy")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull(); // not set = pass-through
    }

    @Test
    @DisplayName("valid key present → request passes through with 200 (from chain)")
    void validKey_proxyRequest_passesThroughToChain() {
        ApiKeyFilter filter = new ApiKeyFilter(VALID_KEY);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/proxy")
                .header(ApiKeyFilter.API_KEY_HEADER, VALID_KEY)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Chain was invoked, filter did not set an error status
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("missing API key header → 401 Unauthorized")
    void missingKey_proxyRequest_returns401() {
        ApiKeyFilter filter = new ApiKeyFilter(VALID_KEY);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/proxy")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("wrong API key value → 401 Unauthorized")
    void wrongKey_proxyRequest_returns401() {
        ApiKeyFilter filter = new ApiKeyFilter(VALID_KEY);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/proxy")
                .header(ApiKeyFilter.API_KEY_HEADER, WRONG_KEY)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("audit log read endpoints are guarded — GET /api/logs without key → 401")
    void readEndpoint_logs_missingKey_returns401() {
        ApiKeyFilter filter = new ApiKeyFilter(VALID_KEY);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/logs").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("audit log read endpoints accept a valid key — GET /api/logs with key passes through")
    void readEndpoint_logs_validKey_passesThrough() {
        ApiKeyFilter filter = new ApiKeyFilter(VALID_KEY);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/logs/00000000-0000-0000-0000-000000000000")
                .header(ApiKeyFilter.API_KEY_HEADER, VALID_KEY)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("dashboard endpoint is guarded — GET /api/dashboard/stats without key → 401")
    void readEndpoint_dashboard_missingKey_returns401() {
        ApiKeyFilter filter = new ApiKeyFilter(VALID_KEY);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/dashboard/stats").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("license endpoint is guarded — GET /api/license/info without key → 401")
    void readEndpoint_license_missingKey_returns401() {
        ApiKeyFilter filter = new ApiKeyFilter(VALID_KEY);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/license/info").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("health endpoint is NEVER guarded — GET /api/health passes through even with keys")
    void healthEndpoint_neverChallenged() {
        ApiKeyFilter filter = new ApiKeyFilter(VALID_KEY);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // No auth check → status not touched. Health must remain reachable
        // by load balancers and uptime probes without credentials.
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("when auth is DISABLED, guarded read endpoints still pass through")
    void noKeys_readEndpoints_passThrough() {
        ApiKeyFilter filter = new ApiKeyFilter("");   // auth disabled
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/logs").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("multiple configured keys — any one of them is accepted")
    void multipleKeys_anyValidKeyAccepted() {
        String key1 = "sk-sentinel-key-one";
        String key2 = "sk-sentinel-key-two";
        ApiKeyFilter filter = new ApiKeyFilter(key1 + "," + key2);
        WebFilterChain chain = passThroughChain();

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/proxy")
                .header(ApiKeyFilter.API_KEY_HEADER, key2)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
