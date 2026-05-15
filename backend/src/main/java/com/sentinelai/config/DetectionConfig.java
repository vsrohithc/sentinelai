package com.sentinelai.config;

import com.sentinelai.detection.DetectionStrategy;
import com.sentinelai.detection.ExternalApiDetector;
import com.sentinelai.detection.NoOpDetector;
import com.sentinelai.detection.RuleBasedDetector;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Wires up the active {@link DetectionStrategy} based on the
 * {@code sentinelai.detection.strategy} configuration key.
 *
 * <h2>Configuration</h2>
 * <pre>
 * sentinelai:
 *   detection:
 *     strategy: none | rules | external      # default: none (governance only)
 *     external:
 *       api-url: ${DETECTION_API_URL:}       # required when strategy=external
 *       api-key: ${DETECTION_API_KEY:}
 *       timeout-ms: ${DETECTION_TIMEOUT_MS:5000}
 * </pre>
 *
 * <h2>Default behaviour — governance only</h2>
 * <p>SentinelAI's headline value is the audit trail of every prompt + response.
 * Scoring is a feature on top, not the core. Out of the box, no scoring
 * strategy is configured ({@code strategy=none} → {@link NoOpDetector}) and
 * the audit table is populated with {@code risk_score = NULL} for every row.
 * Operators opt into scoring explicitly.</p>
 *
 * <h2>Strategy choice</h2>
 * <ul>
 *   <li>{@code none}     — {@link NoOpDetector}. Pure governance.</li>
 *   <li>{@code rules}    — {@link RuleBasedDetector}. In-process regex scorer.
 *       Catches well-known patterns. Zero data egress, zero per-request cost.</li>
 *   <li>{@code external} — {@link ExternalApiDetector}. Sends prompt text to a
 *       configured detection URL. Opt-in only — see the data-egress notes on
 *       that class.</li>
 * </ul>
 *
 * <h2>Adding a new strategy</h2>
 * <ol>
 *   <li>Implement {@link DetectionStrategy} in {@code com.sentinelai.detection}.</li>
 *   <li>Add a switch arm in {@link #detectionStrategy(...)}.</li>
 *   <li>Document the new value in {@code application.yml} and {@code .env.example}.</li>
 * </ol>
 */
@Slf4j
@Configuration
public class DetectionConfig {

    // ── Configuration properties ────────────────────────────────────────────

    /** Which strategy to activate: {@code none}, {@code rules}, or {@code external}. */
    @Value("${sentinelai.detection.strategy:none}")
    private String strategy;

    /** Base URL for the external detection API (only used when strategy=external). */
    @Value("${sentinelai.detection.external.api-url:}")
    private String externalApiUrl;

    /** Auth key for the external detection API. */
    @Value("${sentinelai.detection.external.api-key:}")
    private String externalApiKey;

    /** Per-call timeout for the external detector, in milliseconds. */
    @Value("${sentinelai.detection.external.timeout-ms:5000}")
    private long externalTimeoutMs;

    // ── Bean definitions ────────────────────────────────────────────────────

    /**
     * Builds the single {@link DetectionStrategy} bean consumed by
     * {@link com.sentinelai.service.ProxyService}.
     *
     * <p>Strategy choice is resolved once at startup. If {@code external} is
     * selected but no URL is configured, the strategy falls back to
     * {@link NoOpDetector} with a clear WARN log — preferable to failing
     * startup or silently sending prompts to an unconfigured URL.</p>
     *
     * @return the active detection strategy
     */
    @Bean
    public DetectionStrategy detectionStrategy() {
        String s = strategy == null ? "none" : strategy.trim().toLowerCase();

        return switch (s) {
            case "rules"    -> new RuleBasedDetector();
            case "external" -> buildExternalDetector();
            case "none", "" -> new NoOpDetector();
            default -> {
                log.warn("Unknown sentinelai.detection.strategy='{}' — falling back to 'none'. "
                        + "Valid values: none, rules, external.", strategy);
                yield new NoOpDetector();
            }
        };
    }

    /**
     * Builds an {@link ExternalApiDetector}, or falls back to {@link NoOpDetector}
     * when the URL is not configured. Splitting this out keeps
     * {@link #detectionStrategy()} readable.
     */
    private DetectionStrategy buildExternalDetector() {
        if (externalApiUrl == null || externalApiUrl.isBlank()) {
            log.warn("sentinelai.detection.strategy=external but no api-url configured "
                    + "(sentinelai.detection.external.api-url / DETECTION_API_URL). "
                    + "Falling back to NoOp.");
            return new NoOpDetector();
        }
        return new ExternalApiDetector(externalDetectionWebClient(), externalTimeoutMs);
    }

    /**
     * Constructs a dedicated WebClient for the external detection API.
     *
     * <p>Lives in this class (and not in a shared HTTP-client config) because
     * it is only needed when the external strategy is active. Keeping it
     * co-located with the strategy that uses it means no detection-related
     * beans exist at all in deployments that pick {@code none} or {@code rules}.</p>
     */
    private WebClient externalDetectionWebClient() {
        ConnectionProvider pool = ConnectionProvider.builder("detection")
                .maxConnections(100)
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .maxIdleTime(Duration.ofSeconds(30))
                .build();

        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(15, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(externalApiUrl)
                .defaultHeader("Content-Type", "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        if (externalApiKey != null && !externalApiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + externalApiKey);
        }

        return builder.build();
    }

    // ── No additional beans needed ──────────────────────────────────────────
    // HealthService inspects the active DetectionStrategy bean directly via its
    // name() method to decide whether to run the external-detection probe.
    // We don't expose Boolean/String "config accessor" beans here because
    // Spring's primitive-wrapper autowiring is brittle and adds noise to the
    // application context.
}
