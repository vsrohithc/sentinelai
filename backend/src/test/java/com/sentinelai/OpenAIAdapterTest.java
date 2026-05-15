package com.sentinelai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sentinelai.client.OpenAIAdapter;
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
 * Integration tests for {@link OpenAIAdapter} using a local WireMock server.
 *
 * <p>No Spring context — the adapter is instantiated directly with a WebClient
 * pointed at WireMock. This makes the tests fast and independent of Spring
 * auto-configuration.</p>
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Happy path: well-formed OpenAI response → response text extracted correctly</li>
 *   <li>Error path: 4xx from provider → Mono error propagated</li>
 *   <li>Empty choices: provider returns zero choices → RuntimeException</li>
 *   <li>{@link OpenAIAdapter#getProvider()} returns {@link ModelProvider#OPENAI}</li>
 * </ul>
 */
class OpenAIAdapterTest {

    private WireMockServer wireMock;
    private OpenAIAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        // Build a WebClient pointing at WireMock — mirrors what ProviderConfig does in production
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .defaultHeader("Authorization", "Bearer test-key")
                .defaultHeader("Content-Type", "application/json")
                .build();

        adapter = new OpenAIAdapter(webClient);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("getProvider() returns OPENAI")
    void getProvider_returnsOpenAI() {
        assertThat(adapter.getProvider()).isEqualTo(ModelProvider.OPENAI);
    }

    @Test
    @DisplayName("happy path: extracts text from choices[0].message.content")
    void complete_happyPath_extractsResponseText() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", containing("Bearer"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-abc",
                                  "choices": [
                                    {
                                      "message": {"role": "assistant", "content": "Paris"},
                                      "finish_reason": "stop"
                                    }
                                  ]
                                }
                                """)));

        StepVerifier.create(adapter.complete("What is the capital of France?", "gpt-4o"))
                .expectNext("Paris")
                .verifyComplete();
    }

    @Test
    @DisplayName("provider 4xx error → Mono error propagated")
    void complete_providerReturns4xx_propagatesError() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("{\"error\": {\"message\": \"Invalid API key\"}}")));

        StepVerifier.create(adapter.complete("hello", "gpt-4o"))
                .expectErrorMatches(ex -> ex.getMessage().contains("401"))
                .verify();
    }

    @Test
    @DisplayName("provider 5xx error → Mono error propagated")
    void complete_providerReturns5xx_propagatesError() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        StepVerifier.create(adapter.complete("hello", "gpt-4o"))
                .expectErrorMatches(ex -> ex.getMessage().contains("500"))
                .verify();
    }

    @Test
    @DisplayName("empty choices array → RuntimeException")
    void complete_emptyChoices_throwsRuntimeException() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\": []}")));

        StepVerifier.create(adapter.complete("hello", "gpt-4o"))
                .expectErrorMatches(ex -> ex instanceof RuntimeException
                        && ex.getMessage().contains("empty choices"))
                .verify();
    }
}
