package com.sentinelai.model;

/**
 * License tiers available to SentinelAI operators.
 *
 * <p>Each tier maps to a data retention window (in days) that controls how long
 * {@code prompt_logs} rows are kept before the nightly cleanup job deletes them.
 * Operators on higher tiers retain more historical data for retroactive audit.</p>
 *
 * <h2>Tier resolution</h2>
 * <p>A caller's tier is determined by the license key they supply in the
 * {@code X-Sentinel-License} HTTP header. In Phase 4 this is resolved by
 * a simple in-memory map in {@code LicenseService}. A database-backed license
 * registry can be added in a later phase without changing this enum.</p>
 *
 * <h2>Free tier</h2>
 * <p>Callers that omit the {@code X-Sentinel-License} header are placed on the
 * {@link #FREE} tier automatically. The retention window is intentionally short
 * (7 days) so free-tier tables do not grow unbounded.</p>
 */
public enum LicenseTier {

    /**
     * Free tier — no license key required.
     *
     * <p>Audit logs are retained for 7 days. Suitable for evaluation and
     * low-volume internal tooling.</p>
     */
    FREE(7),

    /**
     * Paid 30-day tier.
     *
     * <p>Audit logs are retained for 30 days. Covers typical monthly reporting
     * and compliance review cycles.</p>
     */
    PAID_30(30),

    /**
     * Paid 90-day tier.
     *
     * <p>Audit logs are retained for 90 days. Aligns with quarterly compliance
     * review periods common in regulated industries.</p>
     */
    PAID_90(90),

    /**
     * Paid 365-day tier.
     *
     * <p>Audit logs are retained for one full year. Required for operators under
     * regulations that mandate annual audit trails (e.g. SOC 2 Type II, ISO 27001).</p>
     */
    PAID_365(365);

    /**
     * The number of days for which prompt_logs rows are retained before
     * the nightly cleanup job is allowed to delete them.
     */
    private final int retentionDays;

    /**
     * @param retentionDays how many days of audit history this tier preserves
     */
    LicenseTier(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /**
     * Returns the retention window for this tier in days.
     *
     * @return number of days prompt_logs rows are kept before deletion
     */
    public int getRetentionDays() {
        return retentionDays;
    }
}
