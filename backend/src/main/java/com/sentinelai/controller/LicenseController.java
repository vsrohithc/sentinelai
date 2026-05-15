package com.sentinelai.controller;

import com.sentinelai.dto.LicenseInfoDto;
import com.sentinelai.model.LicenseTier;
import com.sentinelai.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing license information for the frontend Settings page.
 *
 * <h2>Endpoint</h2>
 * <ul>
 *   <li>{@code GET /api/license/info} — returns the caller's tier and retention window</li>
 * </ul>
 *
 * <p>The license key is read from the same {@code X-Sentinel-License} header used
 * by the proxy endpoint. Callers that omit the header receive FREE tier information.
 * No secret key material is included in the response.</p>
 *
 * <h2>Why expose this endpoint?</h2>
 * <p>The frontend Settings page needs to display the operator's current tier and
 * retention policy so they understand why older logs may have disappeared.
 * Rather than hardcoding tier information in the frontend, we derive it from the
 * same license key the operator configures in their environment — single source
 * of truth.</p>
 */
@RestController
@RequestMapping("/api/license")
@RequiredArgsConstructor
public class LicenseController {

    /** Service that maps license keys to tiers. */
    private final LicenseService licenseService;

    /**
     * Returns the tier and retention configuration for the given license key.
     *
     * <p>If the header is absent or the key is not recognised, returns FREE
     * tier information (7-day retention) — never returns an error. This lets
     * the Settings page always display something meaningful.</p>
     *
     * <h3>Example</h3>
     * <pre>
     * GET /api/license/info
     * X-Sentinel-License: sk-sentinel-abc123
     *
     * 200 OK
     * {"tier": "PAID_365", "retentionDays": 365}
     * </pre>
     *
     * @param licenseKey the license key from the {@code X-Sentinel-License} request header;
     *                   null if the header is omitted
     * @return 200 OK with a {@link LicenseInfoDto}; never 4xx or 5xx for a valid request
     */
    @GetMapping("/info")
    public ResponseEntity<LicenseInfoDto> getInfo(
            @RequestHeader(value = "X-Sentinel-License", required = false) String licenseKey) {

        LicenseTier tier = licenseService.resolveTier(licenseKey);

        LicenseInfoDto dto = new LicenseInfoDto(
                tier.name(),          // e.g. "FREE", "PAID_365"
                tier.getRetentionDays()
        );

        return ResponseEntity.ok(dto);
    }
}
