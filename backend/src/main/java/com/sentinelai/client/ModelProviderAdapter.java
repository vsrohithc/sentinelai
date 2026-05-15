package com.sentinelai.client;

import com.sentinelai.model.ModelProvider;
import reactor.core.publisher.Mono;

/**
 * Strategy interface for provider-specific AI model API adapters.
 *
 * <p>Each adapter implementation handles one AI provider's wire format,
 * authentication scheme, and response structure, translating them to and
 * from SentinelAI's internal representation (a plain prompt string in,
 * a plain response string out).</p>
 *
 * <h2>Design rationale — Strategy pattern</h2>
 * <p>Rather than a single monolithic client with a switch-case on provider,
 * each provider gets its own class. This means:</p>
 * <ul>
 *   <li>Adding a new provider only touches one new file + one config change.</li>
 *   <li>Each adapter can be tested in isolation with WireMock stubs matching
 *       only that provider's wire format.</li>
 *   <li>Provider-specific configuration (URLs, headers, API versions) is
 *       encapsulated inside the adapter rather than spread across a shared class.</li>
 * </ul>
 *
 * <h2>Routing</h2>
 * <p>{@link com.sentinelai.service.ProxyService} receives a {@code Map<ModelProvider,
 * ModelProviderAdapter>} bean and selects the correct adapter by calling
 * {@link ModelProvider#fromString(String)} on the {@code provider} field of the
 * inbound {@link com.sentinelai.dto.ProxyRequest}.</p>
 *
 * <h2>Error contract</h2>
 * <p>Implementations must propagate errors from the downstream provider API as
 * Mono errors. Unlike {@link com.sentinelai.detection.DetectionStrategy}
 * (which is designed to never fail the proxy request), a failed AI call means
 * there is no response to return to the caller, so errors must not be
 * swallowed here.</p>
 */
public interface ModelProviderAdapter {

    /**
     * Returns the {@link ModelProvider} this adapter handles.
     *
     * <p>Used by {@link com.sentinelai.config.ProviderConfig} to register
     * adapters in the routing map so {@code ProxyService} can look up the
     * correct adapter by key.</p>
     *
     * @return the provider enum constant this adapter is responsible for
     */
    ModelProvider getProvider();

    /**
     * Sends the given prompt to the provider's API and returns the response text.
     *
     * <p>Implementations translate the raw prompt string into the provider's
     * specific request format (e.g. Chat Completions for OpenAI, Messages for
     * Anthropic, generateContent for Gemini) and extract the response text from
     * the provider's specific response format.</p>
     *
     * <p>This method is called inside {@code Mono.zip()} in
     * {@link com.sentinelai.service.ProxyService} alongside the detection call,
     * so it must return a non-blocking {@code Mono} — no blocking I/O allowed.</p>
     *
     * @param prompt the raw prompt text to send to the model
     * @param model  the model identifier in the provider's own naming scheme,
     *               e.g. {@code "gpt-4o"}, {@code "claude-3-5-sonnet-20241022"},
     *               {@code "gemini-1.5-pro"}
     * @return a {@code Mono} emitting the model's response text; errors from the
     *         provider API propagate as Mono errors (not swallowed)
     */
    Mono<String> complete(String prompt, String model);
}
