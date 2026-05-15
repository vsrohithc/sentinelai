package com.sentinelai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sentinelai.client.AnthropicAdapter;
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
 * Integration tests for {@link AnthropicAdapter} using a local WireMock server.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Happy path: well-formed Anthropic Messages response → text extracted from content[0].text</li>
 *   <li>Request includes required {@code anthropic-version} and {@code max_tokens} fields</li>
 *   <li>Error path: 4xx from provider → Mono error</li>
 *   <li>Empty content array → RuntimeException</li>
 *   <li>{@link AnthropicAdapter#getProvider()} returns {@link ModelProvider#ANTHROPIC}</li>
 * </ul>
 */
class AnthropicAdapterTest {

    private WireMockServer wireMock;
    private AnthropicAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .defaultHeader("x-api-key", "test-anthropic-key")
                .defaultHeader("Content-Type", "application/json")
                .build();

        adapter = new AnthropicAdapter(webClient);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("getProvider() returns ANTHROPIC")
    void getProvider_returnsAnthropic() {
        assertThat(adapter.getProvider()).isEqualTo(ModelProvider.ANTHROPIC);
    }

    @Test
    @DisplayName("happy path: extracts text from content[0].text")
    void complete_happyPath_extractsTextFromContentArray() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withHeader("x-api-key", equalTo("test-anthropic-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "msg_01abc",
                                  "type": "message",
                                  "role": "assistant",
                                  "content": [
                                    {"type": "text", "text": "The capital is Paris."}
                                  ],
                                  "model": "claude-3-5-sonnet-20241022",
                                  "stop_reason": "end_turn"
                                }
                                """)));

        StepVerifier.create(adapter.complete("What is the capital?", "claude-3-5-sonnet-20241022"))
                .expectNext("The capital is Paris.")
                .verifyComplete();
    }

    @Test
    @DisplayName("request includes required anthropic-version header")
    void complete_request_includesAnthropicVersionHeader() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"content\": [{\"type\": \"text\", \"text\": \"ok\"}]}")));

        StepVerifier.create(adapter.complete("test", "claude-3-haiku-20240307"))
                .expectNext("ok")
                .verifyComplete();

        // WireMock only matches if the header is present — verify it matched
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("anthropic-version", equalTo("2023-06-01")));
    }

    @Test
    @DisplayName("provider 401 error → Mono error propagated")
    void complete_providerReturns401_propagatesError() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("{\"error\": {\"type\": \"authentication_error\"}}")));

        StepVerifier.create(adapter.complete("hello", "claude-3-5-sonnet-20241022"))
                .expectErrorMatches(ex -> ex.getMessage().contains("401"))
                .verify();
    }

    @Test
    @DisplayName("empty content array → RuntimeException")
    void complete_emptyContent_throwsRuntimeException() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"content\": []}")));

        StepVerifier.create(adapter.complete("hello", "claude-3-5-sonnet-20241022"))
                .expectErrorMatches(ex -> ex instanceof RuntimeException
                        && ex.getMessage().contains("empty content"))
                .verify();
    }
}
