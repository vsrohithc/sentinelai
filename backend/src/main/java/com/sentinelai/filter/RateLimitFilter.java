package com.sentinelai.filter;

import com.sentinelai.config.RateLimitConfig;
import io.github.bucket4j.Bucket;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * WebFilter that enforces per-IP rate limiting on {@code POST /api/proxy}.
 *
 * <h2>Algorithm</h2>
 * <p>Each client IP address gets its own token-bucket (managed by
 * {@link RateLimitConfig}). When a request arrives the filter tries to consume
 * one token from the bucket. If a token is available the request proceeds
 * through the filter chain. If the bucket is empty the filter short-circuits
 * and returns {@code HTTP 429 Too Many Requests} with a {@code Retry-After: 1}
 * header so clients know to back off.</p>
 *
 * <h2>Scope</h2>
 * <p>Rate limiting is applied only to {@code POST /api/proxy}. All other
 * paths (health, actuator, dashboard, license info) are not affected.</p>
 *
 * <h2>IP extraction and X-Forwarded-For security</h2>
 * <p>Client IP is determined by {@code sentinel.rate-limit.trust-proxy}:</p>
 * <ul>
 *   <li>{@code true} (default behind a reverse proxy) — read the leftmost IP
 *       from the {@code X-Forwarded-For} header. <strong>Only enable when the
 *       application is behind a trusted reverse proxy</strong> (Nginx, AWS ALB,
 *       etc.) that strips/overwrites the header before forwarding. A malicious
 *       caller can forge {@code X-Forwarded-For} to spoof any IP and escape
 *       their rate-limit bucket if the header reaches the app unvalidated.</li>
 *   <li>{@code false} — always use the TCP remote address. Safe in any
 *       deployment but will rate-limit on the proxy IP rather than the real
 *       client IP when behind a reverse proxy.</li>
 * </ul>
 * <p>When running behind the Nginx container in {@code docker-compose.prod.yml},
 * set {@code sentinel.rate-limit.trust-proxy=true} and ensure Nginx is
 * configured with {@code proxy_set_header X-Forwarded-For $remote_addr;} so
 * that only Nginx's view of the client IP is forwarded (not a client-supplied
 * value).</p>
 *
 * <h2>Filter ordering</h2>
 * <p>Runs at {@code MIN_VALUE + 20}, after {@link ApiKeyFilter}
 * ({@code MIN_VALUE + 10}) and {@link RequestContextFilter}
 * ({@code MIN_VALUE}). API-key validation runs first so unauthenticated
 * callers receive {@code 401} rather than consuming a rate-limit token.</p>
 */
@Slf4j
@Component
@Order(Integer.MIN_VALUE + 20)
public class RateLimitFilter implements WebFilter {

    /** Path this filter guards — only POST requests to this path are rate-limited. */
    private static final String PROXY_PATH = "/api/proxy";

    /** JSON error body returned when the rate limit is exceeded. */
    private static final byte[] RATE_LIMIT_BODY =
            ("{\"error\":\"rate_limit_exceeded\","
            + "\"message\":\"Too many requests. Please slow down and retry after 1 second.\"}")
            .getBytes(StandardCharsets.UTF_8);

    /** Source of per-IP Bucket4j token buckets. */
    private final RateLimitConfig rateLimitConfig;

    /**
     * When {@code true}, the leftmost IP in {@code X-Forwarded-For} is used for
     * rate limiting. Only enable when the application is behind a trusted reverse
     * proxy that sets this header. Injected from
     * {@code sentinel.rate-limit.trust-proxy} (default {@code false}).
     */
    private final boolean trustProxy;

    /**
     * Constructs the filter with its dependencies.
     *
     * @param rateLimitConfig source of per-IP token buckets
     * @param trustProxy      when {@code true}, {@code X-Forwarded-For} is trusted
     *                        for IP extraction; default {@code false}
     */
    public RateLimitFilter(
            RateLimitConfig rateLimitConfig,
            @Value("${sentinel.rate-limit.trust-proxy:false}") boolean trustProxy) {
        this.rateLimitConfig = rateLimitConfig;
        this.trustProxy = trustProxy;

        if (trustProxy) {
            log.info("RateLimitFilter: trust-proxy=true — X-Forwarded-For will be "
                    + "used for rate-limit bucketing. Ensure a trusted reverse proxy "
                    + "strips client-supplied X-Forwarded-For before forwarding.");
        } else {
            log.info("RateLimitFilter: trust-proxy=false — TCP remote address used "
                    + "for rate-limit bucketing (safe default).");
        }
    }

    /**
     * Intercepts every request, applies rate limiting to {@code POST /api/proxy},
     * and either delegates to the next filter or rejects with HTTP 429.
     *
     * @param exchange the current server exchange
     * @param chain    the remaining filter chain
     * @return a {@link Mono} that completes when the exchange is handled
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isProxyRequest(exchange)) {
            return chain.filter(exchange);
        }

        String clientIp = extractClientIp(exchange);
        Bucket bucket = rateLimitConfig.resolveBucket(clientIp);

        if (bucket.tryConsume(1)) {
            log.debug("Rate limit OK for ip={} remaining={}", clientIp,
                    bucket.getAvailableTokens());
            return chain.filter(exchange);
        }

        log.warn("Rate limit exceeded for ip={}", clientIp);
        return rejectWithTooManyRequests(exchange);
    }

    /**
     * Returns {@code true} if the current request is {@code POST /api/proxy}.
     *
     * @param exchange the current server exchange
     * @return {@code true} when method is POST and path is /api/proxy
     */
    private boolean isProxyRequest(ServerWebExchange exchange) {
        return HttpMethod.POST.equals(exchange.getRequest().getMethod())
                && PROXY_PATH.equals(exchange.getRequest().getPath().value());
    }

    /**
     * Extracts the client IP address to use for rate-limit bucketing.
     *
     * <p>When {@code trustProxy} is {@code true}, reads the leftmost value from
     * {@code X-Forwarded-For} (original client per RFC 7239). When
     * {@code trustProxy} is {@code false} (the secure default), always uses the
     * TCP remote address. See class-level Javadoc for the security implications.</p>
     *
     * @param exchange the current server exchange
     * @return the IP address string to use as the rate-limit key
     */
    private String extractClientIp(ServerWebExchange exchange) {
        if (trustProxy) {
            String forwarded = exchange.getRequest().getHeaders()
                    .getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // X-Forwarded-For: client, proxy1, proxy2 — leftmost is original client
                String ip = forwarded.split(",")[0].trim();
                if (!ip.isBlank()) {
                    return ip;
                }
            }
        }

        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    /**
     * Writes a {@code 429 Too Many Requests} response with a JSON error body
     * and a {@code Retry-After: 1} header.
     *
     * @param exchange the current server exchange
     * @return a {@link Mono} that completes when the response is fully written
     */
    private Mono<Void> rejectWithTooManyRequests(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", "1");

        DataBuffer buffer = response.bufferFactory().wrap(RATE_LIMIT_BODY);
        return response.writeWith(Mono.just(buffer));
    }
}
