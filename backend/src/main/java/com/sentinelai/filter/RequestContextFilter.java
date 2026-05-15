package com.sentinelai.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * WebFilter that assigns a unique request ID to every inbound HTTP request
 * and makes it available for the lifetime of that request in both:
 * <ol>
 *   <li>The SLF4J {@link MDC} — so every log line within the request carries
 *       {@code "request_id"} as a structured field (picked up by
 *       logstash-logback-encoder in production).</li>
 *   <li>The Spring {@link ServerWebExchange} attributes — so controllers and
 *       services can read the ID without going through MDC.</li>
 * </ol>
 *
 * <h2>Filter ordering</h2>
 * <p>This filter runs at {@link Integer#MIN_VALUE} — the highest possible
 * priority — so the request ID is set before any other filter (including
 * {@link ApiKeyFilter} and {@link RateLimitFilter}) logs anything.
 * This ensures that rejection log lines (401, 429) also carry the request ID.</p>
 *
 * <h2>MDC propagation across reactive threads</h2>
 * <p>MDC is stored in a {@link ThreadLocal}. In a reactive pipeline, execution
 * can hop between threads (e.g. from the Netty I/O thread to
 * {@code boundedElastic} for JDBC calls). Without special handling, the MDC
 * context would be lost after the first thread hop.</p>
 *
 * <p>SentinelAI calls {@code Hooks.enableAutomaticContextPropagation()} at
 * startup ({@link com.sentinelai.SentinelAIApplication#main}). This reactor-core
 * hook bridges the Reactor {@code Context} with ThreadLocals: any ThreadLocal
 * value (including MDC) set before the chain is subscribed is captured and
 * restored whenever the chain's thread changes. The combination of this filter
 * + the startup hook is what makes log correlation work end-to-end in a
 * WebFlux application.</p>
 *
 * <h2>MDC cleanup</h2>
 * <p>MDC is cleared in a {@code doFinally()} callback that runs regardless of
 * how the Mono terminates (completion, error, or cancellation). This prevents
 * request-ID values from leaking into the next request served by the same
 * thread in the Netty worker pool.</p>
 *
 * <h2>Exchange attribute key</h2>
 * <p>The request ID is also stored under {@link #REQUEST_ID_ATTR} in the
 * exchange attributes so downstream filters and controllers can retrieve it
 * without coupling to MDC:</p>
 * <pre>{@code
 * String id = exchange.getAttribute(RequestContextFilter.REQUEST_ID_ATTR);
 * }</pre>
 */
@Slf4j
@Component
@Order(Integer.MIN_VALUE)
public class RequestContextFilter implements WebFilter {

    /** MDC key for the per-request correlation ID. */
    public static final String MDC_REQUEST_ID = "request_id";

    /**
     * Exchange attribute key under which the request ID is stored.
     * Allows non-MDC access from controllers and downstream filters.
     */
    public static final String REQUEST_ID_ATTR = "sentinelai.request_id";

    /**
     * Assigns a request ID, populates MDC, and delegates to the next filter.
     *
     * <p>The request ID is either read from an inbound {@code X-Request-Id}
     * header (allowing the caller to supply a correlation ID from their own
     * system) or generated as a fresh UUID. This lets end-to-end tracing work
     * across service boundaries when the upstream propagates the header.</p>
     *
     * @param exchange the current server exchange
     * @param chain    the remaining filter chain
     * @return a {@link Mono} that completes when the exchange is handled
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Honour caller-supplied request ID for cross-service tracing;
        // fall back to a fresh UUID when absent.
        String requestId = exchange.getRequest().getHeaders()
                .getFirst("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        // Store in exchange so controllers can retrieve it without MDC
        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);

        // Propagate request ID back to the caller in the response header
        // so they can correlate their logs with ours.
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);

        final String finalRequestId = requestId;

        // Set MDC before the chain executes.
        // With Hooks.enableAutomaticContextPropagation() active at startup,
        // this MDC value is automatically carried across reactive thread hops.
        MDC.put(MDC_REQUEST_ID, finalRequestId);

        return chain.filter(exchange)
                .doFinally(signal -> {
                    // Always clear MDC to prevent value leakage to the next
                    // request on the same thread.
                    MDC.remove(MDC_REQUEST_ID);
                });
    }
}
