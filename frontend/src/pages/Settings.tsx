/**
 * Settings page — license key display and retention tier information.
 *
 * Fetches live license information from GET /api/license/info via the
 * useLicense() hook and displays the current tier and retention window.
 *
 * The license key itself is never displayed — only the resolved tier name
 * and retention days are shown, which come from the backend response.
 */

import { Shield, Clock, Key, CheckCircle, AlertCircle } from 'lucide-react'
import { useLicense } from '../hooks/useLicense'
import type { LicenseTier } from '../types'

// ── Tier display helpers ──────────────────────────────────────────────────────

/** Human-readable label for each tier */
const TIER_LABELS: Record<LicenseTier, string> = {
  FREE:     'Free',
  PAID_30:  'Paid — 30 days',
  PAID_90:  'Paid — 90 days',
  PAID_365: 'Paid — 365 days',
}

/** Tailwind colour classes for the tier badge */
const TIER_STYLES: Record<LicenseTier, string> = {
  FREE:     'bg-gray-100 text-gray-700 ring-1 ring-gray-300',
  PAID_30:  'bg-blue-100 text-blue-800 ring-1 ring-blue-300',
  PAID_90:  'bg-indigo-100 text-indigo-800 ring-1 ring-indigo-300',
  PAID_365: 'bg-sentinel-100 text-sentinel-800 ring-1 ring-sentinel-300',
}

// ── License info card ─────────────────────────────────────────────────────────

/**
 * Renders the live license tier card, fetched from the backend.
 */
function LicenseInfoCard() {
  const { data, isLoading, isError, error } = useLicense()

  if (isLoading) {
    return (
      <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
        <div className="flex items-center gap-2 mb-4">
          <Key className="h-4 w-4 text-sentinel-600" aria-hidden="true" />
          <h2 className="text-sm font-semibold text-gray-900">License</h2>
        </div>
        <p className="text-sm text-gray-400 animate-pulse">Loading license info…</p>
      </div>
    )
  }

  if (isError || !data) {
    return (
      <div className="rounded-xl border border-red-200 bg-white p-5 shadow-sm">
        <div className="flex items-center gap-2 mb-4">
          <AlertCircle className="h-4 w-4 text-red-500" aria-hidden="true" />
          <h2 className="text-sm font-semibold text-gray-900">License</h2>
        </div>
        <p className="text-sm text-red-600">
          Could not load license info: {(error as Error)?.message ?? 'Unknown error'}
        </p>
        <p className="mt-1 text-xs text-gray-400">
          Ensure the backend is running and your{' '}
          <code className="font-mono bg-gray-100 px-1 rounded">VITE_LICENSE_KEY</code> env var is set if you have a paid license.
        </p>
      </div>
    )
  }

  const tierLabel = TIER_LABELS[data.tier] ?? data.tier
  const tierStyle = TIER_STYLES[data.tier] ?? TIER_STYLES.FREE
  const isPaid = data.tier !== 'FREE'

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm space-y-4">
      <div className="flex items-center gap-2">
        <Key className="h-4 w-4 text-sentinel-600" aria-hidden="true" />
        <h2 className="text-sm font-semibold text-gray-900">License</h2>
      </div>

      {/* Current tier display */}
      <div className="flex items-center gap-4">
        <div>
          <p className="text-xs text-gray-400 mb-1">Current tier</p>
          <span className={`inline-flex items-center rounded-full px-3 py-1 text-sm font-semibold ${tierStyle}`}>
            {isPaid && <CheckCircle className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />}
            {tierLabel}
          </span>
        </div>
        <div>
          <p className="text-xs text-gray-400 mb-1">Log retention</p>
          <p className="text-2xl font-bold tabular-nums text-gray-900">
            {data.retentionDays}
            <span className="ml-1 text-sm font-normal text-gray-500">days</span>
          </p>
        </div>
      </div>

      {/* Explanation */}
      <p className="text-sm text-gray-600">
        {isPaid
          ? `Your logs are retained for ${data.retentionDays} days. The nightly cleanup job removes records older than this window.`
          : 'You are on the free tier. Logs are retained for 7 days. Supply an X-Sentinel-License header to unlock longer retention.'}
      </p>

      {/* How to set the license key */}
      <div className="rounded-md border border-gray-100 bg-gray-50 p-3 text-xs text-gray-600">
        <p className="font-medium text-gray-700 mb-1">How license keys work</p>
        <p>
          API callers pass their license key in the{' '}
          <code className="rounded bg-white px-1 py-0.5 font-mono border border-gray-200">X-Sentinel-License</code>{' '}
          HTTP header. The dashboard reads the key from the{' '}
          <code className="rounded bg-white px-1 py-0.5 font-mono border border-gray-200">VITE_LICENSE_KEY</code>{' '}
          build-time environment variable to display your tier here.
        </p>
      </div>
    </div>
  )
}

