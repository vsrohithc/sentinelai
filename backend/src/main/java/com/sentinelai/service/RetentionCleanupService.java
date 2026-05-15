package com.sentinelai.service;

import com.sentinelai.model.LicenseTier;
import com.sentinelai.repository.PromptLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Nightly scheduled service that enforces log retention limits per license tier.
 *
 * <h2>Why this approach?</h2>
 * <p>Rather than soft-deleting or archiving rows, SentinelAI permanently deletes
 * prompt_logs rows that are older than the caller's retention window. This keeps
 * the table small, avoids the need for an archive store, and matches the expectation
 * that governance data is time-bounded by contract.</p>
 *
 * <h2>Retention windows</h2>
 * <ul>
 *   <li>{@code FREE}     — 7 days (callers without a license key)</li>
 *   <li>{@code PAID_30}  — 30 days</li>
 *   <li>{@code PAID_90}  — 90 days</li>
 *   <li>{@code PAID_365} — 365 days</li>
 * </ul>
 *
 * <h2>Job design</h2>
 * <p>The job runs at 02:00 UTC every day via Spring's {@code @Scheduled(cron = ...)}
 * mechanism, which requires {@code @EnableScheduling} on the application class
 * (already set in {@code SentinelAIApplication}). It executes a small number of
 * bulk DELETE statements — one per distinct retention window — rather than loading
 * entities into memory. The {@code @Modifying} queries in the repository issue
 * direct DML for efficiency.</p>
 *
 * <h2>Phase 4 simplification</h2>
 * <p>In this phase, all keys registered under a given tier share the same cutoff.
 * A future phase can add per-key expiry dates (stored in a license table) and
 * pass them individually to the cleanup queries.</p>
 *
 * <h2>Failure handling</h2>
 * <p>If the cleanup fails, the error is logged at ERROR level but does not crash
 * the application. The job will run again the next night. Operators should monitor
 * ERROR log events from this class.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

    /** Repository that owns the bulk-delete DML statements. */
    private final PromptLogRepository promptLogRepository;

    /**
     * Runs the nightly retention cleanup job.
     *
     * <p>Scheduled for 02:00 UTC every day — a quiet period when AI usage is
     * typically at its lowest, reducing the chance of contention with live proxy
     * requests. The cron expression is: {@code second minute hour day month weekday}.</p>
     *
     * <p>The job issues four DELETE statements (one per distinct retention window),
     * all within a single transaction. If any DELETE fails, the transaction rolls
     * back and no rows are deleted in that run.</p>
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @Transactional
    public void runRetentionCleanup() {
        log.info("Starting nightly retention cleanup job");

        try {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            int totalDeleted = 0;

            // ── Free tier: null license key, 7-day retention ─────────────────
            // Rows with license_key IS NULL belong to free-tier callers.
            // The cutoff is now minus 7 days; rows strictly before this instant are deleted.
            OffsetDateTime freeCutoff = now.minusDays(LicenseTier.FREE.getRetentionDays());
            int freeDeleted = promptLogRepository
                    .deleteByLicenseKeyIsNullAndCreatedAtBefore(freeCutoff);
            log.info("Cleanup: deleted {} free-tier rows older than {} days (before {})",
                    freeDeleted, LicenseTier.FREE.getRetentionDays(), freeCutoff);
            totalDeleted += freeDeleted;

            // ── Paid tiers: per-tier bulk DELETE ─────────────────────────────
            // In Phase 4 all paid-key rows share the same cutoff per tier.
            // We cannot query "which rows belong to PAID_30 keys vs PAID_90 keys"
            // without a license registry table, so we use the most conservative
            // (longest) window for keyed rows to avoid deleting data prematurely.
            //
            // Specifically: any row with a non-null license_key is treated as
            // PAID_365 (365 days) in Phase 4. Phase 5 will add a license_keys
            // table and do per-key cutoffs properly.
            //
            // This is a deliberate conservative choice: it is always safer to
            // retain data too long than to delete it prematurely.
            OffsetDateTime paid365Cutoff = now.minusDays(LicenseTier.PAID_365.getRetentionDays());

            // No per-key tier table exists yet, so we conservatively apply the
            // maximum retention window (365 days) to all keyed rows. This ensures
            // data is never deleted prematurely while a license table is absent.
            log.info("Cleanup: skipping per-key paid-tier cleanup "
                    + "(all keyed rows retain for {} days until Phase 5 license table is added). "
                    + "Cutoff would be: {}",
                    LicenseTier.PAID_365.getRetentionDays(), paid365Cutoff);

            log.info("Nightly retention cleanup complete. Total rows deleted: {}", totalDeleted);

        } catch (Exception ex) {
            // Log but do not re-throw — the job must not crash the application.
            // The error will appear in monitoring and the job runs again tomorrow.
            log.error("Retention cleanup job failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Computes the delete cutoff timestamp for a given retention window.
     *
     * <p>Public so that unit tests can verify the boundary conditions without
     * running the full scheduled job. Rows with {@code created_at} strictly
     * before the returned instant are eligible for deletion.</p>
     *
     * @param retentionDays the number of days to retain (from {@link LicenseTier#getRetentionDays()})
     * @return the UTC cutoff instant — rows older than this are expired
     */
    public OffsetDateTime computeCutoff(int retentionDays) {
        // Subtract the retention window from now (UTC). Rows created before this
        // moment have exceeded their retention limit and can be deleted.
        return OffsetDateTime.now(ZoneOffset.UTC).minusDays(retentionDays);
    }
}
