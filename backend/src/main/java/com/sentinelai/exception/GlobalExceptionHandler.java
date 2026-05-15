package com.sentinelai.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized exception handler for all REST controllers.
 *
 * <p>Converts well-known exception types to structured JSON error bodies so that
 * every HTTP error has the same envelope regardless of which controller or service
 * threw it. The error envelope is intentionally minimal:</p>
 *
 * <pre>{@code
 * {
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "Validation failed",
 *   "timestamp": "2024-01-15T10:30:00Z",
 *   "details": [
 *     { "field": "prompt", "message": "prompt must not be blank" }
 *   ]
 * }
 * }</pre>
 *
 * <p>Implementation note: {@link WebExchangeBindException} is the WebFlux equivalent
 * of Spring MVC's {@code MethodArgumentNotValidException}. It is thrown when a
 * {@code @RequestBody} annotated with {@code @Valid} fails Bean Validation.</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ─── Validation errors (400) ──────────────────────────────────────────────

    /**
     * Handles {@code @Valid} / {@code @Validated} constraint violations on request
     * bodies. Collects all field-level errors and returns them as a structured list
     * so clients know exactly which fields are invalid.
     *
     * @param ex the binding exception thrown by Spring WebFlux validation
     * @return HTTP 400 with per-field error details
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleValidation(WebExchangeBindException ex) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    if (error instanceof FieldError fe) {
                        entry.put("field", fe.getField());
                        entry.put("message", fe.getDefaultMessage());
                    } else {
                        entry.put("object", error.getObjectName());
                        entry.put("message", error.getDefaultMessage());
                    }
                    return entry;
                })
                .collect(Collectors.toList());

        log.debug("Validation failed: {}", fieldErrors);

        Map<String, Object> body = errorBody(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                fieldErrors);

        return Mono.just(ResponseEntity.badRequest().body(body));
    }

    // ─── Explicit status exceptions (4xx / 5xx) ────────────────────────────────

    /**
     * Handles {@link ResponseStatusException} — the idiomatic WebFlux way to
     * throw HTTP errors with a specific status from service or controller code.
     *
     * <p>Examples that land here:</p>
     * <ul>
     *   <li>{@code new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found")}</li>
     *   <li>{@code new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI model unreachable")}</li>
     * </ul>
     *
     * @param ex the exception containing the desired HTTP status and reason
     * @return the HTTP status from the exception with a structured JSON body
     */
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        if (status.is5xxServerError()) {
            log.error("ResponseStatusException: {}", ex.getReason(), ex);
        } else {
            log.debug("ResponseStatusException status={} reason={}", status, ex.getReason());
        }

        Map<String, Object> body = errorBody(status, ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(), null);
        return Mono.just(ResponseEntity.status(status).body(body));
    }

    // ─── 404 for unknown routes ────────────────────────────────────────────────

    /**
     * Handles requests for routes that do not exist. Without this handler Spring
     * WebFlux returns a default HTML error page which is inconsistent with the
     * JSON API contract.
     *
     * @param ex the exception thrown by the resource handler
     * @return HTTP 404 with a JSON body
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleNoResource(NoResourceFoundException ex) {
        log.debug("No handler for path: {}", ex.getReason());
        Map<String, Object> body = errorBody(HttpStatus.NOT_FOUND, "The requested path does not exist", null);
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(body));
    }

    // ─── Upstream provider errors (502) ───────────────────────────────────────

    /**
     * Handles {@link UpstreamApiException} — failures originating in an
     * upstream AI provider (OpenAI, Anthropic, Gemini, Azure) or the
     * detection API.
     *
     * <p>Maps to <strong>502 Bad Gateway</strong>, the semantically correct
     * status when SentinelAI itself is healthy but a service it depends on is
     * not. The response body carries {@code provider} and {@code upstreamStatus}
     * fields so clients can implement provider-specific retry logic without
     * parsing the message string.</p>
     *
     * <p>The full upstream body is intentionally not echoed to the caller —
     * provider error bodies sometimes contain prompt fragments or internal
     * diagnostics that we do not want to leak. The body is logged at ERROR
     * for operator debugging.</p>
     *
     * @param ex the upstream provider exception
     * @return HTTP 502 with a structured JSON body
     */
    @ExceptionHandler(UpstreamApiException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleUpstream(UpstreamApiException ex) {
        log.error("Upstream provider failure: provider={} status={} body={}",
                ex.getProvider(), ex.getUpstreamStatus(), ex.getUpstreamBody());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.BAD_GATEWAY.value());
        body.put("error", HttpStatus.BAD_GATEWAY.getReasonPhrase());
        body.put("message", "Upstream provider returned an error.");
        body.put("provider", ex.getProvider());
        body.put("upstreamStatus", ex.getUpstreamStatus());
        body.put("timestamp", Instant.now().toString());

        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body));
    }

    // ─── Catch-all (500) ──────────────────────────────────────────────────────

    /**
     * Safety net for any exception not matched by a more specific handler. Logs the
     * full stack trace (important for diagnosing unexpected failures) and returns
     * a generic 500 without leaking internal details to the caller.
     *
     * @param ex any unhandled throwable
     * @return HTTP 500 with a generic JSON body
     */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        Map<String, Object> body = errorBody(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again or contact support.",
                null);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    /**
     * Constructs a consistent error response envelope.
     *
     * @param status  HTTP status for the response
     * @param message human-readable summary of what went wrong
     * @param details optional list of per-field detail maps (validation errors)
     * @return a {@link LinkedHashMap} whose key order matches the documented shape
     */
    private Map<String, Object> errorBody(HttpStatus status, String message, Object details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        if (details != null) {
            body.put("details", details);
        }
        return body;
    }
}
