package com.sentinelai.controller;

import com.sentinelai.dto.PromptLogDto;
import com.sentinelai.dto.VerificationResultDto;
import com.sentinelai.model.PromptLog;
import com.sentinelai.repository.PromptLogRepository;
import com.sentinelai.service.LogQueryService;
import com.sentinelai.service.SigningService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
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
    private final SigningService signingService;
    private final PromptLogRepository promptLogRepository;

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
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Verifies the Ed25519 signature of a single audit log entry.
     *
     * <p>Re-fetches the raw entity (not the DTO) so the canonical form can be
     * recomputed over the original field values and compared to the stored signature.</p>
     *
     * @param id the UUID of the log entry to verify
     * @return 200 with {@code valid: true} if signature matches, {@code valid: false} if tampered,
     *         or 404 if the record does not exist
     */
    @GetMapping("/{id}/verify")
    public ResponseEntity<VerificationResultDto> verifyLog(@PathVariable UUID id) {
        return promptLogRepository.findById(id)
                .map(log -> ResponseEntity.ok(buildVerificationResult(log)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns the Ed25519 public key in PEM format.
     *
     * <p>Auditors can download this key and use it to verify any record's signature
     * offline without contacting this service.</p>
     *
     * @return 200 with the PEM public key, or 404 if signing is not configured
     */
    @GetMapping(value = "/public-key", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getPublicKey() {
        String pem = signingService.getPublicKeyPem();
        if (pem == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(pem);
    }

    private VerificationResultDto buildVerificationResult(PromptLog log) {
        if (!signingService.isEnabled()) {
            return new VerificationResultDto(false, log.getId(), "Ed25519", "Signing is not configured on this server");
        }
        if (log.getSignature() == null) {
            return new VerificationResultDto(false, log.getId(), "Ed25519", "Record has no signature — created before signing was enabled");
        }
        boolean valid = signingService.verify(log);
        String reason = valid ? null : "Signature mismatch — record has been modified after signing";
        return new VerificationResultDto(valid, log.getId(), "Ed25519", reason);
    }
}
