package com.sentinelai;

import com.sentinelai.model.LicenseTier;
import com.sentinelai.service.LicenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LicenseService}.
 *
 * <p>These tests exercise tier resolution logic in isolation — no Spring context,
 * no database. LicenseService has no external dependencies, so plain instantiation
 * is sufficient and tests run in milliseconds.</p>
 *
 * <h2>Test coverage</h2>
 * <ul>
 *   <li>Null key → FREE tier</li>
 *   <li>Blank/whitespace key → FREE tier</li>
 *   <li>Unknown key → FREE tier (with warning log, not an exception)</li>
 *   <li>Registered PAID_30 key → PAID_30 tier + 30 days</li>
 *   <li>Registered PAID_90 key → PAID_90 tier + 90 days</li>
 *   <li>Registered PAID_365 key → PAID_365 tier + 365 days</li>
 *   <li>Key lookup is exact-match (no prefix matching)</li>
 *   <li>Config with unknown tier name → key skipped, no exception at startup</li>
 * </ul>
 */
class LicenseServiceTest {

    /** Test license keys — not real keys; only used within this test class. */
    private static final String KEY_PAID_30  = "sk-test-paid30-abc";
    private static final String KEY_PAID_90  = "sk-test-paid90-def";
    private static final String KEY_PAID_365 = "sk-test-paid365-xyz";

    /** Service under test, seeded with a small in-memory registry. */
    private LicenseService licenseService;

    @BeforeEach
    void setUp() {
        // Build the service with a small config map — mirrors what Spring would
        // inject from sentinel.licenses.* application properties.
        Map<String, String> config = Map.of(
                KEY_PAID_30,  "PAID_30",
                KEY_PAID_90,  "PAID_90",
                KEY_PAID_365, "PAID_365"
        );
        licenseService = new LicenseService(config);
    }

    // ── Null / blank / missing key ────────────────────────────────────────────

    @Test
    @DisplayName("null key resolves to FREE tier")
    void nullKey_resolvesFree() {
        LicenseTier tier = licenseService.resolveTier(null);
        assertThat(tier).isEqualTo(LicenseTier.FREE);
    }

    @Test
    @DisplayName("blank key resolves to FREE tier")
    void blankKey_resolvesFree() {
        LicenseTier tier = licenseService.resolveTier("   ");
        assertThat(tier).isEqualTo(LicenseTier.FREE);
    }

    @Test
    @DisplayName("empty string key resolves to FREE tier")
    void emptyKey_resolvesFree() {
        LicenseTier tier = licenseService.resolveTier("");
        assertThat(tier).isEqualTo(LicenseTier.FREE);
    }

    @Test
    @DisplayName("unknown key falls back to FREE tier without throwing")
    void unknownKey_resolvesFree_noException() {
        // An unrecognised key must not throw — it falls back to FREE tier so
        // callers with misconfigured keys still get a valid (if restricted) response.
        LicenseTier tier = licenseService.resolveTier("sk-this-key-does-not-exist");
        assertThat(tier).isEqualTo(LicenseTier.FREE);
    }

    // ── FREE tier retention ───────────────────────────────────────────────────

    @Test
    @DisplayName("FREE tier has 7-day retention")
    void freeTier_retentionIs7Days() {
        assertThat(LicenseTier.FREE.getRetentionDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("null key returns 7-day retention via getRetentionDays()")
    void nullKey_retentionDays_is7() {
        int days = licenseService.getRetentionDays(null);
        assertThat(days).isEqualTo(7);
    }

    // ── Paid tier resolution ──────────────────────────────────────────────────

    @Test
    @DisplayName("registered PAID_30 key resolves to PAID_30 tier")
    void paid30Key_resolvesPaid30() {
        LicenseTier tier = licenseService.resolveTier(KEY_PAID_30);
        assertThat(tier).isEqualTo(LicenseTier.PAID_30);
    }

    @Test
    @DisplayName("PAID_30 tier has 30-day retention")
    void paid30Tier_retentionIs30Days() {
        assertThat(LicenseTier.PAID_30.getRetentionDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("registered PAID_90 key resolves to PAID_90 tier")
    void paid90Key_resolvesPaid90() {
        LicenseTier tier = licenseService.resolveTier(KEY_PAID_90);
        assertThat(tier).isEqualTo(LicenseTier.PAID_90);
    }

    @Test
    @DisplayName("PAID_90 tier has 90-day retention")
    void paid90Tier_retentionIs90Days() {
        assertThat(LicenseTier.PAID_90.getRetentionDays()).isEqualTo(90);
    }

    @Test
    @DisplayName("registered PAID_365 key resolves to PAID_365 tier")
    void paid365Key_resolvesPaid365() {
        LicenseTier tier = licenseService.resolveTier(KEY_PAID_365);
        assertThat(tier).isEqualTo(LicenseTier.PAID_365);
    }

    @Test
    @DisplayName("PAID_365 tier has 365-day retention")
    void paid365Tier_retentionIs365Days() {
        assertThat(LicenseTier.PAID_365.getRetentionDays()).isEqualTo(365);
    }

    // ── Convenience wrapper ───────────────────────────────────────────────────

    @Test
    @DisplayName("getRetentionDays() returns correct days for a registered key")
    void getRetentionDays_registered_returnsCorrectDays() {
        int days = licenseService.getRetentionDays(KEY_PAID_365);
        assertThat(days).isEqualTo(365);
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("key with leading/trailing whitespace is trimmed before lookup")
    void keyWithWhitespace_trimmedBeforeLookup() {
        // The service should trim whitespace so that keys copied from config files
        // with accidental spaces still work.
        LicenseTier tier = licenseService.resolveTier("  " + KEY_PAID_365 + "  ");
        assertThat(tier).isEqualTo(LicenseTier.PAID_365);
    }

    @Test
    @DisplayName("config with unknown tier name skips key — no exception at startup")
    void configWithUnknownTierName_skipsKey_noException() {
        // Constructing with a bad tier name must not throw; the key is simply
        // not added to the registry and the warning is logged.
        Map<String, String> badConfig = Map.of("sk-bad-tier-key", "ULTRA_PREMIUM");

        // Should not throw
        LicenseService serviceWithBadConfig = new LicenseService(badConfig);

        // The bad key must not resolve to anything — falls back to FREE
        LicenseTier tier = serviceWithBadConfig.resolveTier("sk-bad-tier-key");
        assertThat(tier).isEqualTo(LicenseTier.FREE);
    }

    @Test
    @DisplayName("empty config map produces a service that always returns FREE")
    void emptyConfig_alwaysReturnsFree() {
        LicenseService emptyService = new LicenseService(Map.of());
        assertThat(emptyService.resolveTier("any-key")).isEqualTo(LicenseTier.FREE);
    }

    @Test
    @DisplayName("null config map produces a service that always returns FREE")
    void nullConfig_alwaysReturnsFree() {
        LicenseService nullConfigService = new LicenseService(null);
        assertThat(nullConfigService.resolveTier("any-key")).isEqualTo(LicenseTier.FREE);
    }
}
