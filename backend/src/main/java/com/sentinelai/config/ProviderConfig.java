package com.sentinelai.config;

import com.sentinelai.client.AnthropicAdapter;
import com.sentinelai.client.AzureOpenAIAdapter;
import com.sentinelai.client.GeminiAdapter;
import com.sentinelai.client.ModelProviderAdapter;
import com.sentinelai.client.OpenAIAdapter;
import com.sentinelai.model.ModelProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.EnumMap;
import java.util.Map;

/**
 * Spring configuration for the multi-provider adapter layer.
 *
 * <p>Creates one {@link WebClient} bean per AI provider and one
 * {@link ModelProviderAdapter} bean per provider, then assembles them into a
 * {@code Map<ModelProvider, ModelProviderAdapter>} routing map that
 * {@link com.sentinelai.service.ProxyService} uses to dispatch requests.</p>
 *
 * <h2>WebClient bean naming convention</h2>
 * <ul>
 *   <li>{@code openAIProviderWebClient} — OpenAI / OpenAI-compatible providers</li>
 *   <li>{@code anthropicWebClient} — Anthropic Messages API</li>
 *   <li>{@code geminiWebClient} — Google Gemini generateContent API</li>
 *   <li>{@code azureOpenAIWebClient} — Azure OpenAI Service</li>
 * </ul>
 *
 * <h2>Adding a new provider</h2>
 * <ol>
 *   <li>Add a WebClient {@code @Bean} for the new provider.</li>
 *   <li>Add an adapter {@code @Bean} using the new WebClient.</li>
 *   <li>Register the adapter in the {@code providerAdapters} map bean.</li>
 * </ol>
 */
@Configuration
public class ProviderConfig {

    // ─── OpenAI ──────────────────────────────────────────────────────────────

    @Value("${sentinelai.providers.openai.api-url:https://api.openai.com/v1}")
    private String openAiUrl;

    @Value("${sentinelai.providers.openai.api-key:}")
    private String openAiKey;

