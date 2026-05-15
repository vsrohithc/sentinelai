package com.sentinelai.service;

import com.sentinelai.detection.DetectionStrategy;
import com.sentinelai.dto.HealthDto;
import com.sentinelai.dto.HealthDto.ComponentHealth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Service that performs liveness / readiness checks for the SentinelAI backend.
 *
 * <h2>Checks performed</h2>
 * <ol>
 *   <li><b>Database</b> — runs {@code SELECT 1} via a blocking JDBC call wrapped
 *       in {@code Mono.fromCallable} on the {@code boundedElastic} scheduler so it
 *       does not block the Netty event loop. Failure marks the overall status
 *       {@code "DOWN"}.</li>
 *   <li><b>Detection</b> — only meaningful when the active
 *       {@link DetectionStrategy} talks to an external service
 *       (i.e. {@code sentinelai.detection.strategy=external}). For the in-process
 *       strategies ({@code none}, {@code rules}) there is nothing to probe;
 *       this check returns UP with a short "skipped" note. When external is
 *       active, an HTTP HEAD probe to the configured URL is fired; any
 *       response status (2xx, 4xx, 5xx) means UP; only network-level failure
 *       or timeout means DOWN, which degrades the overall status to
 *       {@code "DEGRADED"} rather than {@code "DOWN"}.</li>
 * </ol>
 *
 * <h2>Why any HTTP status means UP for the detection API</h2>
 * <p>The detection API may not expose a HEAD on {@code /analyze}. A 404 or 405
 * proves the server is running and responding — the route simply doesn't
 * handle HEAD. A 401 means auth is required — again, the server is reachable.
 * Only a connection error or timeout (no server listening, network partition,
 * firewall block) means the service is genuinely unreachable. Using
 * {@code exchangeToMono()} gives us full control of the raw response,
 * bypassing WebClient's automatic error-raising for 4xx/5xx status codes.</p>
 *
 * <h2>Reactive execution</h2>
 * <p>Both checks run in parallel via {@code Mono.zip()} so the health endpoint
 * responds as fast as the slowest check (bounded by the detection timeout).</p>
 */
@Slf4j
@Service
public class HealthService {

    /** Hard timeout for the detection API reachability probe. */
    private static final Duration DETECTION_PROBE_TIMEOUT = Duration.ofSeconds(2);

    /** Simple SQL used to verify database connectivity. */
    private static final String DB_PING_SQL = "SELECT 1";

    /** JdbcTemplate for the synchronous database ping. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Whether the active detection strategy is the external-HTTP one. When
     * {@code false}, the detection probe is skipped because there is no remote
     * service to check (the strategy is purely in-process).
     */
    private final boolean externalDetectionEnabled;

    /** Base URL for the external detection API — only used when the probe runs. */
    private final String externalDetectionUrl;

    /**
     * WebClient used for the detection probe. Lazily-initialised so that no
     * connection pool exists when external detection is disabled.
     */
    private final WebClient probeWebClient;

    /**
     * @param jdbcTemplate              Spring-managed JDBC template for database checks
     * @param activeStrategy            the {@link DetectionStrategy} resolved by
     *                                  {@link com.sentinelai.config.DetectionConfig}. Inspected
     *                                  via {@link DetectionStrategy#name()} to decide whether
     *                                  to probe (only the {@code EXTERNAL} strategy talks to a
     *                                  remote service).
     * @param externalDetectionUrl      base URL of the external detection API, read directly from
     *                                  the same property {@code DetectionConfig} reads. Blank when
     *                                  the strategy is not {@code external}; in that case the
     *                                  probe is skipped regardless of this value.
     */
    public HealthService(
            JdbcTemplate jdbcTemplate,
            DetectionStrategy activeStrategy,
            @Value("${sentinelai.detection.external.api-url:}") String externalDetectionUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.externalDetectionEnabled = "EXTERNAL".equals(activeStrategy.name())
                && externalDetectionUrl != null
                && !externalDetectionUrl.isBlank();
        this.externalDetectionUrl = externalDetectionUrl;

        // Build the probe client only when we'll actually use it; saves an
        // unnecessary connection pool when external detection is disabled.
        this.probeWebClient = this.externalDetectionEnabled
                ? WebClient.builder().baseUrl(externalDetectionUrl).build()
                : null;
    }

