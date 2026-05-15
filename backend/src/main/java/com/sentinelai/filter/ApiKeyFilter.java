package com.sentinelai.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WebFilter that validates the {@code X-Sentinel-Api-Key} header on every
 * {@code POST /api/proxy} request.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>If no API keys are configured ({@code sentinel.api-keys} is blank),
 *       authentication is <em>disabled</em> and all requests pass through.
 *       This is the default out-of-the-box experience.</li>
 *   <li>When at least one key is configured, every {@code POST /api/proxy}
 *       request <em>must</em> include a matching {@code X-Sentinel-Api-Key}
 *       header. A missing or unrecognised key is rejected with
 *       {@code HTTP 401 Unauthorized}.</li>
 * </ul>
 *
 * <h2>Key configuration</h2>
 * <p>API keys are provided as a comma-separated environment variable:</p>
 * <pre>
 * SENTINEL_API_KEYS=sk-sentinel-abc123,sk-sentinel-def456
 * </pre>
 * <p>which maps to {@code sentinel.api-keys} in {@code application.yml}:</p>
 * <pre>
 * sentinel:
 *   api-keys: ${SENTINEL_API_KEYS:}
 * </pre>
 * <p>Whitespace around each key is stripped, and empty tokens (e.g. a
 * trailing comma) are ignored.</p>
 *
 * <h2>Filter ordering</h2>
 * <p>This filter runs at priority {@code HIGHEST_PRECEDENCE + 10}, before
 * {@link RateLimitFilter} ({@code HIGHEST_PRECEDENCE + 20}). Authenticating
 * first means unauthenticated callers receive {@code 401} and never consume
 * a rate-limit token.</p>
 *
 * <h2>Scope</h2>
 * <p>Only {@code POST /api/proxy} is guarded. Dashboard, health, actuator,
 * and license-info endpoints remain publicly accessible.</p>
 */
@Slf4j
@Component
@Order(Integer.MIN_VALUE + 10)
public class ApiKeyFilter implements WebFilter {

    /** Header name that callers must include to authenticate. */
    public static final String API_KEY_HEADER = "X-Sentinel-Api-Key";

    /** Proxy path — always guarded when auth is enabled. */
    private static final String PROXY_PATH = "/api/proxy";

    /**
     * Read endpoints that are guarded when auth is enabled.
     *
     * <p>These return audit data (full prompt + response text, license info,
     * dashboard statistics). Leaving them unauthenticated allows anyone able
     * to reach the backend to exfiltrate every persisted prompt — a far worse
     * outcome than exposing the proxy endpoint, which only forwards traffic to
     * the upstream provider that the caller's API key already grants access to.</p>
     *
     * <p>Path prefixes are checked with {@code startsWith()} so child paths
     * like {@code /api/logs/{id}} are covered automatically.</p>
     *
     * <p>Explicitly NOT guarded:</p>
     * <ul>
     *   <li>{@code /api/health}        — load balancers and probes need access without keys</li>
     *   <li>{@code /actuator/**}       — operational endpoints, guarded by network policy</li>
     * </ul>
     */
    private static final List<String> GUARDED_READ_PREFIXES = List.of(
            "/api/logs",
            "/api/dashboard",
            "/api/license"
    );

    /** JSON error body returned when the key is missing. */
    private static final byte[] MISSING_KEY_BODY =
            ("{\"error\":\"unauthorized\","
            + "\"message\":\"Missing " + API_KEY_HEADER + " header.\"}")
            .getBytes(StandardCharsets.UTF_8);

    /** JSON error body returned when the key is present but not recognised. */
    private static final byte[] INVALID_KEY_BODY =
            ("{\"error\":\"unauthorized\","
            + "\"message\":\"Invalid API key.\"}")
            .getBytes(StandardCharsets.UTF_8);

    /**
     * Immutable set of valid API keys parsed from the
     * {@code sentinel.api-keys} configuration property.
     *
     * <p>Empty set when no keys are configured — authentication is then
     * disabled and all requests pass through.</p>
     */
    private final Set<String> validKeys;

