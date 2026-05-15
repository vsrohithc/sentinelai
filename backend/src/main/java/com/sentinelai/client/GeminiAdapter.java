package com.sentinelai.client;

import com.sentinelai.exception.UpstreamApiException;
import com.sentinelai.model.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@link ModelProviderAdapter} implementation for the Google Gemini REST API.
 *
 * <h2>Wire format</h2>
 * <pre>
 * POST /v1beta/models/{model}:generateContent?key={api-key}
 * Content-Type: application/json
 *
 * {
 *   "contents": [
 *     {
 *       "role": "user",
 *       "parts": [{"text": "...prompt..."}]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h2>Key differences from OpenAI</h2>
 * <ul>
 *   <li>The model name is embedded in the URL path, not the request body.</li>
 *   <li>Authentication is via the {@code key} query parameter (API key).
 *       Alternatively a Bearer token can be used, but the query-param approach
 *       is simpler for server-to-server calls.</li>
 *   <li>The message array is called {@code contents} (not {@code messages}),
 *       and each item has a {@code parts} array with {@code text} objects.</li>
 *   <li>Response text is at
 *       {@code candidates[0].content.parts[0].text}.</li>
 * </ul>
 *
 * <h2>API key injection</h2>
 * <p>The API key is appended as a query parameter by this adapter rather than
 * in a header, because Gemini's REST API uses query-param auth by default.
 * The WebClient bean in {@link com.sentinelai.config.ProviderConfig} does NOT
 * set a default Authorization header for Gemini — the key is injected here.</p>
 */
@Slf4j
public class GeminiAdapter implements ModelProviderAdapter {

    /** WebClient pre-configured with the Gemini base URL (no auth header — key is a query param). */
    private final WebClient webClient;

    /**
     * The Gemini API key, injected from {@code sentinelai.providers.gemini.api-key}.
     *
     * <p>Appended as {@code ?key=...} to every generateContent request.</p>
     */
    private final String apiKey;

    /**
     * @param webClient WebClient configured for Gemini API calls (base URL set;
     *                  no Authorization header — Gemini uses query-param auth)
     * @param apiKey    the Google AI Studio or Vertex AI API key
     */
    public GeminiAdapter(WebClient webClient, String apiKey) {
        this.webClient = webClient;
        this.apiKey = apiKey;
    }

    /** @return {@link ModelProvider#GEMINI} */
    @Override
    public ModelProvider getProvider() {
        return ModelProvider.GEMINI;
    }

    /**
     * Sends the prompt to the Gemini generateContent endpoint and returns
     * the model's response text.
     *
     * @param prompt the raw user prompt
     * @param model  Gemini model ID, e.g. {@code "gemini-1.5-pro"} or
     *               {@code "gemini-1.5-flash"}
     * @return a Mono emitting the assistant's response text from
     *         {@code candidates[0].content.parts[0].text}
     */
    @Override
    public Mono<String> complete(String prompt, String model) {
        // Gemini uses a "parts" array inside each content entry.
        // A single text part wraps the caller's raw prompt.
        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("text", prompt))
                        )
                )
        );

        return webClient.post()
                // Model name is part of the URI path; API key is a query parameter.
                .uri(uriBuilder -> uriBuilder
                        .path("/v1beta/models/{model}:generateContent")
                        .queryParam("key", apiKey)
                        .build(model))
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class).flatMap(err -> {
                            int code = response.statusCode().value();
                            log.error("Gemini API error {}: {}", code, err);
                            return Mono.error(new UpstreamApiException(
                                    "GEMINI", code, err,
                                    "Gemini returned HTTP " + code));
                        }))
                .bodyToMono(GenerateContentResponse.class)
                .map(resp -> {
                    if (resp.getCandidates() == null || resp.getCandidates().isEmpty()) {
                        throw new UpstreamApiException(
                                "GEMINI", 200, null,
                                "Gemini returned empty candidates array");
                    }
                    // Navigate: candidates[0].content.parts[0].text
                    Candidate candidate = resp.getCandidates().get(0);
                    if (candidate.getContent() == null
                            || candidate.getContent().getParts() == null
                            || candidate.getContent().getParts().isEmpty()) {
                        throw new UpstreamApiException(
                                "GEMINI", 200, null,
                                "Gemini candidate has no parts");
                    }
                    return candidate.getContent().getParts().get(0).getText();
                })
                .doOnError(ex -> log.error("Gemini complete() failed: {}", ex.getMessage()));
    }

    // ─── Internal response DTOs ───────────────────────────────────────────────

    /** Top-level Gemini generateContent response. */
    @lombok.Getter @lombok.NoArgsConstructor
    static class GenerateContentResponse {
        private List<Candidate> candidates;
    }

    /** One generation candidate (the first is the primary response). */
    @lombok.Getter @lombok.NoArgsConstructor
    static class Candidate {
        private ContentEntry content;
    }

    /** The content object inside a candidate — contains an array of parts. */
    @lombok.Getter @lombok.NoArgsConstructor
    static class ContentEntry {
        private List<Part> parts;
    }

    /**
     * A single part within a content block.
     * For text responses, {@code text} holds the model's reply.
     */
    @lombok.Getter @lombok.NoArgsConstructor
    static class Part {
        /** The text content of this part. */
        private String text;
    }
}
