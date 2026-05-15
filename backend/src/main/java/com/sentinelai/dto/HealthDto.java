package com.sentinelai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Response body for the {@code GET /api/health} endpoint.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code status} — overall health: {@code "UP"} when all components are healthy,
 *       {@code "DEGRADED"} when at least one optional component (detection API) is down,
 *       {@code "DOWN"} when a critical component (database) is down.</li>
 *   <li>{@code db} — database connectivity check ({@code SELECT 1}).</li>
 *   <li>{@code detection} — detection API reachability check (HTTP GET with short timeout).</li>
 * </ul>
 *
 * <h2>Example response</h2>
 * <pre>{@code
 * {
 *   "status": "DEGRADED",
 *   "db": { "status": "UP" },
 *   "detection": { "status": "DOWN", "message": "Connection refused" }
 * }
 * }</pre>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthDto {

    /**
     * Overall health status of the SentinelAI backend.
     *
     * <ul>
     *   <li>{@code "UP"} — all components healthy</li>
     *   <li>{@code "DEGRADED"} — non-critical component(s) unreachable (detection API)</li>
     *   <li>{@code "DOWN"} — critical component unreachable (database)</li>
     * </ul>
     */
    private String status;

    /** Health of the PostgreSQL database connection. */
    private ComponentHealth db;

    /** Health of the prompt injection detection API. */
    private ComponentHealth detection;

    /**
     * Health status of a single system component.
     *
     * <p>A component is {@code "UP"} when it responded successfully within
     * the expected time window, or {@code "DOWN"} when it timed out or
     * returned an error. An optional {@code message} field carries the
     * error description when the component is down.</p>
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComponentHealth {

        /**
         * Component-level status: {@code "UP"} or {@code "DOWN"}.
         */
        private String status;

        /**
         * Human-readable error message when status is {@code "DOWN"}.
         * {@code null} when status is {@code "UP"}.
         */
        private String message;

        /**
         * Factory method for a healthy component.
         *
         * @return a {@link ComponentHealth} with status {@code "UP"} and no message
         */
        public static ComponentHealth up() {
            return ComponentHealth.builder().status("UP").build();
        }

        /**
         * Factory method for an unhealthy component.
         *
         * @param message description of the failure
         * @return a {@link ComponentHealth} with status {@code "DOWN"} and the given message
         */
        public static ComponentHealth down(String message) {
            return ComponentHealth.builder().status("DOWN").message(message).build();
        }
    }
}
