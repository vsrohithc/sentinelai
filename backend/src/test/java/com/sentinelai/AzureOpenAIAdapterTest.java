package com.sentinelai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sentinelai.client.AzureOpenAIAdapter;
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
 * Integration tests for {@link AzureOpenAIAdapter} using a local WireMock server.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Happy path: Azure OpenAI response (same shape as OpenAI) → text extracted</li>
 *   <li>Request URL contains deployment name + api-version query param</li>
 *   <li>Authentication uses {@code api-key} header (not Bearer token)</li>
 *   <li>Error path: 4xx → Mono error</li>
 *   <li>Empty choices → RuntimeException</li>
 *   <li>{@link AzureOpenAIAdapter#getProvider()} returns {@link ModelProvider#AZURE_OPENAI}</li>
 * </ul>
 */
class AzureOpenAIAdapterTest {

    private static final String DEPLOYMENT   = "my-gpt4o-deployment";
    private static final String API_VERSION  = "2024-02-01";
    private static final String API_KEY      = "test-azure-key";

    private WireMockServer wireMock;
    private AzureOpenAIAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        // Azure OpenAI uses api-key header (not Bearer token)
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .defaultHeader("api-key", API_KEY)
                .defaultHeader("Content-Type", "application/json")
                .build();

        adapter = new AzureOpenAIAdapter(webClient, DEPLOYMENT, API_VERSION);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("getProvider() returns AZURE_OPENAI")
    void getProvider_returnsAzureOpenAI() {
        assertThat(adapter.getProvider()).isEqualTo(ModelProvider.AZURE_OPENAI);
    }

    @Test
    @DisplayName("happy path: deployment in URL, api-version as query param, text from choices[0]")
    void complete_happyPath_extractsTextFromChoices() {
        // Azure URL: /openai/deployments/{deployment}/chat/completions?api-version=...
        wireMock.stubFor(post(urlPathEqualTo(
                        "/openai/deployments/" + DEPLOYMENT + "/chat/completions"))
                .withQueryParam("api-version", equalTo(API_VERSION))
                .withHeader("api-key", equalTo(API_KEY))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-azure-123",
                                  "object": "chat.completion",
                                  "choices": [
                                    {
                                      "message": {"role": "assistant", "content": "Paris"},
                                      "finish_reason": "stop"
                                    }
                                  ]
                                }
                                """)));

        StepVerifier.create(adapter.complete("Capital of France?", "gpt-4o"))
                .expectNext("Paris")
                .verifyComplete();
    }

    @Test
    @DisplayName("request URL contains correct deployment name")
    void complete_requestUrl_containsDeploymentName() {
        wireMock.stubFor(post(urlPathEqualTo(
                        "/openai/deployments/" + DEPLOYMENT + "/chat/completions"))
                .withQueryParam("api-version", equalTo(API_VERSION))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\": [{\"message\": {\"content\": \"ok\"}}]}")));

        StepVerifier.create(adapter.complete("hello", "gpt-4o"))
                .expectNext("ok")
                .verifyComplete();

        wireMock.verify(postRequestedFor(urlPathEqualTo(
                "/openai/deployments/" + DEPLOYMENT + "/chat/completions")));
    }

    @Test
    @DisplayName("authentication uses api-key header not Bearer token")
    void complete_authentication_usesApiKeyHeader() {
        wireMock.stubFor(post(urlPathMatching("/openai/deployments/.*/chat/completions"))
                .withHeader("api-key", equalTo(API_KEY))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\": [{\"message\": {\"content\": \"ok\"}}]}")));

        StepVerifier.create(adapter.complete("hello", "gpt-4o"))
                .expectNext("ok")
                .verifyComplete();

        wireMock.verify(postRequestedFor(urlPathMatching("/openai/deployments/.*/chat/completions"))
                .withHeader("api-key", equalTo(API_KEY)));
    }

    @Test
    @DisplayName("provider 429 rate limit error → Mono error propagated")
    void complete_providerReturns429_propagatesError() {
        wireMock.stubFor(post(urlPathMatching("/openai/deployments/.*/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withBody("{\"error\": {\"code\": \"RateLimitReached\"}}")));

        StepVerifier.create(adapter.complete("hello", "gpt-4o"))
                .expectErrorMatches(ex -> ex.getMessage().contains("429"))
                .verify();
    }

    @Test
    @DisplayName("empty choices array → RuntimeException")
    void complete_emptyChoices_throwsRuntimeException() {
        wireMock.stubFor(post(urlPathMatching("/openai/deployments/.*/chat/completions"))
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
