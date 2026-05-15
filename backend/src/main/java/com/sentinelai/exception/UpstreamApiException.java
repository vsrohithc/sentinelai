package com.sentinelai.exception;

/**
 * Raised by {@link com.sentinelai.client.ModelProviderAdapter} implementations
 * when the upstream AI provider API returns a non-success HTTP status or fails
 * the response-shape contract (empty {@code choices}, missing {@code content}, etc.).
 *
 * <p>This is the typed counterpart to the previously-used bare
 * {@link RuntimeException}. Promoting upstream failures to their own exception
 * type lets {@link GlobalExceptionHandler} translate them to <strong>HTTP 502
 * Bad Gateway</strong> instead of the generic 500. That distinction matters
 * because:</p>
 *
 * <ul>
 *   <li><strong>Monitoring</strong> can alert separately on "SentinelAI is
 *       broken" (5xx that aren't 502) versus "an upstream provider is broken"
 *       (502s with the {@code provider} tag).</li>
 *   <li><strong>Clients</strong> can implement provider-specific retry logic
 *       (rate-limit backoff, fallback providers) when they receive 502 but not
 *       when they receive 500.</li>
 *   <li><strong>SLA accounting</strong> can exclude upstream-attributable
 *       failures from SentinelAI's own availability calculations.</li>
 * </ul>
 *
 * <p>The {@link #provider}, {@link #upstreamStatus}, and {@link #upstreamBody}
 * fields are surfaced in the structured JSON error response so callers can
 * route on them without parsing the message string.</p>
 */
public class UpstreamApiException extends RuntimeException {

    /**
     * Identifier of the upstream provider that failed (e.g. {@code "OPENAI"},
     * {@code "ANTHROPIC"}, {@code "GEMINI"}, {@code "AZURE_OPENAI"},
     * {@code "DETECTION"}). Matches the {@link com.sentinelai.model.ModelProvider}
     * enum name where applicable.
     */
    private final String provider;

    /**
     * HTTP status code returned by the upstream API, or {@code 0} if the
     * failure was a transport-level error (DNS, connection refused, TLS, etc.)
     * with no HTTP status to report.
     */
    private final int upstreamStatus;

    /**
     * Raw response body from the upstream API, truncated to a safe length.
     * May be {@code null} for transport-level errors. Useful for debugging
     * but should not be echoed verbatim to end-users.
     */
    private final String upstreamBody;

    /**
     * Constructs an exception describing an upstream provider failure.
     *
     * @param provider       provider identifier (e.g. {@code "OPENAI"})
     * @param upstreamStatus HTTP status code from the upstream, or 0 if none
     * @param upstreamBody   upstream response body (may be null/blank)
     * @param message        short human description used as the exception message
     */
    public UpstreamApiException(String provider, int upstreamStatus, String upstreamBody, String message) {
        super(message);
        this.provider = provider;
        this.upstreamStatus = upstreamStatus;
        this.upstreamBody = upstreamBody;
    }

    /** @return the upstream provider identifier (e.g. {@code "OPENAI"}). */
    public String getProvider() {
        return provider;
    }

    /** @return the upstream HTTP status code, or 0 for transport-level errors. */
    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    /** @return the upstream raw response body (may be {@code null}). */
    public String getUpstreamBody() {
        return upstreamBody;
    }
}
