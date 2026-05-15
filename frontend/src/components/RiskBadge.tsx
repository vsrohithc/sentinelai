/**
 * RiskBadge — coloured pill badge that communicates a prompt log's risk level.
 *
 * Uses the Tailwind brand + risk colour tokens defined in tailwind.config.ts:
 *   high   → red   (risk-high-*)
 *   medium → amber (risk-medium-*)
 *   low    → green (risk-low-*)
 *   unknown → gray  (no score available)
 *
 * The badge is intentionally small so it can appear inline in table rows
 * without dominating the layout.
 */

import { getRiskLevel } from '../types'
import type { RiskLevel } from '../types'

interface RiskBadgeProps {
  /** Raw risk score from the API (0.0–1.0), or null if detection failed */
  score: number | null | undefined
  /**
   * When true, the numeric score is shown alongside the level label.
   * Useful in detail views where precision matters.
   */
  showScore?: boolean
}

/** Tailwind classes for each risk level — centralised here to keep them consistent. */
const LEVEL_STYLES: Record<RiskLevel, string> = {
  HIGH:    'bg-red-100 text-red-800 ring-1 ring-red-300',
  MEDIUM:  'bg-amber-100 text-amber-800 ring-1 ring-amber-300',
  LOW:     'bg-green-100 text-green-800 ring-1 ring-green-300',
  UNKNOWN: 'bg-gray-100 text-gray-500 ring-1 ring-gray-300',
}

const LEVEL_LABELS: Record<RiskLevel, string> = {
  HIGH:    'High',
  MEDIUM:  'Medium',
  LOW:     'Low',
  UNKNOWN: 'Unknown',
}

/**
 * Renders a small coloured badge for the given risk score.
 *
 * @param score     - the numeric riskScore (0.0–1.0) or null
 * @param showScore - when true, append the raw numeric score in parentheses
 */
export function RiskBadge({ score, showScore = false }: RiskBadgeProps) {
  const level = getRiskLevel(score)
  const styles = LEVEL_STYLES[level]
  const label = LEVEL_LABELS[level]

  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${styles}`}
      // Accessible role so screen readers announce "High risk" rather than just "High"
      aria-label={`Risk level: ${label}${showScore && score != null ? ` (${score.toFixed(3)})` : ''}`}
    >
      {label}
      {showScore && score != null && (
        <span className="ml-1 opacity-70">({score.toFixed(3)})</span>
      )}
    </span>
  )
}
