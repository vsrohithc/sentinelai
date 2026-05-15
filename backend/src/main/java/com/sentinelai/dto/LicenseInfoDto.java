package com.sentinelai.dto;

/**
 * Response DTO for the {@code GET /api/license/info} endpoint.
 *
 * <p>Returns the caller's resolved license tier and the corresponding log
 * retention window. The frontend {@code Settings} page uses this to display
 * the operator's current tier and data retention policy.</p>
 *
 * <p>No sensitive license key material is included in the response — only the
 * tier name and the derived retention configuration.</p>
 *
 * @param tier          the resolved license tier name (e.g. "FREE", "PAID_365")
 * @param retentionDays the number of days audit logs are retained for this tier
 */
public record LicenseInfoDto(
        String tier,
        int retentionDays
) {}
