package com.sentinelai.detection;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * Pluggable strategy interface for assigning an injection-risk score to a prompt.
 *
 * <h2>Why this is pluggable</h2>
 * <p>SentinelAI is a governance layer — its job is to capture an audit trail
 * of every prompt + response, not to be a best-in-class prompt-injection
 * detector. Detection vendors (Lakera, Protect AI, HiddenLayer, etc.) exist;
 * customers may want to use one of them, run their own classifier, or skip
 * scoring entirely. This interface lets the operator pick.</p>
 *
 * <h2>Bundled implementations</h2>
 * <ul>
 *   <li>{@link NoOpDetector} — default. Returns {@code Mono.just(null)} for
 *       every prompt. The audit trail is still complete; the {@code risk_score}
 *       column is simply NULL. Pure governance mode.</li>
 *   <li>{@link RuleBasedDetector} — in-process regex/keyword scorer. No
 *       network calls, no data egress, no per-request cost. Catches common
 *       prompt-injection patterns (ignore-instructions, DAN, jailbreak,
 *       role-override). Not a substitute for a real ML-based detector against
 *       sophisticated attacks; useful as a baseline or "good enough" default.</li>
 *   <li>{@link ExternalApiDetector} — POSTs the prompt to a customer-configured
 *       detection API (e.g. Lakera Guard) and reads the score from the response.
 *       Opt-in only; sends prompt text out of the customer's environment.</li>
 * </ul>
 *
 * <h2>Failure contract (every implementation must honour this)</h2>
 * <p>An implementation MUST NOT fail the proxy request. Any error — timeout,
 * network failure, parse error, vendor outage — must resolve to
 * {@code Mono.empty()}. The audit row is then written with
 * {@code risk_score = NULL}, which is a valid, queryable audit state
 * ("detection failed for this row, review retroactively"). This is the single
 * most important invariant of the detection layer.</p>
 *
 * <p>{@code Mono.empty()} is used (rather than {@code Mono.just(null)}, which
 * throws NPE in Reactor 3.x). {@link com.sentinelai.service.ProxyService}
 * wraps the strategy's Mono with {@code .map(Optional::of).defaultIfEmpty(Optional.empty())}
 * before zipping it with the AI-model Mono, so an empty emission becomes a
 * null risk score in the audit row.</p>
 *
 * <h2>Performance contract</h2>
 * <p>The score is awaited inside {@code Mono.zip} alongside the AI model call.
 * Detection slower than the AI model becomes the latency floor of the whole
 * request. Implementations should keep their own timeout small (typically
 * 2–5 seconds) and apply it via {@code .timeout()}.</p>
 *
 * <h2>Score semantics</h2>
 * <p>The returned BigDecimal must be in the range {@code [0.0, 1.0]} or
 * {@code null}. The dashboard's risk buckets ({@code HIGH ≥ 0.7},
 * {@code MEDIUM ≥ 0.4}, {@code LOW < 0.4}) are baked into the UI; an
 * implementation that emits scores in a different range must rescale.</p>
 */
public interface DetectionStrategy {

    /**
     * Returns a unique identifier for this strategy, used in logs and metrics.
     *
     * @return a short upper-snake-case identifier (e.g. {@code "NOOP"},
     *         {@code "RULES"}, {@code "EXTERNAL"})
     */
    String name();

    /**
     * Computes an injection-risk score for the given prompt.
     *
     * @param prompt the raw user prompt; may be very long (up to 200,000 chars
     *               per the ProxyRequest size cap)
     * @return a {@link Mono} that emits a {@link BigDecimal} in
     *         {@code [0.0, 1.0]}, or {@link Mono#empty()} if scoring is
     *         skipped or failed. The Mono itself must NEVER error — the caller
     *         wraps it for {@code Mono.zip}, and an error here would fail the
     *         whole proxy request.
     */
    Mono<BigDecimal> score(String prompt);
}
