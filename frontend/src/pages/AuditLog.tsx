/**
 * AuditLog page — paginated table of all proxied AI requests.
 *
 * Features:
 *  - Paginated table (25 rows per page) with date + risk filters
 *  - Clicking a row opens a slide-in detail drawer showing the full
 *    prompt, response, and metadata for that request
 *  - Filters: date range (from/to) and risk level selector
 *  - Auto-refreshes every 30 seconds (via useLogs hook)
 */

import { useEffect, useState } from 'react'
import { X, ChevronLeft, ChevronRight } from 'lucide-react'
import { RiskBadge } from '../components/RiskBadge'
import { SignatureBadge } from '../components/SignatureBadge'
import { useLogs } from '../hooks/useLogs'
import { verifyLog } from '../api/logs'
import type { LogsParams, VerificationResult } from '../api/logs'
import type { PromptLog } from '../types'

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Formats an ISO-8601 datetime string into a human-readable local time string.
 * Falls back to the raw string if parsing fails.
 */
function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: '2-digit', minute: '2-digit',
    })
  } catch {
    return iso
  }
}

/**
 * Truncates a string to maxLen characters, appending "…" if truncated.
 * Used to preview the prompt text in the table cell.
 */
function truncate(text: string | null | undefined, maxLen = 120): string {
  if (!text) return '—'
  if (text.length <= maxLen) return text
  return text.slice(0, maxLen) + '…'
}

/**
 * Converts a <input type="datetime-local"> value (which is in the user's
 * LOCAL time, e.g. "2026-05-13T15:30") to a UTC ISO-8601 string the backend
 * expects ("2026-05-13T22:30:00.000Z" for a PDT user).
 *
 * The previous implementation appended ":00Z" to the raw local value, which
 * stamped local time as if it were UTC and made all filter results off by the
 * user's timezone offset. This bug was invisible to UTC-based developers.
 *
 * @param localValue value from the datetime-local input (may be empty)
 * @returns ISO-8601 UTC string, or undefined when the input is empty
 */
function localInputToIsoUtc(localValue: string): string | undefined {
  if (!localValue) return undefined
  // new Date(localValue) interprets the string as local time per the HTML spec.
  // toISOString() then emits UTC with a Z suffix.
  const d = new Date(localValue)
  if (isNaN(d.getTime())) return undefined
  return d.toISOString()
}

/**
 * Converts an ISO-8601 UTC string back into the "YYYY-MM-DDTHH:mm" shape a
 * datetime-local input expects, in the user's LOCAL time. Used to populate the
 * input value from existing filter state without round-trip distortion.
 *
 * @param iso ISO-8601 string (may be undefined)
 * @returns local-formatted string, or empty string when iso is missing
 */
function isoUtcToLocalInput(iso: string | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  // toISOString() yields UTC; we want LOCAL time, so build the string manually.
  const pad = (n: number) => n.toString().padStart(2, '0')
  return (
    d.getFullYear() +
    '-' + pad(d.getMonth() + 1) +
    '-' + pad(d.getDate()) +
    'T' + pad(d.getHours()) +
    ':' + pad(d.getMinutes())
  )
}

// ── Detail drawer ─────────────────────────────────────────────────────────────

interface DrawerProps {
  log: PromptLog
  onClose: () => void
}

/**
 * Slide-in drawer showing the full detail of one audit log entry.
 *
 * Renders the prompt, response, risk score, model, metadata, and timestamps.
 * Closed via the X button or by clicking the dark overlay backdrop.
 */
