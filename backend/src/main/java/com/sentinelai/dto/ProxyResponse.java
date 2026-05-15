package com.sentinelai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Outbound DTO returned by the {@code POST /api/proxy} endpoint.
 *
 * <p>This is the only response the caller receives. It is constructed and
 * sent <em>before</em> the audit record is persisted to the database — the
 * async write happens after this object is serialised and the HTTP response
 * is flushed. The {@code requestId} field lets callers correlate their own
 * logs with the SentinelAI audit trail.</p>
 *
 * <p>Notably absent: the risk score. Risk scores are available only via the
 * audit log API (Phase 3) because the detection call runs in parallel and
 * the score may not be resolved when this response is built. Exposing the
 * score here would require waiting for the detection call, which would add
 * latency — contrary to the "audit not block" design principle.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProxyResponse {

    /**
     * The AI model's response to the caller's prompt.
     *
     * <p>Returned verbatim from the provider API with no post-processing.
     * SentinelAI does not gate, redact, or modify responses.</p>
     */
    private String responseText;

    /**
     * The AI model that produced this response.
     *
     * <p>Echoed back from the request for client-side logging convenience —
     * the caller already knows which model they requested, but having it in
     * the response simplifies client-side audit trail construction.</p>
     */
    private String model;

    /**
     * Unique identifier for this proxy request.
     *
     * <p>This UUID was generated at request ingestion time and is also used
     * as the primary key of the {@code prompt_logs} row created asynchronously.
     * Callers can use this ID to look up the full audit record (prompt,
     * response, risk score) via the audit log API once it has been persisted.</p>
     */
    private UUID requestId;
}
