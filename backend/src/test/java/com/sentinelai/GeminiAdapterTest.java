package com.sentinelai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sentinelai.client.GeminiAdapter;
import com.sentinelai.model.ModelProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link GeminiAdapter} using a local WireMock server.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Happy path: well-formed Gemini response → text extracted from candidates[0].content.parts[0].text</li>
 *   <li>Model name is embedded in the URL path</li>
 *   <li>API key is passed as the {@code key} query parameter</li>
 *   <li>Error path: 4xx → Mono error</li>
 *   <li>Empty candidates → RuntimeException</li>
 *   <li>{@link GeminiAdapter#getProvider()} returns {@link ModelProvider#GEMINI}</li>
 * </ul>
 */
class GeminiAdapterTest {

    private static final String TEST_API_KEY = "test-gemini-key";

    private WireMockServer wireMock;
    private GeminiAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        // Gemini uses query-param auth — no Authorization header on the WebClient
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .defaultHeader("Content-Type", "application/json")
                .build();

        adapter = new GeminiAdapter(webClient, TEST_API_KEY);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("getProvider() returns GEMINI")
    void getProvider_returnsGemini() {
        assertThat(adapter.getProvider()).isEqualTo(ModelProvider.GEMINI);
    }

    @Test
    @DisplayName("happy path: model in URL path, key as query param, text from candidates[0]")
    void complete_happyPath_extractsTextFromCandidates() {
        // Stub the exact URL Gemini expects: /v1beta/models/{model}:generateContent?key=...
        wireMock.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-1.5-pro:generateContent"))
                .withQueryParam("key", equalTo(TEST_API_KEY))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "candidates": [
                                    {
                                      "content": {
                                        "role": "model",
                                        "parts": [{"text": "The capital is Paris."}]
                                      },
                                      "finishReason": "STOP"
                                    }
                                  ]
                                }
                                """)));

        StepVerifier.create(adapter.complete("What is the capital?", "gemini-1.5-pro"))
                .expectNext("The capital is Paris.")
                .verifyComplete();
    }

    @Test
    @DisplayName("model name is URL-encoded into the path correctly")
    void complete_modelNameInUrlPath() {
        wireMock.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-1.5-flash:generateContent"))
                .withQueryParam("key", equalTo(TEST_API_KEY))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"ok\"}]}}]}")));

        StepVerifier.create(adapter.complete("hello", "gemini-1.5-flash"))
                .expectNext("ok")
                .verifyComplete();

        wireMock.verify(postRequestedFor(
                urlPathEqualTo("/v1beta/models/gemini-1.5-flash:generateContent")));
    }

    @Test
    @DisplayName("provider 400 error → Mono error propagated")
    void complete_providerReturns400_propagatesError() {
        wireMock.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("{\"error\": {\"message\": \"Invalid model\"}}")));

        StepVerifier.create(adapter.complete("hello", "gemini-invalid"))
                .expectErrorMatches(ex -> ex.getMessage().contains("400"))
                .verify();
    }

    @Test
    @DisplayName("empty candidates array → RuntimeException")
    void complete_emptyCandidates_throwsRuntimeException() {
        wireMock.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"candidates\": []}")));

        StepVerifier.create(adapter.complete("hello", "gemini-1.5-pro"))
                .expectErrorMatches(ex -> ex instanceof RuntimeException
                        && ex.getMessage().contains("empty candidates"))
                .verify();
    }
}
