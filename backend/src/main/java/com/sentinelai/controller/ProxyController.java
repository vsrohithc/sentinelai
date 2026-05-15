package com.sentinelai.controller;

import com.sentinelai.dto.ProxyRequest;
import com.sentinelai.dto.ProxyResponse;
import com.sentinelai.service.ProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller exposing the SentinelAI proxy endpoint.
 *
 * <h2>Endpoint</h2>
 * <p>{@code POST /api/proxy} accepts a JSON request body ({@link ProxyRequest}),
 * delegates to {@link ProxyService} for the parallel AI + detection calls, and
 * returns the AI model's response as a {@link ProxyResponse}.</p>
 *
 * <h2>License key resolution</h2>
 * <p>The license key can be supplied two ways:</p>
 * <ol>
 *   <li>{@code X-Sentinel-License} HTTP header — preferred because the key
 *       does not appear in application request-body logs or access logs.</li>
 *   <li>{@code licenseKey} field in the JSON body — fallback for clients
 *       that cannot set custom headers.</li>
 * </ol>
 * <p>The header takes precedence. ProxyService resolves the effective key.</p>
 *
 * <h2>Error handling</h2>
 * <p>Reactor errors from the AI model call propagate as Mono errors and are
 * converted to standardised JSON error bodies by {@link com.sentinelai.exception.GlobalExceptionHandler}.
 * Upstream AI provider failures (raised as {@link com.sentinelai.exception.UpstreamApiException})
 * produce {@code 502 Bad Gateway}; validation errors produce {@code 400 Bad Request};
 * any unmapped exception falls back to {@code 500 Internal Server Error}.</p>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProxyController {

    /** Service that orchestrates the parallel AI + detection calls. */
    private final ProxyService proxyService;

    /**
     * Proxies a prompt to the configured AI model and returns the response.
     *
     * <p>The full audit record (prompt, response, risk score) is persisted
     * asynchronously after this method returns — the HTTP response is sent
     * before the database write completes.</p>
     *
     * <p>Example request:</p>
     * <pre>{@code
     * POST /api/proxy
     * X-Sentinel-License: sk-sentinel-...
     * Content-Type: application/json
     *
     * {
     *   "prompt": "Summarise the key risks in this contract.",
     *   "model": "gpt-4o",
     *   "provider": "OPENAI",
     *   "metadata": { "user_id": "u123", "department": "legal" }
     * }
     * }</pre>
     *
     * <p>Example response:</p>
     * <pre>{@code
     * 200 OK
     * {
     *   "requestId": "a3f7c2d1-...",
     *   "model": "gpt-4o",
     *   "responseText": "The key risks are..."
     * }
     * }</pre>
     *
     * @param request    the proxy request body containing prompt, model, provider, metadata
     * @param licenseKey the tenant license key from the {@code X-Sentinel-License} header;
     *                   null if the header is absent (free-tier callers omit it)
     * @return a {@code Mono<ResponseEntity<ProxyResponse>>} with HTTP 200 and the AI response,
     *         or an error Mono that Spring WebFlux converts to an appropriate HTTP error status
     */
    @PostMapping(
            value = "/proxy",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ProxyResponse>> proxy(
            @Valid @RequestBody ProxyRequest request,
            @RequestHeader(value = "X-Sentinel-License", required = false) String licenseKey) {

        log.debug("Received proxy request model={} provider={}", request.getModel(), request.getProvider());

        return proxyService.proxy(request, licenseKey)
                // Wrap in ResponseEntity so we have control over status codes.
                // HTTP 200 OK is the correct status for a successful proxy response —
                // the underlying AI call succeeded and a response was returned.
                .map(ResponseEntity::ok);
    }
}
