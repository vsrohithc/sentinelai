package com.sentinelai.controller;

import com.sentinelai.dto.PromptLogDto;
import com.sentinelai.service.LogQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * REST controller exposing read-only audit log endpoints for the Dashboard UI.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/logs}        — paginated list of audit log entries with optional filters</li>
 *   <li>{@code GET /api/logs/{id}}   — single audit log entry (full prompt + response for drawer)</li>
 * </ul>
 *
 * <p>All reads are delegated to {@link LogQueryService}. This controller contains
 * no business logic — its sole responsibility is HTTP binding and response shaping.</p>
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogQueryService logQueryService;

    /**
     * Returns a paginated list of audit log entries, newest first.
     *
     * <p>All query parameters are optional. Omitting a parameter skips that filter.
     * The {@code page} and {@code size} parameters follow Spring Data's zero-based
     * page index convention.</p>
     *
     * <h3>Example</h3>
     * <pre>
     * GET /api/logs?page=0&size=25&minRisk=0.7
     * </pre>
     *
     * @param from    ISO-8601 datetime — include only rows at or after this time
     * @param to      ISO-8601 datetime — include only rows at or before this time
     * @param minRisk decimal 0.0–1.0 — include only rows with risk_score >= minRisk
     * @param maxRisk decimal 0.0–1.0 — include only rows with risk_score <= maxRisk
     * @param page    zero-based page index (default 0)
     * @param size    page size (default 25; capped at 200 by the service layer)
     * @return 200 OK with a page of {@link PromptLogDto} objects
     */
    @GetMapping
    public ResponseEntity<Page<PromptLogDto>> getLogs(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime from,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime to,

            @RequestParam(required = false) BigDecimal minRisk,
            @RequestParam(required = false) BigDecimal maxRisk,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        Page<PromptLogDto> result = logQueryService.getLogs(from, to, minRisk, maxRisk, page, size);
        return ResponseEntity.ok(result);
    }

    /**
     * Returns a single audit log entry by its UUID primary key.
     *
     * <p>Used by the Audit Log detail drawer to load the full prompt and
     * response text without re-fetching the entire list.</p>
     *
     * @param id the UUID of the log entry to retrieve
     * @return 200 OK with the {@link PromptLogDto}, or 404 Not Found if the ID does not exist
     */
    @GetMapping("/{id}")
    public ResponseEntity<PromptLogDto> getLog(@PathVariable UUID id) {
        return logQueryService.getLog(id)
                .map(ResponseEntity::ok)
                // Return 404 so the frontend can distinguish "not found" from server errors.
                .orElse(ResponseEntity.notFound().build());
    }
}
