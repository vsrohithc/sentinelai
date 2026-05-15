package com.sentinelai.model;

/**
 * Enumeration of AI model providers supported by SentinelAI.
 *
 * <p>Each enum constant corresponds to one provider's API format and
 * authentication scheme. The proxy request body includes a {@code provider}
 * field (string) that is resolved to this enum in {@link
 * com.sentinelai.service.ProxyService} before routing to the appropriate
 * {@link com.sentinelai.client.ModelProviderAdapter}.</p>
 *
 * <h2>Adding a new provider</h2>
 * <ol>
 *   <li>Add a constant here.</li>
 *   <li>Create a new {@code *Adapter} class in {@code com.sentinelai.client}.</li>
 *   <li>Register the adapter bean in {@link com.sentinelai.config.ProviderConfig}.</li>
 *   <li>Add its URL and key properties to {@code application.yml} and
 *       {@code .env.example}.</li>
 * </ol>
 */
public enum ModelProvider {

    /**
     * OpenAI — Chat Completions API ({@code POST /v1/chat/completions}).
     *
     * <p>Also compatible with many third-party providers that implement the
     * OpenAI API format (Groq, Together AI, Mistral, etc.).</p>
     */
    OPENAI,

    /**
     * Anthropic — Messages API ({@code POST /v1/messages}).
     *
     * <p>Requires the {@code anthropic-version} header and a mandatory
     * {@code max_tokens} parameter. Response structure differs from OpenAI.</p>
     */
    ANTHROPIC,

    /**
     * Google Gemini — REST generateContent API.
     *
     * <p>Uses a URL-embedded model name and authenticates via the
     * {@code x-goog-api-key} header or an OAuth2 bearer token.</p>
     */
    GEMINI,

    /**
     * Azure OpenAI Service — Azure-hosted OpenAI-compatible endpoint.
     *
     * <p>Uses a different URL structure (per-deployment endpoint) and
     * authenticates via the {@code api-key} header rather than Bearer token.
     * The deployment name is embedded in the URL path.</p>
     */
    AZURE_OPENAI;

    /**
     * Resolves a provider name string (case-insensitive) to its enum constant.
     *
     * <p>Returns {@link #OPENAI} as the default when the string is null, blank,
     * or unrecognised so that existing callers without a {@code provider} field
     * continue to work after Phase 5 without breaking changes.</p>
     *
     * @param name the provider string from the proxy request body, e.g.
     *             {@code "ANTHROPIC"}, {@code "gemini"}, {@code "azure_openai"}
     * @return the matching enum constant; defaults to {@link #OPENAI}
     */
    public static ModelProvider fromString(String name) {
        if (name == null || name.isBlank()) {
            return OPENAI; // backward-compatible default
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            // Unknown provider name — fall back to OpenAI rather than crashing.
            // Callers with a typo in the provider field still get a response;
            // the misconfiguration will show up in the audit log model field.
            return OPENAI;
        }
    }
}
