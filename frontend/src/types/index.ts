/**
 * Shared TypeScript interfaces for SentinelAI.
 *
 * These types mirror the Java DTOs returned by the backend API.
 * If you change a backend DTO, update the corresponding interface here.
 */

// ── Risk levels ───────────────────────────────────────────────────────────────

/**
 * The four possible risk classifications for a prompt log entry.
 *
 * - HIGH:    riskScore >= 0.7 — potential prompt injection detected
 * - MEDIUM:  riskScore >= 0.4 and < 0.7 — suspicious but not definitively malicious
 * - LOW:     riskScore >= 0.0 and < 0.4 — appears safe
 * - UNKNOWN: riskScore is null — the detection API timed out or errored
 */
export type RiskLevel = 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN'

/**
 * Derives the RiskLevel enum value from a raw numeric score.
 *
 * @param score - the riskScore from the API; null means detection failed
 * @returns the corresponding RiskLevel string
 */
export function getRiskLevel(score: number | null | undefined): RiskLevel {
  if (score == null) return 'UNKNOWN'
  if (score >= 0.7) return 'HIGH'
  if (score >= 0.4) return 'MEDIUM'
  return 'LOW'
}

// ── License tiers ─────────────────────────────────────────────────────────────

/**
 * License retention tiers. Maps to the backend LicenseTier enum added in Phase 4.
 */
export type LicenseTier = 'FREE' | 'PAID_30' | 'PAID_90' | 'PAID_365'

/**
 * Response from GET /api/license/info — mirrors LicenseInfoDto on the backend.
 */
export interface LicenseInfo {
  /** Tier name, e.g. "FREE" or "PAID_365" */
  tier: LicenseTier
  /** Number of days audit logs are retained for this tier */
  retentionDays: number
}

// ── Prompt log ────────────────────────────────────────────────────────────────

/**
 * One row in the prompt_logs table, as returned by GET /api/logs and GET /api/logs/{id}.
 *
 * All ISO-8601 datetime strings are returned as strings from the API and should
 * be parsed with `new Date()` or a date library when displaying to the user.
 */
export interface PromptLog {
  /** UUID primary key */
  id: string
  /** Wall-clock time the proxy received the request (ISO-8601 with offset) */
  requestTime: string
  /** AI model identifier, e.g. "gpt-4o" */
  model: string
  /** AI provider routing key — mirrors ModelProvider enum on the backend */
  provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI' | 'AZURE_OPENAI'
  /** Full raw prompt text sent by the caller */
  prompt: string
  /** Full raw response from the AI model; null if the AI call failed */
  response: string | null
  /** Injection risk score 0.0–1.0; null if the detection API failed */
  riskScore: number | null
  /** Caller-supplied metadata bag (free-form JSON object) */
  metadata: Record<string, unknown> | null
  /** License key from X-Sentinel-License header; null for free-tier callers */
  licenseKey: string | null
  /** Timestamp when the row was persisted (may lag requestTime by milliseconds) */
  createdAt: string
}

// ── Paged response ────────────────────────────────────────────────────────────

/**
 * Spring Data Page wrapper — every paginated endpoint returns this shape.
 *
 * @typeParam T - the element type in the content array
 */
export interface PagedResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  /** Zero-based page index */
  number: number
  size: number
  /** True when this is the last page */
  last: boolean
  /** True when this is the first page */
  first: boolean
}

// ── Dashboard stats ───────────────────────────────────────────────────────────

/**
 * One data point in the 30-day daily risk trend series.
 */
export interface DailyRiskPoint {
  /** ISO date string, e.g. "2024-11-15", used as the Recharts X-axis label */
  date: string
  highRiskCount: number
  mediumRiskCount: number
  lowRiskCount: number
}

/**
 * Summary statistics returned by GET /api/dashboard/stats.
 */
export interface DashboardStats {
  totalLogs: number
  highRiskCount: number
  mediumRiskCount: number
  lowRiskCount: number
  /** Rows where risk_score is null — detection API failed */
  nullRiskCount: number
  /** 30-day daily series for the risk trend chart, ordered oldest-first */
  dailySeries: DailyRiskPoint[]
}