    /**
     * Performs all health checks in parallel and returns an aggregated
     * {@link HealthDto}.
     *
     * <p>The overall {@code status} is computed as:</p>
     * <ul>
     *   <li>{@code "DOWN"}     — database check failed (critical)</li>
     *   <li>{@code "DEGRADED"} — database is up but external detection is unreachable</li>
     *   <li>{@code "UP"}       — both checks passed (or detection probe was skipped)</li>
     * </ul>
     *
     * @return a {@code Mono} emitting the aggregated {@link HealthDto}
     */
    public Mono<HealthDto> check() {
        return Mono.zip(checkDatabase(), checkDetection())
                .map(tuple -> {
                    ComponentHealth dbHealth        = tuple.getT1();
                    ComponentHealth detectionHealth = tuple.getT2();
                    String overallStatus = computeOverallStatus(dbHealth, detectionHealth);

                    return HealthDto.builder()
                            .status(overallStatus)
                            .db(dbHealth)
                            .detection(detectionHealth)
                            .build();
                });
    }

    /**
     * Verifies database connectivity by executing {@code SELECT 1}.
     *
     * <p>The synchronous JDBC call runs on {@code Schedulers.boundedElastic()}
     * to avoid blocking a Netty I/O thread. Any exception is caught and returned
     * as {@link ComponentHealth#down(String)}.</p>
     *
     * @return a {@code Mono} emitting the database {@link ComponentHealth}
     */
    private Mono<ComponentHealth> checkDatabase() {
        return Mono.fromCallable(() -> {
                    jdbcTemplate.queryForObject(DB_PING_SQL, Integer.class);
                    return ComponentHealth.up();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    log.error("Database health check failed: {}", ex.getMessage());
                    return Mono.just(ComponentHealth.down(ex.getMessage()));
                });
    }

    /**
     * Probes the active detection strategy. For in-process strategies
     * ({@code none}, {@code rules}) there is nothing to probe — returns UP
     * immediately with a "skipped" note. For the {@code external} strategy,
     * issues a HEAD on the configured URL with a short timeout.
     *
     * @return a {@code Mono} emitting the detection {@link ComponentHealth}
     */
    private Mono<ComponentHealth> checkDetection() {
        if (!externalDetectionEnabled || probeWebClient == null) {
            // No remote service to probe — declare UP so the overall status is
            // not artificially degraded by an inactive subsystem.
            return Mono.just(ComponentHealth.builder()
                    .status("UP")
                    .message("detection probe skipped — strategy is in-process")
                    .build());
        }

        return probeWebClient.head()
                .uri("/analyze")
                .exchangeToMono(response -> response
                        .releaseBody()
                        .thenReturn(ComponentHealth.up()))
                .timeout(DETECTION_PROBE_TIMEOUT)
                .onErrorResume(ex -> {
                    log.warn("Detection API health probe failed (url={}): {}",
                            externalDetectionUrl, ex.getMessage());
                    return Mono.just(ComponentHealth.down(sanitiseErrorMessage(ex)));
                });
    }

    /**
     * Produces a concise, safe error message suitable for inclusion in a
     * JSON API response.
     *
     * <p>Strips stack traces and internal class names that could leak
     * implementation details to external callers, while preserving enough
     * information for an operator to diagnose the connectivity failure.</p>
     *
     * @param ex the exception from the failed probe
     * @return a short human-readable error description
     */
    private String sanitiseErrorMessage(Throwable ex) {
        String type = ex.getClass().getSimpleName();
        String msg  = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return type;
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    /**
     * Computes the overall status from individual component health values.
     *
     * <p>The database is critical — its failure immediately drives the top-level
     * status to {@code "DOWN"}. External detection (when active) is optional —
     * its failure degrades to {@code "DEGRADED"} so operators know to
     * investigate without the system being declared fully unavailable.</p>
     *
     * @param db        health of the database component
     * @param detection health of the detection component (or skipped sentinel)
     * @return {@code "UP"}, {@code "DEGRADED"}, or {@code "DOWN"}
     */
    private String computeOverallStatus(ComponentHealth db, ComponentHealth detection) {
        if ("DOWN".equals(db.getStatus())) {
            return "DOWN";
        }
        if ("DOWN".equals(detection.getStatus())) {
            return "DEGRADED";
        }
        return "UP";
    }
}
