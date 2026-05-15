package com.sentinelai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux CORS configuration for SentinelAI.
 *
 * <h2>CORS policy</h2>
 * <p>The dashboard frontend (React SPA) is served on a different origin than the
 * backend API in production. CORS is configured to allow requests only from the
 * origin(s) set in {@code CORS_ALLOWED_ORIGINS} (comma-separated). The default
 * value ({@code http://localhost:3000}) is intentionally permissive for local
 * development and must be overridden in production.</p>
 *
 * <p>If you add a new API path that the frontend needs to call, add it to the
 * {@code addCorsMappings} path list.</p>
 *
 * <h2>Security headers</h2>
 * <p>Security response headers are added by {@link SecurityHeadersFilter} which
 * runs at the lowest filter priority (after all application filters). They apply
 * to every response regardless of route, status code, or CORS outcome.</p>
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    /**
     * Comma-separated list of allowed CORS origins, injected from
     * {@code CORS_ALLOWED_ORIGINS} environment variable.
     *
     * <p>Example production value:
     * {@code https://app.sentinelai.io,https://www.sentinelai.io}</p>
     */
    @Value("${sentinelai.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String[] allowedOrigins;

    /**
     * Registers CORS mappings for all SentinelAI API routes.
     *
     * <ul>
     *   <li>{@code /api/**} — proxy endpoint, health, license info</li>
     *   <li>{@code /actuator/**} — health and metrics (accessed by monitoring dashboards)</li>
     * </ul>
     *
     * <p>Allowed methods are restricted to the set actually used by the frontend.
     * {@code OPTIONS} is handled implicitly by Spring's CORS processing.</p>
     *
     * <p>Credentials ({@code allowCredentials}) are left at the default ({@code false})
     * because SentinelAI uses header-based API key authentication, not cookies.
     * Setting this to {@code true} without a specific origin allowlist is a
     * misconfiguration — do not add it without a corresponding restriction on origins.</p>
     *
     * @param registry the WebFlux CORS registry to configure
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods(
                        HttpMethod.GET.name(),
                        HttpMethod.POST.name(),
                        HttpMethod.OPTIONS.name())
                .allowedHeaders(
                        HttpHeaders.CONTENT_TYPE,
                        HttpHeaders.AUTHORIZATION,
                        "X-Sentinel-License",
                        "X-Sentinel-Api-Key",
                        "X-Request-Id")
                .exposedHeaders(
                        "X-Request-Id",
                        HttpHeaders.RETRY_AFTER)
                .maxAge(3600);

        registry.addMapping("/actuator/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods(HttpMethod.GET.name())
                .maxAge(3600);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Security response headers filter
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds standard security response headers to every HTTP response.
     *
     * <p>These headers harden the application against common web attacks and are
     * independent of CORS. They are especially important for the dashboard pages
     * served by the backend in single-deployment mode.</p>
     *
     * <table border="1">
     *   <caption>Security headers applied</caption>
     *   <tr><th>Header</th><th>Value</th><th>Purpose</th></tr>
     *   <tr><td>X-Content-Type-Options</td><td>nosniff</td><td>Prevent MIME-type sniffing</td></tr>
     *   <tr><td>X-Frame-Options</td><td>DENY</td><td>Prevent clickjacking via iframes</td></tr>
     *   <tr><td>Referrer-Policy</td><td>strict-origin-when-cross-origin</td><td>Limit Referer leakage</td></tr>
     *   <tr><td>Permissions-Policy</td><td>...</td><td>Disable unused browser features</td></tr>
     *   <tr><td>Content-Security-Policy</td><td>default-src 'self'</td><td>Restrict resource loading</td></tr>
     *   <tr><td>Strict-Transport-Security</td><td>max-age=31536000</td><td>Force HTTPS (only if TLS)</td></tr>
     * </table>
     *
     * <p>The filter runs at {@code Integer.MAX_VALUE} (lowest priority) so it
     * always executes last and cannot interfere with authentication or rate-limiting
     * filters.</p>
     */
    @Component
    @Order(Integer.MAX_VALUE)
    static class SecurityHeadersFilter implements WebFilter {

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            // Headers MUST be set before chain.filter() executes.
            // Netty commits the response as soon as the first bytes are written;
            // any header mutation after that point (e.g. in doFinally) is silently dropped.
            addSecurityHeaders(exchange);
            return chain.filter(exchange);
        }

        private void addSecurityHeaders(ServerWebExchange exchange) {
            HttpHeaders headers = exchange.getResponse().getHeaders();

            // Prevent MIME-type sniffing attacks
            headers.set("X-Content-Type-Options", "nosniff");

            // Prevent clickjacking — SentinelAI dashboard should never be framed
            headers.set("X-Frame-Options", "DENY");

            // Limit referrer information sent to third-party sites
            headers.set("Referrer-Policy", "strict-origin-when-cross-origin");

            // Disable browser features not needed by this application
            headers.set("Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(), payment=(), usb=()");

            // Restrict resource loading to the same origin
            // 'unsafe-inline' is needed for the React SPA's inline styles in dev mode;
            // tighten this in production if the frontend is served separately.
            headers.set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                    + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                    + "connect-src 'self'");

            // Force HTTPS for one year. Only effective over TLS; harmless over HTTP.
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }
}
