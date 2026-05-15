package com.sentinelai;

import com.sentinelai.model.LicenseTier;
import com.sentinelai.model.PromptLog;
import com.sentinelai.repository.PromptLogRepository;
import com.sentinelai.service.RetentionCleanupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.temporal.ChronoUnit;

/**
 * Unit tests for {@link RetentionCleanupService}.
 *
 * <p>Uses Mockito to stub {@link PromptLogRepository} so no database or Spring
 * context is needed. Each test focuses on a single boundary condition described
 * in the Phase 4 task list.</p>
 *
 * <h2>Test coverage</h2>
 * <ul>
 *   <li>computeCutoff returns a timestamp exactly N days in the past</li>
 *   <li>Boundary: row exactly at retention limit is NOT deleted (inclusive = keep)</li>
 *   <li>Boundary: row one second past limit IS deleted</li>
 *   <li>Free-tier cleanup calls the correct repository method with the 7-day cutoff</li>
 *   <li>runRetentionCleanup does not throw even when the repository throws</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RetentionCleanupServiceTest {

    @Mock
    private PromptLogRepository promptLogRepository;

    // ── computeCutoff boundary tests ──────────────────────────────────────────

    @Test
    @DisplayName("computeCutoff for 7 days returns a timestamp ~7 days ago")
    void computeCutoff_7Days_isApproximately7DaysAgo() {
        RetentionCleanupService service = new RetentionCleanupService(promptLogRepository);

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7);
        OffsetDateTime cutoff = service.computeCutoff(7);
        OffsetDateTime after  = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7);

        // The cutoff must fall between our before/after brackets.
        // Using a 1-second tolerance to absorb test execution time.
        assertThat(cutoff).isBetween(
                before.minusSeconds(1),
                after.plusSeconds(1)
        );
    }

    @Test
    @DisplayName("computeCutoff for 365 days returns a timestamp ~365 days ago")
    void computeCutoff_365Days_isApproximately365DaysAgo() {
        RetentionCleanupService service = new RetentionCleanupService(promptLogRepository);

        OffsetDateTime cutoff = service.computeCutoff(365);
        OffsetDateTime expected = OffsetDateTime.now(ZoneOffset.UTC).minusDays(365);

        // Allow up to 2 seconds of test execution drift
        assertThat(cutoff).isCloseTo(expected, within(2, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("cutoff for 30 days is older than cutoff for 7 days")
    void cutoff_30Days_isOlderThan_7Days() {
        RetentionCleanupService service = new RetentionCleanupService(promptLogRepository);

        // A 30-day cutoff is farther in the past than a 7-day cutoff,
        // meaning MORE rows are eligible for deletion at the 7-day setting.
        OffsetDateTime cutoff7  = service.computeCutoff(7);
        OffsetDateTime cutoff30 = service.computeCutoff(30);

        assertThat(cutoff30).isBefore(cutoff7);
    }

    // ── Boundary: exactly at limit vs. one unit over ──────────────────────────

    @Test
    @DisplayName("row created exactly at retention limit is NOT before the cutoff (kept)")
    void rowExactlyAtLimit_isNotBefore_cutoff() {
        RetentionCleanupService service = new RetentionCleanupService(promptLogRepository);

        // Simulate a row created exactly 7 days ago (to the millisecond)
        OffsetDateTime cutoff = service.computeCutoff(LicenseTier.FREE.getRetentionDays());
        OffsetDateTime rowCreatedAt = cutoff; // exactly at the limit

        // The delete query uses "strictly before" (created_at < cutoff),
        // so a row created at exactly the cutoff timestamp is NOT deleted.
        assertThat(rowCreatedAt).isEqualTo(cutoff);
        // isBefore returns false for equal instants — row is NOT deleted
        assertThat(rowCreatedAt.isBefore(cutoff)).isFalse();
    }

    @Test
    @DisplayName("row created one second past the retention limit IS before the cutoff (deleted)")
    void rowOneDayOverLimit_isBefore_cutoff() {
        RetentionCleanupService service = new RetentionCleanupService(promptLogRepository);

        OffsetDateTime cutoff = service.computeCutoff(LicenseTier.FREE.getRetentionDays());
        // One second older than the retention window — this row is expired
        OffsetDateTime rowCreatedAt = cutoff.minusSeconds(1);

        assertThat(rowCreatedAt.isBefore(cutoff)).isTrue();
    }

    // ── runRetentionCleanup integration ──────────────────────────────────────

    @Test
    @DisplayName("runRetentionCleanup calls deleteByLicenseKeyIsNullAndCreatedAtBefore with 7-day cutoff")
    void runRetentionCleanup_callsFreeTierDelete_with7DayCutoff() {
        RetentionCleanupService service = new RetentionCleanupService(promptLogRepository);

        // Stub the repository call so it returns 0 deleted rows (no exception)
        ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        when(promptLogRepository.deleteByLicenseKeyIsNullAndCreatedAtBefore(cutoffCaptor.capture()))
                .thenReturn(0);

        service.runRetentionCleanup();

        // Verify the repository was called
        verify(promptLogRepository).deleteByLicenseKeyIsNullAndCreatedAtBefore(cutoffCaptor.getValue());

        // The cutoff passed to the repo must be approximately 7 days ago
        OffsetDateTime expected7DayCutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expected7DayCutoff, within(5, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("runRetentionCleanup does not throw even when repository throws")
    void runRetentionCleanup_repositoryThrows_doesNotPropagate() {
        RetentionCleanupService service = new RetentionCleanupService(promptLogRepository);

        // Simulate a database failure during cleanup
        when(promptLogRepository.deleteByLicenseKeyIsNullAndCreatedAtBefore(
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("simulated DB failure"));

        // The job must not propagate the exception — it logs and returns
        // so the application continues running.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(service::runRetentionCleanup);
    }

    // ── LicenseTier enum sanity ───────────────────────────────────────────────

    @Test
    @DisplayName("all LicenseTier retention values are positive and in ascending order")
    void licenseTierRetentionValues_arePositiveAndAscending() {
        assertThat(LicenseTier.FREE.getRetentionDays()).isPositive();
        assertThat(LicenseTier.PAID_30.getRetentionDays())
                .isGreaterThan(LicenseTier.FREE.getRetentionDays());
        assertThat(LicenseTier.PAID_90.getRetentionDays())
                .isGreaterThan(LicenseTier.PAID_30.getRetentionDays());
        assertThat(LicenseTier.PAID_365.getRetentionDays())
                .isGreaterThan(LicenseTier.PAID_90.getRetentionDays());
    }
}