    /**
     * WebClient for OpenAI API calls.
     *
     * <p>Uses a Bearer token in the Authorization header, which is the
     * standard authentication scheme for the OpenAI Chat Completions API.</p>
     *
     * @param builder Spring Boot's auto-configured builder
     * @return configured WebClient for OpenAI
     */
    @Bean(name = "openAIProviderWebClient")
    public WebClient openAIProviderWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(openAiUrl)
                .defaultHeader("Authorization", "Bearer " + openAiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ─── Anthropic ────────────────────────────────────────────────────────────

    @Value("${sentinelai.providers.anthropic.api-url:https://api.anthropic.com}")
    private String anthropicUrl;

    @Value("${sentinelai.providers.anthropic.api-key:}")
    private String anthropicKey;

    /**
     * WebClient for Anthropic API calls.
     *
     * <p>Anthropic uses the {@code x-api-key} header for authentication, not
     * a Bearer token. The {@code anthropic-version} header is added per-request
     * in {@link AnthropicAdapter} because it is a semantic API contract, not
     * just auth metadata.</p>
     *
     * @param builder Spring Boot's auto-configured builder
     * @return configured WebClient for Anthropic
     */
    @Bean(name = "anthropicWebClient")
    public WebClient anthropicWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(anthropicUrl)
                .defaultHeader("x-api-key", anthropicKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ─── Gemini ───────────────────────────────────────────────────────────────

    @Value("${sentinelai.providers.gemini.api-url:https://generativelanguage.googleapis.com}")
    private String geminiUrl;

    @Value("${sentinelai.providers.gemini.api-key:}")
    private String geminiKey;

    /**
     * WebClient for Google Gemini API calls.
     *
     * <p>Gemini authenticates via a {@code key} query parameter appended to
     * the request URL, not a header. The WebClient bean therefore has no
     * default Authorization header — authentication is handled in
     * {@link GeminiAdapter#complete(String, String)} via the URI builder.</p>
     *
     * @param builder Spring Boot's auto-configured builder
     * @return configured WebClient for Gemini (no auth header — key is query param)
     */
    @Bean(name = "geminiWebClient")
    public WebClient geminiWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(geminiUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ─── Azure OpenAI ─────────────────────────────────────────────────────────

    @Value("${sentinelai.providers.azure-openai.endpoint:}")
    private String azureEndpoint;

    @Value("${sentinelai.providers.azure-openai.api-key:}")
    private String azureKey;

    @Value("${sentinelai.providers.azure-openai.deployment:}")
    private String azureDeployment;

    @Value("${sentinelai.providers.azure-openai.api-version:2024-02-01}")
    private String azureApiVersion;

    /**
     * WebClient for Azure OpenAI Service calls.
     *
     * <p>Azure OpenAI uses the {@code api-key} header for authentication and
     * a resource-specific base URL (the {@code endpoint} variable). The
     * deployment name and API version are embedded in the request URL by
     * {@link AzureOpenAIAdapter}.</p>
     *
     * @param builder Spring Boot's auto-configured builder
     * @return configured WebClient for Azure OpenAI
     */
    @Bean(name = "azureOpenAIWebClient")
    public WebClient azureOpenAIWebClient(WebClient.Builder builder) {
        // Use a placeholder base URL when the Azure endpoint is not configured
        // so the bean can be created at startup without failing. Requests to
        // Azure will fail at call time if the endpoint is not set — which is
        // better than preventing the whole application from starting.
        String baseUrl = azureEndpoint.isBlank()
                ? "https://placeholder.openai.azure.com"
                : azureEndpoint;

        return builder
                .baseUrl(baseUrl)
                .defaultHeader("api-key", azureKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ─── Adapter beans ────────────────────────────────────────────────────────

    /**
     * OpenAI adapter bean.
     *
     * @param openAIProviderWebClient the WebClient configured for OpenAI
     * @return an {@link OpenAIAdapter} instance
     */
    @Bean
    public OpenAIAdapter openAIAdapter(
            @Qualifier("openAIProviderWebClient") WebClient openAIProviderWebClient) {
        return new OpenAIAdapter(openAIProviderWebClient);
    }

    /**
     * Anthropic adapter bean.
     *
     * @param anthropicWebClient the WebClient configured for Anthropic
     * @return an {@link AnthropicAdapter} instance
     */
    @Bean
    public AnthropicAdapter anthropicAdapter(
            @Qualifier("anthropicWebClient") WebClient anthropicWebClient) {
        return new AnthropicAdapter(anthropicWebClient);
    }

    /**
     * Gemini adapter bean.
     *
     * <p>The API key is passed separately (not via the WebClient) because Gemini
     * uses query-parameter auth rather than header auth.</p>
     *
     * @param geminiWebClient the WebClient configured for Gemini (no auth header)
     * @return a {@link GeminiAdapter} instance
     */
    @Bean
    public GeminiAdapter geminiAdapter(
            @Qualifier("geminiWebClient") WebClient geminiWebClient) {
        return new GeminiAdapter(geminiWebClient, geminiKey);
    }

    /**
     * Azure OpenAI adapter bean.
     *
     * @param azureOpenAIWebClient the WebClient configured for Azure OpenAI
     * @return an {@link AzureOpenAIAdapter} instance
     */
    @Bean
    public AzureOpenAIAdapter azureOpenAIAdapter(
            @Qualifier("azureOpenAIWebClient") WebClient azureOpenAIWebClient) {
        return new AzureOpenAIAdapter(azureOpenAIWebClient, azureDeployment, azureApiVersion);
    }

    /**
     * Provider routing map — the central dispatch table for {@link
     * com.sentinelai.service.ProxyService}.
     *
     * <p>Maps each {@link ModelProvider} constant to its {@link ModelProviderAdapter}
     * implementation. Injected into {@code ProxyService} so that routing
     * is a simple {@code Map.get()} with no switch statements.</p>
     *
     * @param openAIAdapter    the OpenAI adapter bean
     * @param anthropicAdapter the Anthropic adapter bean
     * @param geminiAdapter    the Gemini adapter bean
     * @param azureAdapter     the Azure OpenAI adapter bean
     * @return an unmodifiable {@link EnumMap} of provider → adapter
     */
    @Bean
    public Map<ModelProvider, ModelProviderAdapter> providerAdapters(
            OpenAIAdapter openAIAdapter,
            AnthropicAdapter anthropicAdapter,
            GeminiAdapter geminiAdapter,
            AzureOpenAIAdapter azureAdapter) {

        // EnumMap is the most efficient Map for enum keys — O(1) lookup via array index
        Map<ModelProvider, ModelProviderAdapter> map = new EnumMap<>(ModelProvider.class);
        map.put(ModelProvider.OPENAI,       openAIAdapter);
        map.put(ModelProvider.ANTHROPIC,    anthropicAdapter);
        map.put(ModelProvider.GEMINI,       geminiAdapter);
        map.put(ModelProvider.AZURE_OPENAI, azureAdapter);
        return map;
    }
}
