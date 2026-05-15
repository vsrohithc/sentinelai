package com.sentinelai.service;

import com.sentinelai.model.LicenseTier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for resolving license keys to their corresponding
 * {@link LicenseTier} and retention configuration.
 *
 * <h2>Phase 4 implementation — in-memory key registry</h2>
 * <p>In Phase 4 the license registry is a simple in-memory {@link Map} populated
 * from application configuration ({@code sentinel.licenses.*}). This is
 * intentionally lightweight — it avoids introducing a separate license database
 * table while still letting operators configure real keys in production via
 * environment variables.</p>
 *
 * <p>Example {@code application.yml} configuration:</p>
 * <pre>{@code
 * sentinel:
 *   licenses:
 *     sk-sentinel-abc123: PAID_365
 *     sk-sentinel-def456: PAID_90
 * }</pre>
 *
 * <p>A database-backed registry (with expiry dates, per-tenant quotas, etc.)
 * can replace this in a future phase without changing the interface that
 * {@link RetentionCleanupService} and {@link LicenseController} depend on.</p>
 *
 * <h2>Thread safety</h2>
 * <p>The registry map is built once at construction time and is never mutated
 * afterwards, so it is safe to read from multiple threads without locking.</p>
 */
@Slf4j
@Service
public class LicenseService {

    /**
     * In-memory license key → tier map.
     *
     * <p>Keys are license key strings (e.g. {@code "sk-sentinel-abc123"});
     * values are the corresponding {@link LicenseTier}. Populated from the
     * {@code sentinel.licenses} configuration map at application startup.</p>
     */
    private final Map<String, LicenseTier> registry;

    /**
     * Constructs the service and builds the in-memory registry from the
     * configuration supplied via {@code sentinel.licenses.*} properties.
     *
     * <p>The {@code licenseConfig} map is injected by Spring from a
     * {@code Map<String, String>} binding where keys are license key strings
     * and values are {@link LicenseTier} enum name strings
     * (e.g. {@code "PAID_365"}).</p>
     *
     * @param licenseConfig map of license key → tier name; may be null or empty
     *                      (e.g. in development when no keys are configured)
     */
    public LicenseService(
            @Value("#{${sentinel.licenses:{:}}}")
            Map<String, String> licenseConfig) {

        Map<String, LicenseTier> map = new HashMap<>();

        if (licenseConfig != null) {
            for (Map.Entry<String, String> entry : licenseConfig.entrySet()) {
                try {
                    LicenseTier tier = LicenseTier.valueOf(entry.getValue().toUpperCase());
                    map.put(entry.getKey(), tier);
                    log.info("Registered license key (masked) → tier={}", tier);
                } catch (IllegalArgumentException ex) {
                    // Warn but don't crash startup — an unknown tier name is a config error,
                    // not a reason to refuse to start the application.
                    log.warn("Unknown license tier '{}' for key (masked); skipping. "
                            + "Valid values: FREE, PAID_30, PAID_90, PAID_365", entry.getValue());
                }
            }
        }

        // Make the map unmodifiable after construction — immutability = thread safety.
        this.registry = Collections.unmodifiableMap(map);
        log.info("LicenseService initialised with {} registered key(s)", registry.size());
    }

    /**
     * Resolves the {@link LicenseTier} for the given license key.
     *
     * <p>If the key is null, blank, or not found in the registry, the
     * {@link LicenseTier#FREE} tier is returned. This ensures that callers
     * without a valid license key always receive a valid (if restricted)
     * service, rather than an error.</p>
     *
     * @param licenseKey the license key from the {@code X-Sentinel-License} header;
     *                   null and blank strings are treated as "no key supplied"
     * @return the resolved tier; never null; defaults to {@link LicenseTier#FREE}
     */
    public LicenseTier resolveTier(String licenseKey) {
        if (licenseKey == null || licenseKey.isBlank()) {
            // No key supplied — free tier
            return LicenseTier.FREE;
        }

        LicenseTier tier = registry.get(licenseKey.trim());

        if (tier == null) {
            // Key supplied but not in registry — fall back to free tier and log a warning
            // so operators can detect misconfigured or expired keys in their logs.
            log.warn("Unknown license key (masked, length={}); falling back to FREE tier",
                    licenseKey.length());
            return LicenseTier.FREE;
        }

        return tier;
    }

    /**
     * Returns the number of days logs are retained for the given license key.
     *
     * <p>Convenience wrapper around {@link #resolveTier(String)} +
     * {@link LicenseTier#getRetentionDays()}.</p>
     *
     * @param licenseKey the license key; null or blank resolves to FREE tier
     * @return retention window in days; always >= 7
     */
    public int getRetentionDays(String licenseKey) {
        return resolveTier(licenseKey).getRetentionDays();
    }
}
