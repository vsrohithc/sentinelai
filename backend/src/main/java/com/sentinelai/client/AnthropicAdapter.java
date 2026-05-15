package com.sentinelai.client;

import com.sentinelai.exception.UpstreamApiException;
import com.sentinelai.model.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@link ModelProviderAdapter} implementation for the Anthropic Messages API.
 *
 * <h2>Wire format</h2>
 * <pre>
 * POST /v1/messages
 * x-api-key: {api-key}
 * anthropic-version: 2023-06-01
 * Content-Type: application/json
 *
 * {
 *   "model": "claude-3-5-sonnet-20241022",
 *   "max_tokens": 4096,
 *   "messages": [{"role": "user", "content": "...prompt..."}]
 * }
 * </pre>
 *
 * <h2>Key differences from OpenAI</h2>
 * <ul>
 *   <li>Authentication: {@code x-api-key} header, not Bearer token.</li>
 *   <li>{@code max_tokens} is <strong>required</strong> — Anthropic rejects
 *       requests without it. We default to 4096.</li>
 *   <li>{@code anthropic-version} header is required; we pin to
 *       {@code 2023-06-01} (stable, broadly supported).</li>
 *   <li>Response text is at {@code content[0].text}, not
 *       {@code choices[0].message.content}.</li>
 * </ul>
 */
@Slf4j
public class AnthropicAdapter implements ModelProviderAdapter {

    /**
     * Anthropic API version header value.
     *
     * <p>Pinned to a stable version so upgrades to newer API versions are
     * explicit and intentional, not accidental.</p>
     */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /**
     * Default max_tokens for Anthropic requests.
     *
     * <p>Anthropic requires this field; omitting it causes a 400 error.
     * 4096 is the default output token limit for most Claude models. Callers
     * that need a different limit can extend the adapter in a future phase.</p>
     */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /** WebClient pre-configured with the Anthropic base URL and x-api-key header. */
    private final WebClient webClient;

    /**
     * @param webClient WebClient configured for Anthropic API calls (base URL +
     *                  {@code x-api-key} default header)
     */
    public AnthropicAdapter(WebClient webClient) {
        this.webClient = webClient;
    }

    /** @return {@link ModelProvider#ANTHROPIC} */
    @Override
    public ModelProvider getProvider() {
        return ModelProvider.ANTHROPIC;
    }

    /**
     * Sends the prompt to the Anthropic Messages API and returns the response text.
     *
     * @param prompt the raw user prompt
     * @param model  Anthropic model ID, e.g. {@code "claude-3-5-sonnet-20241022"}
     * @return a Mono emitting the assistant's response text from {@code content[0].text}
     */
    @Override
    public Mono<String> complete(String prompt, String model) {
        Map<String, Object> body = Map.of(
                "model", model,
                // max_tokens is mandatory for Anthropic — omitting it causes 400 Bad Request
                "max_tokens", DEFAULT_MAX_TOKENS,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        return webClient.post()
                .uri("/v1/messages")
                // anthropic-version header is required by the API
                .header("anthropic-version", ANTHROPIC_VERSION)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class).flatMap(err -> {
                            int code = response.statusCode().value();
                            log.error("Anthropic API error {}: {}", code, err);
                            return Mono.error(new UpstreamApiException(
                                    "ANTHROPIC", code, err,
                                    "Anthropic returned HTTP " + code));
                        }))
                .bodyToMono(MessagesResponse.class)
                .map(resp -> {
                    if (resp.getContent() == null || resp.getContent().isEmpty()) {
                        throw new UpstreamApiException(
                                "ANTHROPIC", 200, null,
                                "Anthropic returned empty content array");
                    }
                    // Anthropic returns content as an array of content blocks.
                    // The first block of type "text" contains the assistant's reply.
                    return resp.getContent().get(0).getText();
                })
                .doOnError(ex -> log.error("Anthropic complete() failed: {}", ex.getMessage()));
    }

    // ─── Internal response DTOs ───────────────────────────────────────────────

    /**
     * Top-level Anthropic Messages API response.
     *
     * <p>The full response has many more fields (id, type, role, model, stop_reason,
     * usage), but we only need {@code content} to extract the reply text.</p>
     */
    @lombok.Getter @lombok.NoArgsConstructor
    static class MessagesResponse {
        /**
         * Array of content blocks returned by the model.
         * Each block has a {@code type} (e.g. "text") and the corresponding field
         * (e.g. {@code text} for type "text").
         */
        private List<ContentBlock> content;
    }

    /**
     * A single content block within the Anthropic Messages response.
     *
     * <p>For a standard text response, {@code type} is {@code "text"} and
     * {@code text} contains the assistant's reply. Other types (e.g.
     * {@code "tool_use"}) are not handled in Phase 5.</p>
     */
    @lombok.Getter @lombok.NoArgsConstructor
    static class ContentBlock {
        /** Block type — "text" for standard responses. */
        private String type;
        /** The assistant's response text (populated when type = "text"). */
        private String text;
    }
}
