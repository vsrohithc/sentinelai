package com.sentinelai.detection;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * Default {@link DetectionStrategy} implementation that performs no scoring.
 *
 * <h2>Behaviour</h2>
 * <p>{@link #score(String)} returns {@link Mono#empty()}. The downstream audit
 * record is persisted with {@code risk_score = NULL}, which is a valid,
 * queryable audit state — operators can filter the audit trail for
 * {@code risk_score IS NULL} to find every request that was logged without
 * scoring.</p>
 *
 * <h2>When this is the active strategy</h2>
 * <p>This is the out-of-the-box default. SentinelAI is a governance and audit
 * platform — its primary value is the prompt/response trail, not the injection
 * score. Operators who want scoring opt in by setting
 * {@code sentinelai.detection.strategy=rules} (in-process regex) or
 * {@code =external} (third-party HTTP API).</p>
 *
 * <h2>Why a NoOp instead of conditionals in the orchestrator</h2>
 * <p>Keeping a single uniform call site ({@code detectionStrategy.score(prompt)})
 * means {@link com.sentinelai.service.ProxyService} has no branching for the
 * detection-disabled case. The strategy pattern absorbs the variation; the
 * orchestration code stays linear and is identically testable across
 * strategies.</p>
 */
@Slf4j
public class NoOpDetector implements DetectionStrategy {

    /** Logged once at construction so operators see which strategy is active. */
    public NoOpDetector() {
        log.info("DetectionStrategy: NoOp — prompts will be audited without risk scoring. "
                + "Set sentinelai.detection.strategy=rules or =external to enable scoring.");
    }

    /** @return the constant string {@code "NOOP"}. */
    @Override
    public String name() {
        return "NOOP";
    }

    /**
     * Returns an empty Mono — no scoring is performed.
     *
     * @param prompt ignored
     * @return {@link Mono#empty()}; the proxy orchestrator translates this to a
     *         {@code null} risk score on the audit record
     */
    @Override
    public Mono<BigDecimal> score(String prompt) {
        return Mono.empty();
    }
}
