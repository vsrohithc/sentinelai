package com.sentinelai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * Inbound DTO for the {@code POST /api/proxy} endpoint.
 *
 * <p>Callers send this JSON body to have SentinelAI forward their prompt to
 * the target AI model while simultaneously running injection detection. The
 * full request is also persisted to the audit log asynchronously.</p>
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li>{@code prompt} and {@code model} are required fields enforced by Bean
 *       Validation. A missing or blank value yields HTTP 400 with a JSON error
 *       body from {@code GlobalExceptionHandler}.</li>
 *   <li>The license key is read from the {@code X-Sentinel-License} HTTP header
 *       first; the {@code licenseKey} body field is a fallback for clients that
 *       cannot set custom headers. <strong>The header takes precedence.</strong></li>
 *   <li>{@code metadata} accepts any flat or nested JSON object; keys and
 *       structure are operator-defined (e.g. {@code {"user_id": "u123"}}).</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProxyRequest {

    /**
     * The prompt text to forward to the AI model.
     *
     * <p>Required and must be non-blank. This is the exact content sent to the
     * provider API and stored verbatim in the audit log. SentinelAI passes it
     * through without modification — callers are responsible for system-prompt
     * prefixing or message formatting.</p>
     *
     * <p>A 200,000-character upper bound (~150k tokens) prevents accidental or
     * malicious oversized requests from being buffered and forwarded. Adjust
     * via the {@code @Size} max if your use case requires longer prompts.</p>
     */
    @NotBlank(message = "prompt must not be blank")
    @Size(max = 200_000, message = "prompt must not exceed 200,000 characters")
    private String prompt;

    /**
     * Target AI model identifier.
     *
     * <p>Required and must be non-blank. Must match the model name accepted by
     * the configured provider.
     * Examples: {@code "gpt-4o"}, {@code "claude-3-5-sonnet-20241022"},
     * {@code "gemini-1.5-pro"}. Stored in the audit log for reporting.</p>
     */
    @NotBlank(message = "model must not be blank")
    @Size(max = 256, message = "model name must not exceed 256 characters")
    private String model;

    /**
     * Target AI provider.
     *
     * <p>Optional. Routes the request to the correct provider-specific adapter
     * (OPENAI, ANTHROPIC, GEMINI, AZURE_OPENAI). Defaults to OPENAI when
     * absent or unrecognised, preserving backward compatibility.</p>
     */
    @Size(max = 64, message = "provider name must not exceed 64 characters")
    private String provider;

    /**
     * Optional license key identifying the tenant.
     *
     * <p>Fallback channel — the {@code X-Sentinel-License} HTTP header takes
     * precedence when both are present. Use the header when possible so the
     * key does not appear in request-body logs.</p>
     */
    @Size(max = 512, message = "licenseKey must not exceed 512 characters")
    private String licenseKey;

    /**
     * Arbitrary caller-supplied metadata attached to the audit record.
     *
     * <p>Operators use this to correlate audit records with their own systems
     * (e.g. {@code {"user_id": "u123", "department": "finance"}}). Stored as
     * JSONB in PostgreSQL with no schema enforcement.</p>
     */
    private Map<String, Object> metadata;
}
