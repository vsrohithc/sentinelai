package com.sentinelai.repository;

import com.sentinelai.model.PromptLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PromptLog} entities.
 *
 * <p>Provides standard CRUD operations via JpaRepository and adds two
 * custom queries used by the retention cleanup job (Phase 4):</p>
 *
 * <ul>
 *   <li>{@link #deleteByLicenseKeyAndCreatedAtBefore} — deletes expired rows
 *       for a specific license key (per-tenant retention).</li>
 *   <li>{@link #deleteByLicenseKeyIsNullAndCreatedAtBefore} — deletes expired
 *       rows for free-tier callers (no license key).</li>
 * </ul>
 *
 * <p>All write paths are handled by {@code AuditService}, which calls
 * {@code save()} asynchronously after the proxy response has been returned.
 * No synchronous writes should ever occur on the hot request path.</p>
 */
@Repository
public interface PromptLogRepository extends JpaRepository<PromptLog, UUID>, JpaSpecificationExecutor<PromptLog> {

    /**
     * Counts rows for a given license key — used in tests and diagnostics
     * to verify that audit records are being persisted correctly.
     *
     * @param licenseKey the license key to filter by
     * @return the number of {@code prompt_logs} rows with the given key
     */
    long countByLicenseKey(String licenseKey);

    // Optional filtered paging is handled via JpaSpecificationExecutor.findAll(Specification, Pageable)
    // in LogQueryService — avoids Hibernate 6's typed-null-parameter limitation in JPQL.

    /**
     * Counts rows with risk_score >= the given threshold, used to compute
     * the high-risk and medium-risk summary counts on the dashboard.
     *
     * @param threshold lower bound (inclusive)
     * @return count of rows with risk_score >= threshold
     */
    long countByRiskScoreGreaterThanEqual(BigDecimal threshold);

    /**
     * Counts rows with risk_score in [minRisk, maxRisk) — used to compute
     * the medium-risk and low-risk bands on the dashboard.
     *
     * @param min lower bound (inclusive)
     * @param max upper bound (exclusive)
     * @return count of matching rows
     */
    long countByRiskScoreBetween(BigDecimal min, BigDecimal max);

    /**
     * Counts rows where risk_score IS NULL — i.e. requests where the detection
     * API timed out or errored.
     *
     * @return count of rows with no risk score
     */
    long countByRiskScoreIsNull();

    /**
     * Returns daily aggregate counts for the last {@code days} calendar days,
     * grouped by UTC date. Used to populate the risk trend line chart.
     *
     * <p>Each result row contains four elements:</p>
     * <ol>
     *   <li>{@code java.time.LocalDate} — the calendar date (UTC)</li>
     *   <li>{@code Long} — high-risk count (riskScore >= 0.7)</li>
     *   <li>{@code Long} — medium-risk count (0.4 <= riskScore < 0.7)</li>
     *   <li>{@code Long} — low-risk count (riskScore < 0.4)</li>
     * </ol>
     *
     * <p>Rows with null risk_score are excluded — they cannot be bucketed.
     * Results are ordered ascending so the chart series reads left-to-right.</p>
     *
     * <p>Written as JPQL (not a native query) so it runs correctly against both
     * PostgreSQL in production and H2 in the test profile. Hibernate 6 translates
     * {@code cast(... as LocalDate)} and {@code SUM(CASE WHEN ...)} to the
     * appropriate dialect SQL automatically.</p>
     *
     * @param since lower bound — rows with requestTime >= since are included
     * @return list of Object arrays: [LocalDate, highCount, mediumCount, lowCount]
     */
    @Query("""
            SELECT
                cast(l.requestTime as LocalDate),
                SUM(CASE WHEN l.riskScore >= 0.7                              THEN 1 ELSE 0 END),
                SUM(CASE WHEN l.riskScore >= 0.4 AND l.riskScore < 0.7        THEN 1 ELSE 0 END),
                SUM(CASE WHEN l.riskScore >= 0.0 AND l.riskScore < 0.4        THEN 1 ELSE 0 END)
            FROM PromptLog l
            WHERE l.requestTime >= :since
              AND l.riskScore IS NOT NULL
            GROUP BY cast(l.requestTime as LocalDate)
            ORDER BY cast(l.requestTime as LocalDate) ASC
            """)
    List<Object[]> findDailyRiskCounts(@Param("since") OffsetDateTime since);

    /**
     * Deletes all rows for the given license key that are older than the
     * supplied cutoff timestamp.
     *
     * <p>Called by the nightly retention cleanup job for paid-tier tenants.
     * The {@code @Modifying} annotation tells Spring Data to execute this as
     * a bulk DML statement rather than loading entities into memory first —
     * critical for tables with millions of rows.</p>
     *
     * @param licenseKey the license key whose old records should be purged
     * @param cutoff     delete rows with {@code created_at} strictly before this instant
     * @return the number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM PromptLog p WHERE p.licenseKey = :licenseKey AND p.createdAt < :cutoff")
    int deleteByLicenseKeyAndCreatedAtBefore(
            @Param("licenseKey") String licenseKey,
            @Param("cutoff") OffsetDateTime cutoff);

    /**
     * Deletes all rows with a null license key (free-tier callers) that are
     * older than the supplied cutoff timestamp.
     *
     * <p>Free-tier callers do not supply a license key, so their rows have
     * {@code license_key IS NULL}. This query handles that case separately
     * from the paid-tier query above.</p>
     *
     * @param cutoff delete rows with {@code created_at} strictly before this instant
     * @return the number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM PromptLog p WHERE p.licenseKey IS NULL AND p.createdAt < :cutoff")
    int deleteByLicenseKeyIsNullAndCreatedAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