    /**
     * Constructs the filter and parses the comma-separated API key list.
     *
     * @param rawApiKeys the raw value of {@code sentinel.api-keys} (may be
     *                   blank when no keys are configured)
     */
    public ApiKeyFilter(@Value("${sentinel.api-keys:}") String rawApiKeys) {
        this.validKeys = Arrays.stream(rawApiKeys.split(","))
                .map(String::trim)
                .filter(k -> !k.isBlank())
                .collect(Collectors.toUnmodifiableSet());

        if (validKeys.isEmpty()) {
            log.warn("sentinel.api-keys is not configured — API key authentication "
                    + "is DISABLED. Set SENTINEL_API_KEYS to enable it.");
        } else {
            log.info("API key authentication enabled with {} configured key(s).",
                    validKeys.size());
        }
    }

    /**
     * Validates the {@code X-Sentinel-Api-Key} header on {@code POST /api/proxy}.
     *
     * <p>All other paths are passed through without inspection.</p>
     *
     * @param exchange the current server exchange (request + response)
     * @param chain    the remaining filter chain
     * @return a {@link Mono} that completes when the exchange is fully handled
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Only guard endpoints that handle audit data or accept proxy traffic
        if (!isGuardedRequest(exchange)) {
            return chain.filter(exchange);
        }

        // If no keys are configured, authentication is disabled — pass through
        if (validKeys.isEmpty()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Rejected request to {} — missing {} header",
                    path, API_KEY_HEADER);
            return rejectWithUnauthorized(exchange, MISSING_KEY_BODY);
        }

        if (!validKeys.contains(apiKey.trim())) {
            // Do NOT log the key value — it would appear in plaintext in log files
            log.warn("Rejected request to {} — unrecognised API key (length={})",
                    path, apiKey.length());
            return rejectWithUnauthorized(exchange, INVALID_KEY_BODY);
        }

        log.debug("API key validated for request to {}", path);
        return chain.filter(exchange);
    }

    /**
     * Returns {@code true} if the current request targets a path that
     * {@link ApiKeyFilter} guards.
     *
     * <p>Guards:</p>
     * <ul>
     *   <li>{@code POST /api/proxy}            — the prompt-forwarding endpoint</li>
     *   <li>{@code GET  /api/logs/**}          — audit log read endpoints</li>
     *   <li>{@code GET  /api/dashboard/**}     — dashboard statistics</li>
     *   <li>{@code GET  /api/license/**}       — license tier info</li>
     * </ul>
     *
     * <p>{@code /api/health} and {@code /actuator/**} are deliberately NOT
     * guarded so load balancers and probes can reach them.</p>
     *
     * @param exchange the current server exchange
     * @return {@code true} if this request must present a valid API key
     */
    private boolean isGuardedRequest(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();

        // POST /api/proxy — the original guarded endpoint
        if (HttpMethod.POST.equals(method) && PROXY_PATH.equals(path)) {
            return true;
        }

        // Read endpoints — guard any method (GET, HEAD, OPTIONS pre-flight is
        // handled by Spring's CORS layer before this filter runs).
        for (String prefix : GUARDED_READ_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Writes a {@code 401 Unauthorized} response with the provided JSON body.
     *
     * @param exchange the current server exchange
     * @param body     the UTF-8 encoded JSON error body to write
     * @return a {@link Mono} that completes when the response is fully written
     */
    private Mono<Void> rejectWithUnauthorized(ServerWebExchange exchange, byte[] body) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // WWW-Authenticate tells clients what authentication scheme is expected
        response.getHeaders().set("WWW-Authenticate",
                "ApiKey realm=\"SentinelAI\", header=\"" + API_KEY_HEADER + "\"");

        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Returns {@code true} if API key authentication is currently enabled
     * (i.e. at least one key is configured).
     *
     * <p>Exposed for testing only — production code should not need to call
     * this method directly.</p>
     *
     * @return {@code true} when the valid-keys set is non-empty
     */
    public boolean isAuthEnabled() {
        return !validKeys.isEmpty();
    }
}
