package com.sentinelai.detection;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;

/**
 * In-process {@link DetectionStrategy} that scores prompts against a curated
 * set of regex patterns covering the most common prompt-injection signatures.
 *
 * <h2>Why this exists</h2>
 * <p>The two alternative strategies have notable drawbacks:</p>
 * <ul>
 *   <li>{@link NoOpDetector} — no scoring at all. The audit trail is complete
 *       but every row has {@code risk_score = NULL}.</li>
 *   <li>{@link ExternalApiDetector} — sends the prompt to a third-party
 *       detection service. Costs money per request and, more importantly,
 *       means the prompt text leaves the customer's environment. That conflicts
 *       directly with the "self-hosted, prompts never leave your cloud"
 *       value proposition.</li>
 * </ul>
 * <p>This detector fills the gap: zero cost, zero data egress, catches the
 * 70-or-so percent of attempted prompt-injection attacks that use well-known
 * patterns (ignore-previous-instructions, DAN / jailbreak roleplay, system
 * prompt extraction, content-filter bypass, role-override). It is NOT a
 * substitute for a real ML-trained classifier against motivated, novel
 * attackers — for that, plug an {@link ExternalApiDetector} pointed at a
 * commercial detector or run your own classifier behind the same interface.</p>
 *
 * <h2>Scoring algorithm</h2>
 * <p>Each pattern carries a weight in {@code [0.0, 1.0]}. The total score is
 * {@code min(1.0, sum_of_weights_for_matched_patterns)}. The cap means a
 * prompt that matches multiple high-weight patterns saturates at 1.0 instead
 * of going out of range.</p>
 *
 * <p>Patterns are case-insensitive (Java {@code Pattern.CASE_INSENSITIVE}) and
 * use word boundaries where appropriate so substrings inside unrelated words
 * (e.g. "PRO<b>HIBIT</b>" wouldn't match "DAN") don't fire false positives.</p>
 *
 * <h2>Bucket thresholds (from {@link com.sentinelai.service.LogQueryService})</h2>
 * <ul>
 *   <li>{@code HIGH}   — score {@literal ≥} 0.70</li>
 *   <li>{@code MEDIUM} — score in {@code [0.40, 0.70)}</li>
 *   <li>{@code LOW}    — score in {@code [0.00, 0.40)}</li>
 * </ul>
 * <p>The weights below were chosen so that a single match of the strongest
 * pattern lands a prompt in HIGH, while soft signals (single mention of
 * "system prompt") land in LOW unless combined with another signal.</p>
 *
 * <h2>Failure handling</h2>
 * <p>The detector is pure CPU work — no I/O, no thread switches, no exceptions
 * expected. Should a regex evaluation throw (theoretically possible for
 * catastrophic backtracking on a pathological prompt), the {@code .onErrorResume}
 * in {@link #score(String)} maps it to {@link Mono#empty()} so the proxy
 * request is never failed.</p>
 */
@Slf4j
public class RuleBasedDetector implements DetectionStrategy {

    /**
     * A single weighted pattern. Each pattern that matches the prompt
     * contributes its {@code weight} to the total score, capped at 1.0.
     */
    private record Rule(Pattern pattern, double weight, String description) {}

