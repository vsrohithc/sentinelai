package com.sentinelai.client;

import com.sentinelai.exception.UpstreamApiException;
import com.sentinelai.model.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@link ModelProviderAdapter} implementation for the OpenAI Chat Completions API.
 *
 * <h2>Wire format</h2>
 * <pre>
 * POST /chat/completions
 * Authorization: Bearer {api-key}
 * Content-Type: application/json
 *
 * {
 *   "model": "gpt-4o",
 *   "messages": [{"role": "user", "content": "...prompt..."}]
 * }
 * </pre>
 *
 * <h2>Response extraction</h2>
 * <p>The response text is extracted from
 * {@code choices[0].message.content} in the JSON response body.</p>
 *
 * <h2>Compatibility</h2>
 * <p>This adapter also works with any third-party provider that implements the
 * OpenAI Chat Completions format (Groq, Together AI, Mistral API, etc.) —
 * just point {@code OPENAI_API_URL} at their base URL.</p>
 */
@Slf4j
public class OpenAIAdapter implements ModelProviderAdapter {

    /** WebClient pre-configured with the OpenAI base URL and Bearer auth header. */
    private final WebClient webClient;

    /**
     * Constructs the adapter with a pre-configured WebClient.
     *
     * <p>The WebClient must have the OpenAI base URL and {@code Authorization}
     * header already set — see {@link com.sentinelai.config.ProviderConfig}.</p>
     *
     * @param webClient the WebClient configured for OpenAI API calls
     */
    public OpenAIAdapter(WebClient webClient) {
        this.webClient = webClient;
    }

    /** @return {@link ModelProvider#OPENAI} */
    @Override
    public ModelProvider getProvider() {
        return ModelProvider.OPENAI;
    }

    /**
     * Sends the prompt to the OpenAI Chat Completions endpoint and returns
     * the model's response text.
     *
     * @param prompt the raw user prompt
     * @param model  the OpenAI model ID, e.g. {@code "gpt-4o"} or {@code "gpt-4-turbo"}
     * @return a Mono emitting the assistant's response text
     */
    @Override
    public Mono<String> complete(String prompt, String model) {
        // Build the minimal Chat Completions request body.
        // A single user message wraps the caller's raw prompt.
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class).flatMap(err -> {
                            int code = response.statusCode().value();
                            log.error("OpenAI API error {}: {}", code, err);
                            return Mono.error(new UpstreamApiException(
                                    "OPENAI", code, err,
                                    "OpenAI returned HTTP " + code));
                        }))
                .bodyToMono(ChatCompletionResponse.class)
                .map(resp -> {
                    if (resp.getChoices() == null || resp.getChoices().isEmpty()) {
                        throw new UpstreamApiException(
                                "OPENAI", 200, null,
                                "OpenAI returned empty choices array");
                    }
                    // Extract the assistant's reply from choices[0].message.content
                    return resp.getChoices().get(0).getMessage().getContent();
                })
                .doOnError(ex -> log.error("OpenAI complete() failed: {}", ex.getMessage()));
    }

    // ─── Internal response DTOs ───────────────────────────────────────────────

    /** Top-level Chat Completions response. */
    @lombok.Getter @lombok.NoArgsConstructor
    static class ChatCompletionResponse {
        private List<Choice> choices;
    }

    /** One completion choice (typically only index 0 is used). */
    @lombok.Getter @lombok.NoArgsConstructor
    static class Choice {
        private Message message;
    }

    /** The assistant message inside a choice. */
    @lombok.Getter @lombok.NoArgsConstructor
    static class Message {
        /** The model's response text. */
        private String content;
    }
}
