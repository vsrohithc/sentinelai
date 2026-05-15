package com.sentinelai.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only DTO returned by {@code GET /api/logs} and {@code GET /api/logs/{id}}.
 *
 * <p>Mirrors the {@code PromptLog} JPA entity but is decoupled from it so that
 * the REST surface can evolve independently of the persistence model. Fields
 * are named in camelCase here; Jackson serialises them to camelCase JSON by
 * default, matching the TypeScript interface on the frontend.</p>
 *
 * <p>The full {@code prompt} and {@code response} text are included here so
 * that a single {@code GET /api/logs/{id}} call can populate the detail drawer
 * in the Audit Log page without a second round-trip.</p>
 *
 * @param id          surrogate primary key
 * @param requestTime wall-clock time the proxy received the request
 * @param model       AI model identifier (e.g. "gpt-4o")
 * @param provider    AI provider routing key (e.g. "OPENAI", "ANTHROPIC", "GEMINI", "AZURE_OPENAI")
 * @param prompt      full raw prompt text
 * @param response    full raw AI response; null if the AI call failed
 * @param riskScore   injection risk score 0.0–1.0; null if detection API failed
 * @param metadata    caller-supplied JSON metadata bag
 * @param licenseKey  license key from the {@code X-Sentinel-License} header; null for free tier
 * @param createdAt   timestamp the row was inserted by the async persistence path
 */
public record PromptLogDto(
        UUID id,
        OffsetDateTime requestTime,
        String model,
        String provider,
        String prompt,
        String response,
        BigDecimal riskScore,
        Map<String, Object> metadata,
        String licenseKey,
        OffsetDateTime createdAt
) {}
