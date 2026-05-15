package com.sentinelai.dto;

import java.util.List;

/**
 * Summary statistics DTO returned by {@code GET /api/dashboard/stats}.
 *
 * <p>Supplies every number the Dashboard page needs in one call, avoiding
 * multiple round-trips from the frontend. The chart series data is included
 * here so the chart can render without a separate API call.</p>
 *
 * @param totalLogs        total number of prompt_logs rows
 * @param highRiskCount    rows with risk_score >= 0.7
 * @param mediumRiskCount  rows with risk_score >= 0.4 and < 0.7
 * @param lowRiskCount     rows with risk_score >= 0.0 and < 0.4
 * @param nullRiskCount    rows where risk_score IS NULL (detection API failed)
 * @param dailySeries      list of per-day aggregates for the risk trend line chart,
 *                         ordered oldest-first; covers the last 30 calendar days
 */
public record DashboardStatsDto(
        long totalLogs,
        long highRiskCount,
        long mediumRiskCount,
        long lowRiskCount,
        long nullRiskCount,
        List<DailyRiskPoint> dailySeries
) {

    /**
     * One data point in the daily risk trend series.
     *
     * <p>Each point represents a single UTC calendar day. The frontend maps
     * these to Recharts data objects where {@code date} becomes the X-axis
     * label and the count fields become the line series values.</p>
     *
     * @param date            ISO-8601 date string (e.g. "2024-11-15") for X-axis labelling
     * @param highRiskCount   number of high-risk prompts logged on this day
     * @param mediumRiskCount number of medium-risk prompts logged on this day
     * @param lowRiskCount    number of low-risk prompts logged on this day
     */
    public record DailyRiskPoint(
            String date,
            long highRiskCount,
            long mediumRiskCount,
            long lowRiskCount
    ) {}
}
