package com.sentinelai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sentinelai.dto.ProxyRequest;
import com.sentinelai.dto.ProxyResponse;
import com.sentinelai.repository.PromptLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code POST /api/proxy} endpoint.
 *
 * <h2>What this test verifies</h2>
 * <ol>
 *   <li>The endpoint returns HTTP 200 and a {@link ProxyResponse} containing
 *       the AI model's response text.</li>
 *   <li>A {@code prompt_logs} row is created in the database with the correct
 *       prompt, model, response, and risk score.</li>
 *   <li>When the detection API times out or errors, the proxy still succeeds
 *       and the row is persisted with {@code risk_score = null}.</li>
 *   <li>The license key from the {@code X-Sentinel-License} header is stored
 *       correctly in the audit record.</li>
 * </ol>
 *
 * <h2>Test infrastructure</h2>
 * <p>WireMock stubs replace both the AI model API and the detection API.
 * Two WireMock servers are started on random ports before each test and
 * registered as dynamic Spring properties so the WebClient beans pick up
 * the correct URLs.</p>
 *
 * <p>H2 in-memory database (PostgreSQL compatibility mode) replaces
 * PostgreSQL. Flyway migrations run against H2, so the schema is identical
 * to production except for dialect differences.</p>
 *
 * <p>{@code @DirtiesContext} marks the Spring ApplicationContext as dirty
 * after this test class so WireMock port registrations don't bleed into
 * other test classes.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProxyIntegrationTest {

    // ── WireMock servers ─────────────────────────────────────────────────────

    /** WireMock server acting as the AI model API (OpenAI Chat Completions). */
    private static WireMockServer aiModelServer;

    /** WireMock server acting as the injection detection API. */
    private static WireMockServer detectionServer;

    // ── Spring injection ─────────────────────────────────────────────────────

    /** The random port the Spring Boot test server started on. */
    @LocalServerPort
    private int port;

    /** WebTestClient wired to the test server for issuing HTTP requests. */
    @Autowired
    private WebTestClient webTestClient;

    /** Direct access to the repository so tests can assert DB state. */
    @Autowired
    private PromptLogRepository promptLogRepository;

    // ── Test constants ────────────────────────────────────────────────────────

    private static final String TEST_PROMPT      = "What is the capital of France?";
    private static final String TEST_MODEL       = "gpt-4o";
    private static final String TEST_RESPONSE    = "The capital of France is Paris.";
    private static final String TEST_LICENSE_KEY = "sk-sentinel-test-key";

    // ── WireMock dynamic property registration ────────────────────────────────

    /**
     * Starts WireMock servers on random ports BEFORE the Spring context is
     * initialised, then registers the ports as dynamic Spring properties so
     * the {@code sentinelai.ai-model.api-url} and
     * {@code sentinelai.detection.api-url} properties point at the stubs.
     *
     * <p>This is the correct pattern for WireMock + @SpringBootTest because
     * the ApplicationContext is created once and reused across tests in the
     * class — we need the ports before context startup.</p>
     *
     * @param registry Spring's dynamic property registry, populated before context starts
     */
    @DynamicPropertySource
    static void registerWireMockPorts(DynamicPropertyRegistry registry) {
        // Start servers on random ports to avoid conflicts in parallel test runs
        aiModelServer  = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        detectionServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());

        aiModelServer.start();
        detectionServer.start();

        // Override the Spring properties so the WebClient beans created by
        // ProviderConfig and DetectionConfig point at the WireMock stubs.
        registry.add("sentinelai.providers.openai.api-url",
                () -> "http://localhost:" + aiModelServer.port());
        registry.add("sentinelai.detection.external.api-url",
                () -> "http://localhost:" + detectionServer.port());
    }

    // ── Setup / teardown ──────────────────────────────────────────────────────

    /**
     * Resets WireMock stub mappings and clears the database before each test
     * so tests are fully isolated from each other.
     */
    @BeforeEach
    void setUp() {
        aiModelServer.resetAll();
        detectionServer.resetAll();
        promptLogRepository.deleteAll();
    }

    /**
     * Stops WireMock servers after the test class is done (not after each test,
     * since @DynamicPropertySource servers must outlive individual tests).
     */
    @AfterEach
    void tearDown() {
        // Servers are stopped by @DirtiesContext cleanup; individual tests just reset stubs
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Happy path: both AI model and detection APIs respond successfully.
     *
     * <p>Verifies that:</p>
     * <ul>
     *   <li>HTTP 200 is returned with the correct response text and request ID.</li>
     *   <li>One {@code prompt_logs} row is created with the correct fields.</li>
     *   <li>The risk score from the detection API is stored in the audit record.</li>
     * </ul>
     */
    @Test
    @DisplayName("POST /api/proxy returns AI response and creates audit record")
    void proxyRequest_happyPath_returnsResponseAndCreatesAuditRecord() throws InterruptedException {
        // ── Arrange WireMock stubs ───────────────────────────────────────────

        // Stub the AI model API (OpenAI Chat Completions format)
        aiModelServer.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", containing("Bearer"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(buildOpenAiResponse(TEST_RESPONSE, TEST_MODEL))));

        // Stub the detection API to return a moderate risk score
        detectionServer.stubFor(post(urlEqualTo("/analyze"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"score\": 0.2500}")));

        // ── Act ──────────────────────────────────────────────────────────────

        ProxyRequest request = ProxyRequest.builder()
                .prompt(TEST_PROMPT)
                .model(TEST_MODEL)
                .provider("OPENAI")
                .metadata(Map.of("user_id", "u123"))
                .build();

        var exchangeResult = webTestClient
                .mutate()
                // Give the test extra time for async DB write to complete
                .responseTimeout(Duration.ofSeconds(10))
                .build()
                .post().uri("/api/proxy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Sentinel-License", TEST_LICENSE_KEY)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                // X-Request-Id must always be present — set by RequestContextFilter
                .expectHeader().exists("X-Request-Id")
                .expectBody(ProxyResponse.class)
                .returnResult();

        ProxyResponse response = exchangeResult.getResponseBody();

        // ── Assert HTTP response ─────────────────────────────────────────────

        assertThat(response).isNotNull();
        assertThat(response.getResponseText()).isEqualTo(TEST_RESPONSE);
        assertThat(response.getModel()).isEqualTo(TEST_MODEL);
        assertThat(response.getRequestId()).isNotNull();

        // X-Request-Id in response must be a valid UUID (generated by RequestContextFilter)
        String responseRequestId = exchangeResult.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(responseRequestId)
                .isNotBlank()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        // ── Assert DB audit record ────────────────────────────────────────────
        // The async write may take a short moment; wait up to 2 seconds.
        awaitAuditRecord(1L, 2000);

        var logs = promptLogRepository.findAll();
        assertThat(logs).hasSize(1);

        var log = logs.get(0);
        assertThat(log.getId()).isEqualTo(response.getRequestId());
        assertThat(log.getPrompt()).isEqualTo(TEST_PROMPT);
        assertThat(log.getModel()).isEqualTo(TEST_MODEL);
        assertThat(log.getResponse()).isEqualTo(TEST_RESPONSE);
        assertThat(log.getLicenseKey()).isEqualTo(TEST_LICENSE_KEY);
        assertThat(log.getRiskScore()).isNotNull();
        assertThat(log.getRiskScore().doubleValue()).isEqualTo(0.25d);
        assertThat(log.getRequestTime()).isNotNull();
    }

    /**
     * Detection failure scenario: the detection API returns a 500 error.
     *
     * <p>Verifies that the proxy still returns HTTP 200 with the AI response,
     * and that the audit record is created with {@code risk_score = null}.</p>
     */
    @Test
    @DisplayName("POST /api/proxy succeeds with null risk_score when detection API fails")
    void proxyRequest_detectionFails_returnsResponseWithNullRiskScore() throws InterruptedException {
        // ── Arrange ──────────────────────────────────────────────────────────

        aiModelServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(buildOpenAiResponse(TEST_RESPONSE, TEST_MODEL))));

        // Detection API returns 500 — should be swallowed by DetectionClient
        detectionServer.stubFor(post(urlEqualTo("/analyze"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        // ── Act ──────────────────────────────────────────────────────────────

        ProxyRequest request = ProxyRequest.builder()
                .prompt(TEST_PROMPT)
                .model(TEST_MODEL)
                .provider("OPENAI")
                .build();

        ProxyResponse response = webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/proxy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProxyResponse.class)
                .returnResult()
                .getResponseBody();

        // ── Assert ───────────────────────────────────────────────────────────

        assertThat(response).isNotNull();
        assertThat(response.getResponseText()).isEqualTo(TEST_RESPONSE);

        awaitAuditRecord(1L, 2000);

        var logs = promptLogRepository.findAll();
        assertThat(logs).hasSize(1);
        // risk_score must be null — not 0.0 — when detection failed
        assertThat(logs.get(0).getRiskScore()).isNull();
    }

    /**
     * License key fallback: key supplied in request body, not header.
     *
     * <p>Verifies that the license key in the request body is stored in the
     * audit record when no header is present.</p>
     */
    @Test
    @DisplayName("License key from request body is stored in audit record")
    void proxyRequest_licenseKeyInBody_isPersistedToAuditRecord() throws InterruptedException {
        // ── Arrange ──────────────────────────────────────────────────────────

        aiModelServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(buildOpenAiResponse(TEST_RESPONSE, TEST_MODEL))));

        detectionServer.stubFor(post(urlEqualTo("/analyze"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"score\": 0.1000}")));

        // ── Act — supply license key in body, not header ──────────────────────

        String bodyLicenseKey = "sk-sentinel-body-key";
        ProxyRequest request = ProxyRequest.builder()
                .prompt(TEST_PROMPT)
                .model(TEST_MODEL)
                .provider("OPENAI")
                .licenseKey(bodyLicenseKey)
                .build();

        webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/proxy")
                .contentType(MediaType.APPLICATION_JSON)
                // No X-Sentinel-License header
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();

        // ── Assert ───────────────────────────────────────────────────────────

        awaitAuditRecord(1L, 2000);
        var logs = promptLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getLicenseKey()).isEqualTo(bodyLicenseKey);
    }

    /**
     * Validates Bean Validation on the request body: blank prompt → HTTP 400.
     *
     * <p>Verifies that:</p>
     * <ul>
     *   <li>HTTP 400 is returned (not 500) when {@code prompt} is blank.</li>
     *   <li>The response body contains a structured JSON error with {@code "status": 400}.</li>
     *   <li>The {@code "details"} array identifies the offending field as {@code "prompt"}.</li>
     * </ul>
     */
    @Test
    @DisplayName("POST /api/proxy with blank prompt → 400 Bad Request with validation details")
    void proxyRequest_blankPrompt_returns400WithValidationDetails() {
        ProxyRequest request = ProxyRequest.builder()
                .prompt("")          // violates @NotBlank
                .model(TEST_MODEL)
                .build();

        webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/proxy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.error").isEqualTo("Bad Request")
                .jsonPath("$.details[0].field").isEqualTo("prompt");
    }

    /**
     * Validates Bean Validation on the request body: missing model → HTTP 400.
     *
     * <p>The {@code model} field is {@code @NotBlank} — omitting it must
     * produce a 400 with a details entry pointing to the {@code model} field.</p>
     */
    @Test
    @DisplayName("POST /api/proxy with blank model → 400 Bad Request with validation details")
    void proxyRequest_blankModel_returns400WithValidationDetails() {
        ProxyRequest request = ProxyRequest.builder()
                .prompt(TEST_PROMPT)
                .model("")           // violates @NotBlank
                .build();

        webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/proxy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.details[0].field").isEqualTo("model");
    }

    /**
     * Verifies that every response from {@code POST /api/proxy} carries an
     * {@code X-Request-Id} header, even error responses (400, etc.).
     *
     * <p>The header is set by {@link com.sentinelai.filter.RequestContextFilter}
     * which runs before every other filter and controller, so it must always
     * be present regardless of the outcome.</p>
     */
    @Test
    @DisplayName("Every response (including 400) carries X-Request-Id correlation header")
    void everyResponse_carriesXRequestIdHeader() {
        // Use an invalid request that will be rejected with 400 — even error
        // responses must include X-Request-Id from RequestContextFilter.
        ProxyRequest badRequest = ProxyRequest.builder()
                .prompt("")
                .model("")
                .build();

        webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/proxy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(badRequest)
                .exchange()
                .expectHeader().exists("X-Request-Id");
    }

    /**
     * Verifies that a caller-supplied {@code X-Request-Id} header is echoed
     * back in the response, enabling cross-service request tracing.
     */
    @Test
    @DisplayName("Caller-supplied X-Request-Id is echoed in the response header")
    void callerSuppliedXRequestId_isEchoedInResponse() {
        aiModelServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(buildOpenAiResponse(TEST_RESPONSE, TEST_MODEL))));

        detectionServer.stubFor(post(urlEqualTo("/analyze"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"score\": 0.1}")));

        String callerTraceId = "upstream-trace-id-abc123";

        webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/proxy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", callerTraceId)
                .bodyValue(ProxyRequest.builder()
                        .prompt(TEST_PROMPT)
                        .model(TEST_MODEL)
                        .provider("OPENAI")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", callerTraceId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal OpenAI Chat Completions API response JSON string.
     *
     * @param content the response text the model "generated"
     * @param model   the model name to echo in the response
     * @return a JSON string matching the OpenAI API response format
     */
    private String buildOpenAiResponse(String content, String model) {
        return """
                {
                  "id": "chatcmpl-test123",
                  "object": "chat.completion",
                  "model": "%s",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "%s"
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """.formatted(model, content);
    }

    /**
     * Polls the repository until the expected number of audit records appear
     * or the timeout elapses.
     *
     * <p>Because {@code AuditService.save()} is {@code @Async}, the DB write
     * happens on a background thread after the HTTP response is returned.
     * Tests must wait briefly for the write to complete before asserting DB
     * state. A polling loop is simpler and more robust than {@code Thread.sleep()}
     * with a fixed delay.</p>
     *
     * @param expectedCount the number of records to wait for
     * @param timeoutMs     maximum milliseconds to wait before giving up
     * @throws InterruptedException if the polling thread is interrupted
     */
    private void awaitAuditRecord(long expectedCount, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (promptLogRepository.count() >= expectedCount) {
                return;
            }
            // Short poll interval — avoids busy-waiting without a long fixed sleep
            Thread.sleep(50);
        }
        // Don't throw here — let the assertion fail with a clear message about count
    }
}
