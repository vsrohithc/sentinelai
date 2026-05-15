package com.sentinelai.detection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * {@link DetectionStrategy} that delegates scoring to a third-party HTTP
 * detection service (Lakera Guard, Protect AI, a homegrown classifier behind
 * an HTTP wrapper, etc.).
 *
 * <h2>When to use this</h2>
 * <p>You want vendor-grade detection accuracy and you have either accepted
 * that prompts will leave your environment to reach the detector, or your
 * "detector" is a separately deployed in-cluster service. Off by default.</p>
 *
 * <h2>Data egress — read this</h2>
 * <p>Enabling this strategy means SentinelAI POSTs every customer prompt
 * (verbatim) to the configured URL. This is fundamentally different from the
 * other bundled strategies, which keep prompt content in-process. Operators
 * MUST verify the chosen detector's data-handling, retention, and sub-processor
 * obligations match their own. SentinelAI emits a WARN log line at startup so
 * the egress is visible in the audit of the deployment itself.</p>
 *
 * <h2>Wire format</h2>
 * <pre>
 * POST {DETECTION_API_URL}/analyze
 * Authorization: Bearer {DETECTION_API_KEY}      (optional, set by WebClient bean)
 * Content-Type: application/json
 *
 * {"text": "&lt;raw prompt&gt;"}
 *
 * Response: {"score": 0.0–1.0, ...other ignored fields}
 * </pre>
 * <p>If your detection vendor uses a different request/response shape, wrap
 * them in a thin adapter sitting in front of this detector, or implement a new
 * {@link DetectionStrategy} for the vendor's native API directly.</p>
 *
 * <h2>Failure contract</h2>
 * <p>Every failure mode — timeout, network error, HTTP 4xx/5xx, body parse
 * error — maps to {@link Mono#empty()} via {@code onErrorResume}. The proxy
 * request continues; the audit row gets {@code risk_score = NULL}.</p>
 */
@Slf4j
public class ExternalApiDetector implements DetectionStrategy {

    /** Pre-configured WebClient pointing at the detection API base URL. */
    private final WebClient webClient;

    /** Per-call timeout. After this, the call is cancelled and the score is null. */
    private final Duration timeout;

    /**
     * @param webClient WebClient configured for the external detection API
     *                  (base URL + optional Authorization header)
     * @param timeoutMs per-call timeout in milliseconds
     */
    public ExternalApiDetector(WebClient webClient, long timeoutMs) {
        this.webClient = webClient;
        this.timeout = Duration.ofMillis(timeoutMs);
        log.warn("DetectionStrategy: ExternalApi — every prompt will be sent to the "
                + "configured detection URL. Verify the vendor's data-handling and "
                + "retention policy. Timeout: {} ms.", timeoutMs);
    }

    /** @return the constant string {@code "EXTERNAL"}. */
    @Override
    public String name() {
        return "EXTERNAL";
    }

    /**
     * POSTs the prompt to {@code /analyze} and reads the {@code score} field
     * from the response body.
     *
     * @param prompt the raw user prompt — sent verbatim to the detection API
     * @return Mono of the score, or {@link Mono#empty()} on any failure
     */
    @Override
    public Mono<BigDecimal> score(String prompt) {
        return webClient.post()
                .uri("/analyze")
                .bodyValue(Map.of("text", prompt))
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> {
                            log.warn("Detection API returned error status: {}", response.statusCode());
                            return Mono.error(new RuntimeException(
                                    "Detection API error: " + response.statusCode()));
                        })
                .bodyToMono(DetectionResponse.class)
                .mapNotNull(DetectionResponse::getScore)
                .timeout(timeout)
                .onErrorResume(ex -> {
                    log.warn("ExternalApiDetector failed (risk_score will be null): {}", ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Internal DTO for the detection API response body.
     *
     * <p>Package-private — the wire format is an implementation detail of this
     * detector; nothing outside this class depends on it. Vendors with a
     * different response shape should be wrapped in a translating proxy, or
     * implement {@link DetectionStrategy} directly.</p>
     */
    @lombok.Getter
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    static class DetectionResponse {
        /** Risk score in {@code [0.0, 1.0]}. 0.0 = safe, 1.0 = high injection risk. */
        private BigDecimal score;
    }
}
