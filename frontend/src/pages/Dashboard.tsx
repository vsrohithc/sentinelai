/**
 * Dashboard page — entry point for the SentinelAI governance UI.
 *
 * Shows four summary stat cards (total / high / medium / low risk) and
 * a 30-day daily risk trend line chart built with Recharts.
 *
 * Data is fetched by the useDashboard() hook which refreshes every 60 seconds
 * in the background, so the numbers stay current without a manual reload.
 */

import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts'
import { AlertTriangle, AlertCircle, CheckCircle, HelpCircle } from 'lucide-react'
import { useDashboard } from '../hooks/useDashboard'

// ── Stat card component ───────────────────────────────────────────────────────

interface StatCardProps {
  label: string
  value: number | string
  Icon: React.ComponentType<{ className?: string }>
  iconClass: string
  bgClass: string
}

/**
 * Single summary stat card — label, large number, coloured icon.
 */
function StatCard({ label, value, Icon, iconClass, bgClass }: StatCardProps) {
  return (
    <div className="flex items-center gap-4 rounded-xl border border-gray-200 bg-white px-5 py-4 shadow-sm">
      <div className={`flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-full ${bgClass}`}>
        <Icon className={`h-5 w-5 ${iconClass}`} aria-hidden="true" />
      </div>
      <div>
        <p className="text-sm text-gray-500">{label}</p>
        <p className="text-2xl font-bold tabular-nums text-gray-900">{value.toLocaleString()}</p>
      </div>
    </div>
  )
}

// ── Dashboard page ────────────────────────────────────────────────────────────

/**
 * Renders the Dashboard page with stat cards and a risk trend chart.
 *
 * Loading and error states are handled inline — no separate component needed
 * at this scale.
 */
export function Dashboard() {
  const { data, isLoading, isError, error } = useDashboard()

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <p className="text-gray-400 animate-pulse">Loading dashboard…</p>
      </div>
    )
  }

  if (isError || !data) {
    return (
      <div className="p-8">
        <p className="text-red-600">Failed to load dashboard stats: {(error as Error)?.message}</p>
      </div>
    )
  }

  return (
    <div className="p-6 space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-xl font-semibold text-gray-900">Dashboard</h1>
        <p className="mt-0.5 text-sm text-gray-500">
          Summary of AI governance activity across all proxied requests.
        </p>
      </div>

      {/* ── Stat cards ─────────────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard
          label="Total Requests"
          value={data.totalLogs}
          Icon={CheckCircle}
          iconClass="text-sentinel-600"
          bgClass="bg-sentinel-50"
        />
        <StatCard
          label="High Risk"
          value={data.highRiskCount}
          Icon={AlertTriangle}
          iconClass="text-red-600"
          bgClass="bg-red-50"
        />
        <StatCard
          label="Medium Risk"
          value={data.mediumRiskCount}
          Icon={AlertCircle}
          iconClass="text-amber-600"
          bgClass="bg-amber-50"
        />
        <StatCard
          label="Detection Failed"
          value={data.nullRiskCount}
          Icon={HelpCircle}
          iconClass="text-gray-500"
          bgClass="bg-gray-100"
        />
      </div>

      {/* ── Risk trend chart ────────────────────────────────────────────────── */}
      <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
        <h2 className="mb-4 text-sm font-semibold text-gray-700">
          Risk Trend — Last 30 Days
        </h2>

        {data.dailySeries.length === 0 ? (
          <p className="py-12 text-center text-sm text-gray-400">
            No data yet. Requests will appear here once the proxy starts receiving traffic.
          </p>
        ) : (
          // ResponsiveContainer makes the chart fill the available width at any
          // viewport size without requiring a fixed pixel width.
          <ResponsiveContainer width="100%" height={280}>
            <LineChart
              data={data.dailySeries}
              margin={{ top: 4, right: 16, left: 0, bottom: 4 }}
            >
              <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 11, fill: '#9ca3af' }}
                // Show only every 7th label to avoid crowding on narrow viewports
                interval={6}
              />
              <YAxis
                tick={{ fontSize: 11, fill: '#9ca3af' }}
                allowDecimals={false}
              />
              <Tooltip
                contentStyle={{ fontSize: 12, borderRadius: 6 }}
                labelFormatter={(label) => `Date: ${label}`}
              />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Line
                type="monotone"
                dataKey="highRiskCount"
                name="High Risk"
                stroke="#ef4444"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4 }}
              />
              <Line
                type="monotone"
                dataKey="mediumRiskCount"
                name="Medium Risk"
                stroke="#f59e0b"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4 }}
              />
              <Line
                type="monotone"
                dataKey="lowRiskCount"
                name="Low Risk"
                stroke="#22c55e"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4 }}
              />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  )
}
