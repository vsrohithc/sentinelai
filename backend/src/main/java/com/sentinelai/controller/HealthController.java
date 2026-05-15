package com.sentinelai.controller;

import com.sentinelai.dto.HealthDto;
import com.sentinelai.service.HealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller exposing the SentinelAI application health check endpoint.
 *
 * <h2>Endpoint</h2>
 * <pre>GET /api/health</pre>
 *
 * <p>This is a custom health endpoint complementing the Spring Actuator endpoint
 * at {@code /actuator/health}. It provides application-level detail about the
 * components that SentinelAI depends on, in a format tailored for operations
 * dashboards and external monitoring systems.</p>
 *
 * <h2>HTTP status codes</h2>
 * <ul>
 *   <li>{@code 200 OK} — overall status is {@code "UP"} or {@code "DEGRADED"}
 *       (the service is serving requests; degraded means detection is down)</li>
 *   <li>{@code 503 Service Unavailable} — overall status is {@code "DOWN"}
 *       (database is unreachable; the proxy cannot persist audit records)</li>
 * </ul>
 *
 * <h2>Example responses</h2>
 * <pre>{@code
 * // All healthy — HTTP 200
 * { "status": "UP", "db": {"status":"UP"}, "detection": {"status":"UP"} }
 *
 * // Detection API unreachable — HTTP 200 (service still operational)
 * { "status": "DEGRADED", "db": {"status":"UP"}, "detection": {"status":"DOWN","message":"..."} }
 *
 * // Database unreachable — HTTP 503
 * { "status": "DOWN", "db": {"status":"DOWN","message":"..."}, "detection": {"status":"UP"} }
 * }</pre>
 *
 * <h2>Authentication</h2>
 * <p>The health endpoint is not guarded by {@link com.sentinelai.filter.ApiKeyFilter} —
 * it must be accessible to load balancers and monitoring systems without credentials.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    /** Service that performs the actual DB and detection API checks. */
    private final HealthService healthService;

    /**
     * Performs all dependency health checks and returns an aggregated status.
     *
     * <p>Checks run in parallel; the response time is bounded by the slowest
     * check (the detection API probe timeout, currently 2 s).</p>
     *
     * @return a {@code Mono} emitting a {@link ResponseEntity} with HTTP 200
     *         when the service is UP or DEGRADED, or HTTP 503 when it is DOWN
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<HealthDto>> health() {
        return healthService.check()
                .map(dto -> {
                    log.debug("Health check result: status={}", dto.getStatus());

                    // 503 only when the database is fully down.
                    // DEGRADED (detection API unreachable) still returns 200 because
                    // load balancers watching for 503 should not yank the backend
                    // from the pool just because an optional service is unreachable.
                    HttpStatus httpStatus = "DOWN".equals(dto.getStatus())
                            ? HttpStatus.SERVICE_UNAVAILABLE
                            : HttpStatus.OK;

                    return ResponseEntity.status(httpStatus).body(dto);
                });
    }
}