    /**
     * The bundled rule set. Patterns are ordered loosely by category (strongest
     * "override the system" signals first, softer "probe for info" signals last)
     * for readability; the order does not affect scoring.
     *
     * <p>Adding a new pattern: pick a weight by category.</p>
     * <ul>
     *   <li>0.80–0.90 — explicit instruction override / known jailbreak handles</li>
     *   <li>0.55–0.75 — strong intent indicators (bypass, override, no safety)</li>
     *   <li>0.30–0.50 — soft signals on their own; useful only when stacked</li>
     * </ul>
     */
    private static final List<Rule> RULES = List.of(
            // ── Instruction override (the canonical injection pattern) ───────
            new Rule(Pattern.compile("\\bignore (all |the )?(previous|prior|above) (instructions?|prompts?|messages?)", Pattern.CASE_INSENSITIVE),
                    0.85, "ignore previous instructions"),
            new Rule(Pattern.compile("\\bdisregard (all |the )?(previous|prior|above) (instructions?|prompts?|rules?)", Pattern.CASE_INSENSITIVE),
                    0.85, "disregard previous instructions"),
            new Rule(Pattern.compile("\\bforget (everything|all|previous|prior) (you|that you)?", Pattern.CASE_INSENSITIVE),
                    0.65, "forget previous context"),

            // ── Known jailbreak personas ─────────────────────────────────────
            new Rule(Pattern.compile("\\bDAN\\b|\\bdo anything now\\b", Pattern.CASE_INSENSITIVE),
                    0.75, "DAN jailbreak persona"),
            new Rule(Pattern.compile("\\bjailbreak(?:\\s?mode)?\\b", Pattern.CASE_INSENSITIVE),
                    0.75, "jailbreak keyword"),
            new Rule(Pattern.compile("\\bAIM\\b\\s+(prompt|mode)|\\balways\\s+intelligent\\s+machiavellian\\b", Pattern.CASE_INSENSITIVE),
                    0.70, "AIM jailbreak persona"),

            // ── Role-play to disable safety ──────────────────────────────────
            new Rule(Pattern.compile("\\b(act|pretend) (as|to be).{0,40}\\b(unrestricted|no restrictions|no rules|no safety|no filter|without safety|without restrictions)\\b", Pattern.CASE_INSENSITIVE),
                    0.80, "act as unrestricted AI"),
            new Rule(Pattern.compile("\\byou are (now )?(a |an )?(AI|model|assistant).{0,40}\\b(no|without).{0,20}\\b(safety|restriction|filter|guideline)\\b", Pattern.CASE_INSENSITIVE),
                    0.75, "redefine as unsafe AI"),

            // ── Bypass safety / moderation ───────────────────────────────────
            new Rule(Pattern.compile("\\bbypass.{0,30}\\b(filter|moderation|safety|guard|guideline|policy)\\b", Pattern.CASE_INSENSITIVE),
                    0.70, "bypass safety/filter"),
            new Rule(Pattern.compile("\\b(disable|deactivate|turn off).{0,30}\\b(filter|moderation|safety|guard|guideline|restriction)\\b", Pattern.CASE_INSENSITIVE),
                    0.70, "disable safety"),
            new Rule(Pattern.compile("\\bcontent\\s+(filter|moderation)\\b.{0,20}\\b(bypass|disable|off)\\b", Pattern.CASE_INSENSITIVE),
                    0.65, "content filter bypass"),

            // ── System prompt / instruction extraction ───────────────────────
            new Rule(Pattern.compile("\\b(reveal|show|print|output|repeat|tell me)\\b.{0,30}\\b(system\\s?prompt|initial\\s+instructions|hidden\\s+prompt)\\b", Pattern.CASE_INSENSITIVE),
                    0.70, "extract system prompt"),
            new Rule(Pattern.compile("\\b(what|tell me)\\b.{0,20}\\b(your )?(real|true|actual|original)\\b.{0,20}\\b(instructions?|prompts?|rules?)\\b", Pattern.CASE_INSENSITIVE),
                    0.55, "probe original instructions"),

            // ── Override default behaviour ───────────────────────────────────
            new Rule(Pattern.compile("\\boverride.{0,30}\\b(default|instructions?|behaviou?r|response\\s+format|configuration)\\b", Pattern.CASE_INSENSITIVE),
                    0.55, "override default behaviour"),

            // ── Soft / supporting signals ────────────────────────────────────
            new Rule(Pattern.compile("\\bsystem\\s?prompt\\b", Pattern.CASE_INSENSITIVE),
                    0.35, "mentions system prompt"),
            new Rule(Pattern.compile("\\b(repeat|output|print)\\s+(this|that|the\\s+above)\\s+(verbatim|exactly|word\\s+for\\s+word)\\b", Pattern.CASE_INSENSITIVE),
                    0.45, "verbatim output request"),
            new Rule(Pattern.compile("\\bconfidential\\b.{0,30}\\b(detail|info|data|prompt|instruction)\\b", Pattern.CASE_INSENSITIVE),
                    0.40, "probe for confidential data")
    );

    /** Logged once at construction so operators see which strategy is active. */
    public RuleBasedDetector() {
        log.info("DetectionStrategy: RuleBased — {} patterns loaded; in-process scoring, no data egress.",
                RULES.size());
    }

    /** @return the constant string {@code "RULES"}. */
    @Override
    public String name() {
        return "RULES";
    }

    /**
     * Scores the prompt by summing the weight of every matching rule, capped at 1.0.
     *
     * @param prompt the raw user prompt
     * @return a Mono emitting the score in {@code [0.0, 1.0]}, or {@link Mono#empty()}
     *         on any unexpected error (defensive — regex evaluation should not throw,
     *         but we never want detection to fail the proxy request)
     */
    @Override
    public Mono<BigDecimal> score(String prompt) {
        return Mono.fromCallable(() -> computeScore(prompt))
                .onErrorResume(ex -> {
                    log.warn("RuleBasedDetector failed unexpectedly (risk_score will be null): {}",
                            ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Pure-function scoring routine — extracted from {@link #score(String)} so unit
     * tests can call it without dealing with Mono plumbing, and so callers that
     * already live in a synchronous context (e.g. CLI tools, ad-hoc scripts,
     * one-shot scoring jobs) don't have to subscribe to a Mono.
     *
     * <p>Public for cross-package test access — the test class lives in the
     * {@code com.sentinelai} root package, not {@code com.sentinelai.detection},
     * so package-private visibility wouldn't reach it.</p>
     *
     * @param prompt the raw user prompt (null is treated as empty string)
     * @return the scored value, rounded to 4 decimal places to match the
     *         {@code prompt_logs.risk_score NUMERIC(5,4)} column precision
     */
    public BigDecimal computeScore(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        double total = 0.0;
        for (Rule rule : RULES) {
            if (rule.pattern.matcher(prompt).find()) {
                total += rule.weight;
                if (log.isDebugEnabled()) {
                    log.debug("RuleBasedDetector matched rule='{}' weight={}",
                            rule.description, rule.weight);
                }
            }
        }
        double clamped = Math.min(1.0, total);
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }
}
