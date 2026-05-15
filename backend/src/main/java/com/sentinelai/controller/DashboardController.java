package com.sentinelai.controller;

import com.sentinelai.dto.DashboardStatsDto;
import com.sentinelai.service.LogQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for dashboard summary statistics.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/dashboard/stats} — total log counts by risk tier + 30-day daily series</li>
 * </ul>
 *
 * <p>This controller is intentionally thin — all computation lives in
 * {@link LogQueryService#getDashboardStats()}. Keeping controllers free of
 * business logic makes them easier to test and replace.</p>
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final LogQueryService logQueryService;

    /**
     * Returns summary statistics for the Dashboard page.
     *
     * <p>Includes total counts broken down by risk tier (high / medium / low / null)
     * and a 30-day daily series suitable for a Recharts line chart.</p>
     *
     * @return 200 OK with a {@link DashboardStatsDto}
     */
    @GetMapping("/stats")
    public ResponseEntity<DashboardStatsDto> getStats() {
        return ResponseEntity.ok(logQueryService.getDashboardStats());
    }
}