function DetailDrawer({ log, onClose }: DrawerProps) {
  const [verification, setVerification] = useState<VerificationResult | null>(null)
  const [verifying, setVerifying] = useState(false)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // Auto-verify when the drawer opens for a signed record
  useEffect(() => {
    if (!log.signature) return
    setVerifying(true)
    verifyLog(log.id)
      .then(setVerification)
      .catch(() => setVerification(null))
      .finally(() => setVerifying(false))
  }, [log.id, log.signature])

  return (
    <>
      {/* Backdrop — clicking it closes the drawer */}
      <div
        className="fixed inset-0 z-40 bg-black/30"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Drawer panel */}
      <aside
        className="fixed right-0 top-0 z-50 flex h-full w-full max-w-xl flex-col border-l border-gray-200 bg-white shadow-2xl"
        aria-label="Audit log detail"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-5 py-4">
          <div>
            <h2 className="text-sm font-semibold text-gray-900">Request Detail</h2>
            <p className="mt-0.5 text-xs text-gray-500 font-mono">{log.id}</p>
          </div>
          <button
            onClick={onClose}
            className="rounded-md p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
            aria-label="Close detail drawer"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Scrollable body */}
        <div className="flex-1 overflow-y-auto p-5 space-y-5 text-sm">
          {/* Metadata row */}
          <div className="flex flex-wrap gap-3">
            <div>
              <span className="text-xs text-gray-400 uppercase tracking-wide">Risk</span>
              <div className="mt-1">
                <RiskBadge score={log.riskScore} showScore />
              </div>
            </div>
            <div>
              <span className="text-xs text-gray-400 uppercase tracking-wide">Model</span>
              <p className="mt-1 font-mono text-xs text-gray-700">{log.model}</p>
            </div>
            <div>
              <span className="text-xs text-gray-400 uppercase tracking-wide">Provider</span>
              <p className="mt-1 font-mono text-xs text-gray-700">{log.provider}</p>
            </div>
            <div>
              <span className="text-xs text-gray-400 uppercase tracking-wide">Requested</span>
              <p className="mt-1 text-xs text-gray-700">{formatDate(log.requestTime)}</p>
            </div>
            {log.licenseKey && (
              <div>
                <span className="text-xs text-gray-400 uppercase tracking-wide">License</span>
                <p className="mt-1 font-mono text-xs text-gray-700">{log.licenseKey}</p>
              </div>
            )}
          </div>

          {/* Prompt */}
          <div>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-400">Prompt</h3>
            <pre className="whitespace-pre-wrap rounded-md bg-gray-50 p-3 font-mono text-xs text-gray-800 leading-relaxed border border-gray-200">
              {log.prompt ?? '—'}
            </pre>
          </div>

          {/* Response */}
          <div>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-400">Response</h3>
            <pre className="whitespace-pre-wrap rounded-md bg-gray-50 p-3 font-mono text-xs text-gray-800 leading-relaxed border border-gray-200">
              {log.response ?? '(no response — AI call failed)'}
            </pre>
          </div>

          {/* Metadata */}
          {log.metadata && Object.keys(log.metadata).length > 0 && (
            <div>
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-400">Metadata</h3>
              <pre className="whitespace-pre-wrap rounded-md bg-gray-50 p-3 font-mono text-xs text-gray-600 leading-relaxed border border-gray-200">
                {JSON.stringify(log.metadata, null, 2)}
              </pre>
            </div>
          )}

          {/* Signature */}
          <div>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">Cryptographic Signature</h3>
            <div className="rounded-md border border-gray-200 bg-gray-50 p-3 space-y-2">
              <div className="flex items-center gap-2">
                <SignatureBadge
                  signature={log.signature}
                  valid={verification?.valid}
                />
                <span className="text-xs text-gray-700">
                  {verifying && 'Verifying…'}
                  {!verifying && !log.signature && 'Unsigned — signing was not enabled when this record was created'}
                  {!verifying && log.signature && verification?.valid === true && 'Valid — record is unmodified'}
                  {!verifying && log.signature && verification?.valid === false && (verification.reason ?? 'Signature invalid')}
                </span>
              </div>
              {log.signature && (
                <div>
                  <span className="text-xs text-gray-400">Algorithm</span>
                  <p className="font-mono text-xs text-gray-600">Ed25519</p>
                </div>
              )}
              {log.signature && (
                <div>
                  <span className="text-xs text-gray-400">Signature</span>
                  <p className="mt-0.5 break-all font-mono text-xs text-gray-500">{log.signature}</p>
                </div>
              )}
            </div>
          </div>
        </div>
      </aside>
    </>
  )
}

