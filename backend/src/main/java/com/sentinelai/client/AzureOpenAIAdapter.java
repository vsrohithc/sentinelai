package com.sentinelai.client;

import com.sentinelai.exception.UpstreamApiException;
import com.sentinelai.model.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@link ModelProviderAdapter} implementation for the Azure OpenAI Service.
 *
 * <h2>Wire format</h2>
 * <pre>
 * POST {endpoint}/openai/deployments/{deployment}/chat/completions?api-version={version}
 * api-key: {api-key}
 * Content-Type: application/json
 *
 * {
 *   "messages": [{"role": "user", "content": "...prompt..."}]
 * }
 * </pre>
 *
 * <h2>Key differences from OpenAI</h2>
 * <ul>
 *   <li>The URL is per-resource and per-deployment — the deployment name
 *       (not the model name) identifies which model to use. The deployment
 *       name is configured via {@code AZURE_OPENAI_DEPLOYMENT}.</li>
 *   <li>Authentication is via the {@code api-key} header, not Bearer token.</li>
 *   <li>The {@code model} field in the request body is optional for Azure
 *       (the deployment already determines the model) but harmless to include.</li>
 *   <li>The {@code api-version} query parameter is required.</li>
 *   <li>Response structure is identical to OpenAI Chat Completions.</li>
 * </ul>
 *
 * <h2>Deployment vs model</h2>
 * <p>In Azure, operators create a "deployment" that maps to a specific model
 * version. The {@code model} parameter in the proxy request is stored for
 * audit purposes, but the actual model used is determined by the deployment
 * configured in the environment. This is a deliberate Azure design choice.</p>
 */
@Slf4j
public class AzureOpenAIAdapter implements ModelProviderAdapter {

    /**
     * The Azure OpenAI API version query parameter.
     *
     * <p>Pinned to a stable GA version. Azure updates the API surface across
     * versions; pinning avoids unexpected breaking changes from a version bump.</p>
     */
    private final String apiVersion;

    /**
     * The Azure deployment name.
     *
     * <p>Configured via {@code AZURE_OPENAI_DEPLOYMENT}. This identifies
     * which model deployment within the Azure resource to route requests to.</p>
     */
    private final String deployment;

    /**
     * WebClient pre-configured with the Azure OpenAI resource endpoint as base URL
     * and the {@code api-key} default header.
     */
    private final WebClient webClient;

    /**
     * @param webClient  WebClient with Azure endpoint as baseUrl + api-key header
     * @param deployment the Azure deployment name (from {@code AZURE_OPENAI_DEPLOYMENT})
     * @param apiVersion the Azure API version string (from {@code AZURE_OPENAI_API_VERSION})
     */
    public AzureOpenAIAdapter(WebClient webClient, String deployment, String apiVersion) {
        this.webClient = webClient;
        this.deployment = deployment;
        this.apiVersion = apiVersion;
    }

    /** @return {@link ModelProvider#AZURE_OPENAI} */
    @Override
    public ModelProvider getProvider() {
        return ModelProvider.AZURE_OPENAI;
    }

    /**
     * Sends the prompt to the Azure OpenAI deployment and returns the response text.
     *
     * <p>Note: {@code model} is stored in the audit log but the actual model is
     * determined by the Azure deployment, not this parameter.</p>
     *
     * @param prompt the raw user prompt
     * @param model  the model identifier (stored in audit log; routing uses deployment)
     * @return a Mono emitting the assistant's response text from
     *         {@code choices[0].message.content}
     */
    @Override
    public Mono<String> complete(String prompt, String model) {
        // Azure Chat Completions body — same structure as OpenAI.
        // The model field is optional but included for audit consistency.
        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        return webClient.post()
                // Azure URL path uses the deployment name; api-version is a query param.
                .uri(uriBuilder -> uriBuilder
                        .path("/openai/deployments/{deployment}/chat/completions")
                        .queryParam("api-version", apiVersion)
                        .build(deployment))
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class).flatMap(err -> {
                            int code = response.statusCode().value();
                            log.error("Azure OpenAI error {}: {}", code, err);
                            return Mono.error(new UpstreamApiException(
                                    "AZURE_OPENAI", code, err,
                                    "Azure OpenAI returned HTTP " + code));
                        }))
                // Response structure is identical to OpenAI Chat Completions
                .bodyToMono(ChatCompletionResponse.class)
                .map(resp -> {
                    if (resp.getChoices() == null || resp.getChoices().isEmpty()) {
                        throw new UpstreamApiException(
                                "AZURE_OPENAI", 200, null,
                                "Azure OpenAI returned empty choices array");
                    }
                    return resp.getChoices().get(0).getMessage().getContent();
                })
                .doOnError(ex -> log.error("Azure OpenAI complete() failed: {}", ex.getMessage()));
    }

    // ─── Internal response DTOs (same shape as OpenAI) ───────────────────────

    /** Azure Chat Completions response — identical structure to OpenAI. */
    @lombok.Getter @lombok.NoArgsConstructor
    static class ChatCompletionResponse {
        private List<Choice> choices;
    }

    /** One completion choice. */
    @lombok.Getter @lombok.NoArgsConstructor
    static class Choice {
        private Message message;
    }

    /** The assistant message within a choice. */
    @lombok.Getter @lombok.NoArgsConstructor
    static class Message {
        /** The model's response text. */
        private String content;
    }
}
