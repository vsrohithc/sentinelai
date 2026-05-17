package com.sentinelai.service;

import com.sentinelai.dto.DashboardStatsDto;
import com.sentinelai.dto.DashboardStatsDto.DailyRiskPoint;
import com.sentinelai.dto.PromptLogDto;
import com.sentinelai.model.PromptLog;
import com.sentinelai.repository.PromptLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only service for querying audit log data and producing dashboard statistics.
 *
 * <p>All methods in this service are read-only — they never write to the database.
 * Write operations are handled exclusively by {@link AuditService}. Keeping the
 * read and write paths in separate services makes it easy to apply different
 * caching, transaction, and scaling strategies to each.</p>
 *
 * <h2>Risk score thresholds</h2>
 * <ul>
 *   <li>HIGH:   risk_score >= 0.7</li>
 *   <li>MEDIUM: risk_score >= 0.4 and < 0.7</li>
 *   <li>LOW:    risk_score >= 0.0 and < 0.4</li>
 *   <li>NULL:   detection API failed — risk unknown</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogQueryService {

    /** Lower bound of the HIGH risk tier (inclusive). */
    private static final BigDecimal HIGH_RISK_THRESHOLD = new BigDecimal("0.7");

    /** Lower bound of the MEDIUM risk tier (inclusive). */
    private static final BigDecimal MEDIUM_RISK_THRESHOLD = new BigDecimal("0.4");

    /** Upper bound of the LOW risk tier (exclusive = MEDIUM lower bound). */
    private static final BigDecimal LOW_RISK_MAX = new BigDecimal("0.4");

    /** Number of days of history returned in the daily chart series. */
    private static final int CHART_DAYS = 30;

    private final PromptLogRepository promptLogRepository;

    /**
     * Returns a paginated, optionally filtered page of audit log entries.
     *
     * <p>All filter parameters are nullable — passing {@code null} skips that filter.
     * Results are always ordered by request_time descending (newest first).</p>
     *
     * @param from    include only rows at or after this time; null to skip
     * @param to      include only rows at or before this time; null to skip
     * @param minRisk include only rows with risk_score >= minRisk; null to skip
     * @param maxRisk include only rows with risk_score <= maxRisk; null to skip
     * @param page    zero-based page index
     * @param size    number of records per page (capped at 200 to prevent runaway queries)
     * @return a page of {@link PromptLogDto} objects
     */
    @Transactional(readOnly = true)
    public Page<PromptLogDto> getLogs(
            OffsetDateTime from,
            OffsetDateTime to,
            BigDecimal minRisk,
            BigDecimal maxRisk,
            int page,
            int size) {

        // Cap page size to prevent clients from accidentally fetching millions of rows.
        int safeSize = Math.min(size, 200);

        PageRequest pageable = PageRequest.of(page, safeSize,
                Sort.by(Sort.Direction.DESC, "requestTime"));

        // Build a Specification from whichever filters were supplied.
        // JpaSpecificationExecutor handles null predicates cleanly — no Hibernate 6
        // typed-null-parameter issues that arise with ":param IS NULL" in JPQL.
        Specification<PromptLog> spec = Specification.where(null);
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("requestTime"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("requestTime"), to));
        }
        if (minRisk != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("riskScore"), minRisk));
        }
        if (maxRisk != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("riskScore"), maxRisk));
        }

        Page<PromptLog> entities = promptLogRepository.findAll(spec, pageable);

        // Map each JPA entity to the DTO; avoids exposing the entity outside the service layer.
        return entities.map(this::toDto);
    }

    /**
     * Returns a single audit log entry by its ID, or empty if not found.
     *
     * @param id the UUID primary key
     * @return the matching log entry, or {@link Optional#empty()} if not found
     */
    @Transactional(readOnly = true)
    public Optional<PromptLogDto> getLog(UUID id) {
        return promptLogRepository.findById(id).map(this::toDto);
    }

    /**
     * Computes summary statistics for the Dashboard page.
     *
     * <p>The daily series covers the last {@value #CHART_DAYS} calendar days,
     * ordered oldest-first so Recharts can plot them left-to-right without
     * any client-side sorting.</p>
     *
     * @return a {@link DashboardStatsDto} with counts and the chart series
     */
    @Transactional(readOnly = true)
    public DashboardStatsDto getDashboardStats() {
        long total = promptLogRepository.count();

        // Count rows in each risk band. These three queries use the indexed risk_score column.
        long highCount   = promptLogRepository.countByRiskScoreGreaterThanEqual(HIGH_RISK_THRESHOLD);
        long mediumCount = promptLogRepository.countByRiskScoreBetween(MEDIUM_RISK_THRESHOLD, HIGH_RISK_THRESHOLD);
        long lowCount    = promptLogRepository.countByRiskScoreBetween(BigDecimal.ZERO, LOW_RISK_MAX);
        long nullCount   = promptLogRepository.countByRiskScoreIsNull();

        // Build the daily chart series for the last CHART_DAYS days.
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(CHART_DAYS);
        List<Object[]> rows = promptLogRepository.findDailyRiskCounts(since);

        List<DailyRiskPoint> series = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            // row layout: [LocalDate, highCount, mediumCount, lowCount]
            // row[0] is a java.time.LocalDate produced by the JPQL cast; toString() yields "YYYY-MM-DD".
            String date       = row[0].toString();
            long high         = toLong(row[1]);
            long medium       = toLong(row[2]);
            long low          = toLong(row[3]);
            series.add(new DailyRiskPoint(date, high, medium, low));
        }

        return new DashboardStatsDto(total, highCount, mediumCount, lowCount, nullCount, series);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Maps a {@link PromptLog} JPA entity to a {@link PromptLogDto}.
     *
     * <p>This is a simple field-for-field copy. If the DTO shape ever diverges
     * from the entity shape (e.g. computed fields, redaction), this is the only
     * place that needs to change.</p>
     *
     * @param log the entity to convert
     * @return the corresponding DTO
     */
    private PromptLogDto toDto(PromptLog log) {
        return new PromptLogDto(
                log.getId(),
                log.getRequestTime(),
                log.getModel(),
                log.getProvider(),
                log.getPrompt(),
                log.getResponse(),
                log.getRiskScore(),
                log.getMetadata(),
                log.getLicenseKey(),
                log.getCreatedAt(),
                log.getSignature()
        );
    }

    /**
     * Converts a native query aggregate result cell (which arrives as a Number
     * subtype such as Long or BigInteger depending on the JDBC driver) to a
     * plain {@code long}.
     *
     * @param obj the raw result cell; must be a {@link Number}
     * @return the long value, or 0 if obj is null
     */
    private long toLong(Object obj) {
        if (obj == null) return 0L;
        return ((Number) obj).longValue();
    }
}
