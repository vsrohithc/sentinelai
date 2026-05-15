/**
 * API functions for prompt log and dashboard statistics endpoints.
 *
 * Each function corresponds to one backend endpoint and returns a typed promise.
 * These functions are called by TanStack Query hooks in src/hooks/ — they should
 * not be called directly from components, keeping data-fetching logic centralised.
 */

import { apiFetch } from './client'
import type { DashboardStats, PagedResponse, PromptLog } from '../types'

// ── Audit log endpoints ───────────────────────────────────────────────────────

/**
 * Parameters accepted by the GET /api/logs endpoint.
 *
 * All fields are optional; omitting them skips the corresponding filter.
 */
export interface LogsParams {
  /** Zero-based page index (default 0) */
  page?: number
  /** Records per page (default 25, backend caps at 200) */
  size?: number
  /** Include only records at or after this ISO-8601 datetime */
  from?: string
  /** Include only records at or before this ISO-8601 datetime */
  to?: string
  /** Include only records with riskScore >= minRisk (0.0–1.0) */
  minRisk?: number
  /** Include only records with riskScore <= maxRisk (0.0–1.0) */
  maxRisk?: number
}

/**
 * Fetches a paginated list of audit log entries from GET /api/logs.
 *
 * @param params  - filter and pagination parameters
 * @param signal  - AbortSignal for request cancellation (TanStack Query passes this automatically)
 * @returns a page of PromptLog entries
 */
export async function fetchLogs(
  params: LogsParams = {},
  signal?: AbortSignal,
): Promise<PagedResponse<PromptLog>> {
  // Build query string from non-null, non-undefined params only.
  const qs = new URLSearchParams()

  if (params.page != null) qs.set('page', String(params.page))
  if (params.size != null) qs.set('size', String(params.size))
  if (params.from) qs.set('from', params.from)
  if (params.to) qs.set('to', params.to)
  if (params.minRisk != null) qs.set('minRisk', String(params.minRisk))
  if (params.maxRisk != null) qs.set('maxRisk', String(params.maxRisk))

  const query = qs.toString() ? `?${qs.toString()}` : ''
  return apiFetch<PagedResponse<PromptLog>>(`/api/logs${query}`, signal)
}

/**
 * Fetches a single audit log entry by UUID from GET /api/logs/{id}.
 *
 * Used by the detail drawer in the Audit Log page to load the full
 * prompt and response text without re-fetching the entire list.
 *
 * @param id      - the UUID of the log entry
 * @param signal  - AbortSignal for request cancellation
 * @returns the matching PromptLog
 * @throws Error with status 404 if the log entry does not exist
 */
export async function fetchLog(id: string, signal?: AbortSignal): Promise<PromptLog> {
  return apiFetch<PromptLog>(`/api/logs/${id}`, signal)
}

// ── Dashboard stats endpoint ──────────────────────────────────────────────────

/**
 * Fetches dashboard summary statistics from GET /api/dashboard/stats.
 *
 * Returns risk-level counts and a 30-day daily series for the trend chart.
 *
 * @param signal - AbortSignal for request cancellation
 * @returns DashboardStats object
 */
export async function fetchDashboardStats(signal?: AbortSignal): Promise<DashboardStats> {
  return apiFetch<DashboardStats>('/api/dashboard/stats', signal)
}