// ── Filter bar ────────────────────────────────────────────────────────────────

interface FilterBarProps {
  params: LogsParams
  onChange: (p: LogsParams) => void
}

/**
 * Row of filter controls above the audit log table.
 *
 * Changes call onChange() with the updated params, which resets to page 0.
 */
function FilterBar({ params, onChange }: FilterBarProps) {
  /** Maps a risk-level selector string to minRisk/maxRisk param values */
  const RISK_OPTIONS = [
    { label: 'All risk levels', min: undefined, max: undefined },
    { label: 'High only (≥ 0.7)', min: 0.7, max: undefined },
    { label: 'Medium (0.4–0.7)', min: 0.4, max: 0.7 },
    { label: 'Low (< 0.4)', min: 0.0, max: 0.4 },
  ]

  const currentRiskLabel =
    RISK_OPTIONS.find(o => o.min === params.minRisk && o.max === params.maxRisk)?.label
    ?? 'All risk levels'

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3 shadow-sm">
      {/* Date from */}
      <div>
        <label className="block text-xs text-gray-500 mb-1" htmlFor="filter-from">From</label>
        <input
          id="filter-from"
          type="datetime-local"
          className="rounded-md border border-gray-300 px-2 py-1.5 text-xs text-gray-700 focus:outline-none focus:ring-2 focus:ring-sentinel-400"
          // The input emits LOCAL time; we round-trip through Date so the
          // backend always receives a UTC instant matching what the user picked.
          value={isoUtcToLocalInput(params.from)}
          onChange={e => onChange({ ...params, page: 0, from: localInputToIsoUtc(e.target.value) })}
        />
      </div>

      {/* Date to */}
      <div>
        <label className="block text-xs text-gray-500 mb-1" htmlFor="filter-to">To</label>
        <input
          id="filter-to"
          type="datetime-local"
          className="rounded-md border border-gray-300 px-2 py-1.5 text-xs text-gray-700 focus:outline-none focus:ring-2 focus:ring-sentinel-400"
          value={isoUtcToLocalInput(params.to)}
          onChange={e => onChange({ ...params, page: 0, to: localInputToIsoUtc(e.target.value) })}
        />
      </div>

      {/* Risk level */}
      <div>
        <label className="block text-xs text-gray-500 mb-1" htmlFor="filter-risk">Risk level</label>
        <select
          id="filter-risk"
          className="rounded-md border border-gray-300 px-2 py-1.5 text-xs text-gray-700 focus:outline-none focus:ring-2 focus:ring-sentinel-400"
          value={currentRiskLabel}
          onChange={e => {
            const opt = RISK_OPTIONS.find(o => o.label === e.target.value) ?? RISK_OPTIONS[0]
            onChange({ ...params, page: 0, minRisk: opt.min, maxRisk: opt.max })
          }}
        >
          {RISK_OPTIONS.map(o => (
            <option key={o.label} value={o.label}>{o.label}</option>
          ))}
        </select>
      </div>

      {/* Clear filters */}
      <button
        className="rounded-md px-3 py-1.5 text-xs text-gray-500 hover:bg-gray-100 hover:text-gray-700 border border-gray-300"
        onClick={() => onChange({ page: 0, size: params.size })}
      >
        Clear filters
      </button>
    </div>
  )
}

// ── Main page ─────────────────────────────────────────────────────────────────

/**
 * Renders the Audit Log page with filters, a paginated table, and a detail drawer.
 */
