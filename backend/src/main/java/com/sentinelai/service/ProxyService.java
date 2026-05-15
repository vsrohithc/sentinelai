package com.sentinelai.service;

import com.sentinelai.client.ModelProviderAdapter;
import com.sentinelai.detection.DetectionStrategy;
import com.sentinelai.dto.ProxyRequest;
import com.sentinelai.dto.ProxyResponse;
import com.sentinelai.model.ModelProvider;
import com.sentinelai.model.PromptLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core orchestration service for the SentinelAI proxy engine.
 *
 * <h2>Request flow</h2>
 * <ol>
 *   <li>Resolve the caller's provider string to a {@link ModelProvider} enum value.</li>
 *   <li>Look up the corresponding {@link ModelProviderAdapter} in the routing map.</li>
 *   <li>Fire the AI model call and the injection detection call in
 *       <strong>parallel</strong> using {@code Mono.zip()}.</li>
 *   <li>When both Monos resolve, build and return the {@link ProxyResponse}.</li>
 *   <li>Hand the complete audit record to {@link AuditService#save(PromptLog)}
 *       which persists it <strong>asynchronously</strong> after the HTTP response
 *       is already sent.</li>
 * </ol>
 *
 * <h2>Provider routing (Phase 5)</h2>
 * <p>The {@code providerAdapters} map (built by
 * {@link com.sentinelai.config.ProviderConfig}) maps each {@link ModelProvider}
 * enum value to its adapter implementation. {@link ModelProvider#fromString(String)}
 * resolves the provider field from the request and defaults to
 * {@link ModelProvider#OPENAI} when the field is absent or unrecognised —
 * ensuring backward compatibility with Phase 2 callers.</p>
 *
 * <h2>Detection failure tolerance</h2>
 * <p>The active {@link com.sentinelai.detection.DetectionStrategy} is
 * contractually required to never fail the proxy request — every error mode
 * resolves to {@link reactor.core.publisher.Mono#empty()}. This service wraps
 * the strategy's Mono with {@code Optional.of / defaultIfEmpty(Optional.empty())}
 * before zipping it with the AI call, so an empty emission becomes a
 * {@code null} risk score on the persisted audit row — a valid, queryable
 * audit state ("detection skipped or failed for this row").</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {

    /**
     * Provider routing map — maps {@link ModelProvider} → {@link ModelProviderAdapter}.
     *
     * <p>Injected as a bean from {@link com.sentinelai.config.ProviderConfig#providerAdapters}.
     * Using a map rather than a switch statement means adding a new provider
     * only requires registering a new entry in the map — no changes to this class.</p>
     */
    private final Map<ModelProvider, ModelProviderAdapter> providerAdapters;

    /**
     * Pluggable injection-risk scoring strategy. The active implementation is
     * chosen by {@link com.sentinelai.config.DetectionConfig} based on the
     * {@code sentinelai.detection.strategy} property. Default is {@code NoOp}
     * — SentinelAI's primary value is the audit trail, scoring is opt-in.
     */
    private final DetectionStrategy detectionStrategy;

    /** Service that persists audit records asynchronously after response is returned. */
    private final AuditService auditService;

    /**
     * Proxies the caller's prompt to the appropriate AI model provider and
     * returns the response.
     *
     * <p>The provider is resolved from {@code request.getProvider()} and defaults
     * to {@link ModelProvider#OPENAI} when absent or unrecognised, preserving
     * backward compatibility. The AI model call and the injection detection call
     * are fired in parallel; the response is built from the AI result only.</p>
     *
     * @param request    the inbound proxy request (prompt, model, provider, metadata)
     * @param licenseKey the license key from the {@code X-Sentinel-License} header;
     *                   null for free-tier callers
     * @return a {@code Mono<ProxyResponse>} with the AI model's answer; errors from
     *         the AI adapter propagate as Mono errors; detection failures are silenced
     */
    public Mono<ProxyResponse> proxy(ProxyRequest request, String licenseKey) {
        // Generate stable request ID at ingestion time so it can be included in
        // both the HTTP response and the audit record, even though the DB write
        // happens asynchronously after the response is already sent.
        UUID requestId = UUID.randomUUID();
        // Use UTC explicitly so request_time is comparable across instances
        // regardless of the container's default time zone. The retention job
        // and the dashboard date aggregation both compute UTC cutoffs, so
        // mixing UTC and local-offset values here would silently misalign them.
        OffsetDateTime requestTime = OffsetDateTime.now(ZoneOffset.UTC);

        // Resolve license key: header takes precedence over body field
        String effectiveLicenseKey = (licenseKey != null && !licenseKey.isBlank())
                ? licenseKey
                : request.getLicenseKey();

        // ── Resolve provider ─────────────────────────────────────────────────
        // ModelProvider.fromString() defaults to OPENAI for null/blank/unknown values,
        // maintaining backward compatibility with Phase 2 callers.
        ModelProvider provider = ModelProvider.fromString(request.getProvider());
        ModelProviderAdapter adapter = providerAdapters.get(provider);

        if (adapter == null) {
            // Should never happen since ModelProvider.fromString() always returns a
            // value that exists in the map, but guard defensively.
            log.error("No adapter found for provider={} — falling back to OPENAI", provider);
            adapter = providerAdapters.get(ModelProvider.OPENAI);
        }

        // Populate MDC fields so every log line within this request — including
        // logs emitted from the WebClient adapters and the async audit writer —
        // carries provider and model context, not just the request_id. These
        // keys are advertised in logback-spring.xml's <includeMdcKeyName> list
        // so they appear as top-level JSON fields in production logs.
        MDC.put("provider", provider.name());
        if (request.getModel() != null) {
            MDC.put("model", request.getModel());
        }

        log.debug("Proxying request id={} provider={} model={} licenseKey={}",
                requestId, provider, request.getModel(), effectiveLicenseKey != null ? "(set)" : "(null)");

        // Capture final references for use in the lambda below
        final ModelProviderAdapter finalAdapter = adapter;

        // ── Fire parallel calls ──────────────────────────────────────────────
        // aiCall:    routes to the correct provider adapter; errors propagate.
        Mono<String> aiCall = finalAdapter.complete(request.getPrompt(), request.getModel());

        // detectionCall: every strategy honours the "never error" contract by
        // resolving to Mono.empty() on any failure. We wrap with Optional so the
        // empty case becomes a queryable null in the audit row — Mono.zip needs
        // a value in every slot to emit, and Mono.empty() would otherwise stall it.
        Mono<Optional<BigDecimal>> detectionCall = detectionStrategy.score(request.getPrompt())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());

        return Mono.zip(aiCall, detectionCall)
                .map(tuple -> {
                    String aiResponse = tuple.getT1();
                    BigDecimal riskScore = tuple.getT2().orElse(null); // null when the strategy emitted empty

                    // Expose the resolved risk score as an MDC field so the
                    // structured JSON log line emitted after this completes
                    // can be filtered by score band without parsing messages.
                    if (riskScore != null) {
                        MDC.put("risk_score", riskScore.toPlainString());
                    }

                    // Build the complete audit record and hand it to AuditService.
                    // The DB write happens after the HTTP response is returned.
                    PromptLog auditRecord = PromptLog.builder()
                            .id(requestId)
                            .requestTime(requestTime)
                            .model(request.getModel())
                            .provider(provider.name())   // store enum name (e.g. "OPENAI")
                            .prompt(request.getPrompt())
                            .response(aiResponse)
                            .riskScore(riskScore)
                            .metadata(request.getMetadata())
                            .licenseKey(effectiveLicenseKey)
                            .build();

                    auditService.save(auditRecord);

                    return ProxyResponse.builder()
                            .requestId(requestId)
                            .model(request.getModel())
                            .responseText(aiResponse)
                            .build();
                })
                .doOnError(ex ->
                        log.error("Proxy request id={} provider={} failed: {}",
                                requestId, provider, ex.getMessage()));
    }
}
