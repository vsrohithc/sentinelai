package com.sentinelai;

import com.sentinelai.filter.RequestContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RequestContextFilter}.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Caller-supplied {@code X-Request-Id} is echoed as the response header</li>
 *   <li>When no header is supplied a UUID is generated and set as the response header</li>
 *   <li>Request ID is stored in the exchange attribute {@link RequestContextFilter#REQUEST_ID_ATTR}</li>
 *   <li>MDC is populated with {@code request_id} during chain execution</li>
 *   <li>MDC is cleared after the chain completes (no leakage)</li>
 *   <li>Blank {@code X-Request-Id} triggers UUID generation (not stored as blank)</li>
 * </ul>
 *
 * <p>Note: The reactive MDC propagation via {@code Hooks.enableAutomaticContextPropagation()}
 * is a JVM-wide hook tested in integration. These unit tests verify the synchronous
 * MDC behaviour within the filter's own execution thread.</p>
 */
class RequestContextFilterTest {

    private final RequestContextFilter filter = new RequestContextFilter();

    /** Clean up MDC after each test — defensive guard for test isolation. */
    @AfterEach
    void clearMdc() {
        MDC.remove(RequestContextFilter.MDC_REQUEST_ID);
    }

    /** Builds a pass-through chain that records MDC state at chain-invocation time. */
    private WebFilterChain chainThatCapturesMdc(AtomicReference<String> mdcCapture) {
        return exchange -> {
            // Capture MDC value at the moment the chain is called
            mdcCapture.set(MDC.get(RequestContextFilter.MDC_REQUEST_ID));
            return Mono.empty();
        };
    }

    private WebFilterChain passThroughChain() {
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    // ─── X-Request-Id header honour ───────────────────────────────────────────

    @Test
    @DisplayName("Caller-supplied X-Request-Id is echoed in the response header")
    void callerSuppliedRequestId_isEchoedInResponse() {
        String callerRequestId = "my-trace-id-abc123";

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/proxy")
                .header("X-Request-Id", callerRequestId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id"))
                .isEqualTo(callerRequestId);
    }

    @Test
    @DisplayName("Caller-supplied X-Request-Id is stored in exchange attribute")
    void callerSuppliedRequestId_isStoredInExchangeAttribute() {
        String callerRequestId = "my-trace-id-xyz789";

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/logs")
                .header("X-Request-Id", callerRequestId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, passThroughChain()).block();

        assertThat(exchange.<String>getAttribute(RequestContextFilter.REQUEST_ID_ATTR))
                .isEqualTo(callerRequestId);
    }

    // ─── UUID generation when no header ──────────────────────────────────────

    @Test
    @DisplayName("No X-Request-Id header → UUID is generated and set in response")
    void noHeader_uuidGeneratedAndSetInResponse() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/proxy")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, passThroughChain()).block();

        String responseId = exchange.getResponse().getHeaders().getFirst("X-Request-Id");
        assertThat(responseId)
                .isNotNull()
                .isNotBlank()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("No X-Request-Id header → generated UUID stored in exchange attribute")
    void noHeader_generatedUuidStoredInExchangeAttribute() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/health").build());

        filter.filter(exchange, passThroughChain()).block();

        String attrId = exchange.getAttribute(RequestContextFilter.REQUEST_ID_ATTR);
        String responseId = exchange.getResponse().getHeaders().getFirst("X-Request-Id");

        // Both should be the same UUID
        assertThat(attrId).isNotNull().isEqualTo(responseId);
    }

    @Test
    @DisplayName("Blank X-Request-Id header triggers UUID generation")
    void blankHeader_triggersUuidGeneration() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/proxy")
                        .header("X-Request-Id", "   ")
                        .build());

        filter.filter(exchange, passThroughChain()).block();

        String responseId = exchange.getResponse().getHeaders().getFirst("X-Request-Id");
        // Must not be blank — a fresh UUID should have been generated
        assertThat(responseId).isNotBlank();
        // The blank value must not have been echoed back
        assertThat(responseId.trim()).isNotEmpty();
        assertThat(responseId).doesNotContain("   ");
    }

    // ─── MDC population ───────────────────────────────────────────────────────

    @Test
    @DisplayName("MDC request_id is populated during chain execution")
    void mdcPopulated_duringChainExecution() {
        String callerRequestId = "mdc-test-id-001";
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/proxy")
                        .header("X-Request-Id", callerRequestId)
                        .build());

        filter.filter(exchange, chainThatCapturesMdc(mdcDuringChain)).block();

        assertThat(mdcDuringChain.get())
                .as("MDC request_id should be set while chain executes")
                .isEqualTo(callerRequestId);
    }

    @Test
    @DisplayName("MDC request_id is populated with generated UUID when no header supplied")
    void mdcPopulated_withGeneratedUuid_whenNoHeader() {
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/logs").build());

        filter.filter(exchange, chainThatCapturesMdc(mdcDuringChain)).block();

        String mdcValue = mdcDuringChain.get();
        assertThat(mdcValue)
                .isNotNull()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        // The same UUID should be in the exchange attribute and response header
        assertThat(exchange.<String>getAttribute(RequestContextFilter.REQUEST_ID_ATTR))
                .isEqualTo(mdcValue);
    }

    // ─── MDC cleanup ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("MDC request_id is cleared after chain completes (no leakage)")
    void mdcCleared_afterChainCompletes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/proxy").build());

        // Precondition: MDC is empty before the filter runs
        assertThat(MDC.get(RequestContextFilter.MDC_REQUEST_ID)).isNull();

        filter.filter(exchange, passThroughChain()).block();

        // Post-condition: MDC must be cleared — no leakage to next request
        assertThat(MDC.get(RequestContextFilter.MDC_REQUEST_ID))
                .as("MDC request_id must be null after chain completes")
                .isNull();
    }

    @Test
    @DisplayName("MDC request_id is cleared even when chain throws an error")
    void mdcCleared_evenWhenChainErrors() {
        WebFilterChain errorChain = exchange ->
                Mono.error(new RuntimeException("Simulated downstream error"));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/proxy").build());

        // The filter's Mono will emit an error — that's fine, we just want to
        // verify MDC is cleaned up via doFinally regardless.
        StepVerifier.create(filter.filter(exchange, errorChain))
                .expectError(RuntimeException.class)
                .verify();

        assertThat(MDC.get(RequestContextFilter.MDC_REQUEST_ID))
                .as("MDC must be cleared even after a chain error")
                .isNull();
    }

    // ─── Response header always set ───────────────────────────────────────────

    @Test
    @DisplayName("X-Request-Id response header is always set, regardless of path")
    void responseHeaderAlwaysSet_forAllPaths() {
        for (String path : new String[]{"/api/proxy", "/api/logs", "/actuator/health", "/unknown/path"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path).build());

            filter.filter(exchange, passThroughChain()).block();

            assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id"))
                    .as("X-Request-Id header must be set for path: " + path)
                    .isNotBlank();
        }
    }
}