// ── Settings page ─────────────────────────────────────────────────────────────

/**
 * Renders the full Settings page: live license card + retention tier table + about.
 */
export function Settings() {
  return (
    <div className="p-6 space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-xl font-semibold text-gray-900">Settings</h1>
        <p className="mt-0.5 text-sm text-gray-500">
          License key and data retention configuration for this SentinelAI instance.
        </p>
      </div>

      {/* Live license info card */}
      <LicenseInfoCard />

      {/* ── Retention tiers reference table ───────────────────────────────── */}
      <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm space-y-4">
        <div className="flex items-center gap-2">
          <Clock className="h-4 w-4 text-sentinel-600" aria-hidden="true" />
          <h2 className="text-sm font-semibold text-gray-900">Retention Tiers</h2>
        </div>

        <p className="text-sm text-gray-600">
          Audit log retention is determined by the license tier. A nightly cleanup job
          removes logs older than the tier's retention window.
        </p>

        <table className="min-w-full text-sm divide-y divide-gray-200">
          <thead className="bg-gray-50 text-xs font-semibold uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-2.5 text-left">Tier</th>
              <th className="px-4 py-2.5 text-left">License key required</th>
              <th className="px-4 py-2.5 text-left">Retention</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 text-gray-700">
            <tr>
              <td className="px-4 py-2.5 font-medium">Free</td>
              <td className="px-4 py-2.5 text-gray-500">No</td>
              <td className="px-4 py-2.5">7 days</td>
            </tr>
            <tr>
              <td className="px-4 py-2.5 font-medium">Paid 30</td>
              <td className="px-4 py-2.5 text-gray-500">Yes</td>
              <td className="px-4 py-2.5">30 days</td>
            </tr>
            <tr>
              <td className="px-4 py-2.5 font-medium">Paid 90</td>
              <td className="px-4 py-2.5 text-gray-500">Yes</td>
              <td className="px-4 py-2.5">90 days</td>
            </tr>
            <tr>
              <td className="px-4 py-2.5 font-medium">Paid 365</td>
              <td className="px-4 py-2.5 text-gray-500">Yes</td>
              <td className="px-4 py-2.5">365 days</td>
            </tr>
          </tbody>
        </table>
      </div>

      {/* ── About card ────────────────────────────────────────────────────── */}
      <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm space-y-3">
        <div className="flex items-center gap-2">
          <Shield className="h-4 w-4 text-sentinel-600" aria-hidden="true" />
          <h2 className="text-sm font-semibold text-gray-900">About SentinelAI</h2>
        </div>
        <p className="text-sm text-gray-600">
          SentinelAI is a self-hosted AI governance proxy. All prompt and response data
          stays in your own cloud — no data is sent to external services beyond the AI
          model API and detection API you configure.
        </p>
        <p className="text-xs text-gray-400">
          SentinelAI · Open Source · Self-hosted
        </p>
      </div>
    </div>
  )
}