export function AuditLog() {
  // Filter + pagination state — starts on page 0, 25 per page, no filters
  const [params, setParams] = useState<LogsParams>({ page: 0, size: 25 })

  // Selected log entry for the detail drawer; null = drawer closed
  const [selected, setSelected] = useState<PromptLog | null>(null)

  const { data, isLoading, isError, error } = useLogs(params)

  return (
    <div className="p-6 space-y-4">
      {/* Page header */}
      <div>
        <h1 className="text-xl font-semibold text-gray-900">Audit Log</h1>
        <p className="mt-0.5 text-sm text-gray-500">
          Every proxied AI request, with risk scores and full prompt/response detail.
        </p>
      </div>

      {/* Filter controls */}
      <FilterBar params={params} onChange={setParams} />

      {/* ── Table ──────────────────────────────────────────────────────────── */}
      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm">
        {isLoading && (
          <p className="px-5 py-6 text-sm text-gray-400 animate-pulse">Loading…</p>
        )}

        {isError && (
          <p className="px-5 py-6 text-sm text-red-600">
            Failed to load logs: {(error as Error)?.message}
          </p>
        )}

        {!isLoading && !isError && (
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <thead className="bg-gray-50 text-xs font-semibold uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3 text-left">Time</th>
                <th className="px-4 py-3 text-left">Provider / Model</th>
                <th className="px-4 py-3 text-left">Prompt preview</th>
                <th className="px-4 py-3 text-left">Risk</th>
                <th className="px-4 py-3 text-left">Sig</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data?.content.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-gray-400">
                    No records found.
                  </td>
                </tr>
              )}
              {data?.content.map(log => (
                <tr
                  key={log.id}
                  className="cursor-pointer hover:bg-sentinel-50 transition-colors"
                  onClick={() => setSelected(log)}
                  // Keyboard accessibility — rows can be activated with Enter/Space
                  tabIndex={0}
                  role="button"
                  aria-label={`View detail for request ${log.id}`}
                  onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') setSelected(log) }}
                >
                  <td className="whitespace-nowrap px-4 py-3 text-gray-600">
                    {formatDate(log.requestTime)}
                  </td>
                  <td className="whitespace-nowrap px-4 py-3 font-mono text-gray-700">
                    <span className="text-xs text-gray-400">{log.provider}</span>
                    <span className="mx-1 text-gray-300">/</span>
                    {log.model}
                  </td>
                  <td className="px-4 py-3 text-gray-600 max-w-xs">
                    {truncate(log.prompt)}
                  </td>
                  <td className="px-4 py-3">
                    <RiskBadge score={log.riskScore} />
                  </td>
                  <td className="px-4 py-3">
                    <SignatureBadge signature={log.signature} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* ── Pagination ─────────────────────────────────────────────────────── */}
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between text-sm text-gray-600">
          <p>
            Showing {(data.number * data.size) + 1}–{Math.min((data.number + 1) * data.size, data.totalElements)} of {data.totalElements.toLocaleString()}
          </p>
          <div className="flex items-center gap-2">
            <button
              className="flex items-center gap-1 rounded-md border border-gray-300 px-3 py-1.5 text-xs hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
              onClick={() => setParams(p => ({ ...p, page: (p.page ?? 0) - 1 }))}
              disabled={data.first}
            >
              <ChevronLeft className="h-3 w-3" />
              Previous
            </button>
            <span className="text-xs text-gray-500">
              Page {data.number + 1} of {data.totalPages}
            </span>
            <button
              className="flex items-center gap-1 rounded-md border border-gray-300 px-3 py-1.5 text-xs hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
              onClick={() => setParams(p => ({ ...p, page: (p.page ?? 0) + 1 }))}
              disabled={data.last}
            >
              Next
              <ChevronRight className="h-3 w-3" />
            </button>
          </div>
        </div>
      )}

      {/* Detail drawer — rendered outside the table so it overlays everything */}
      {selected && (
        <DetailDrawer key={selected.id} log={selected} onClose={() => setSelected(null)} />
      )}
    </div>
  )
}
