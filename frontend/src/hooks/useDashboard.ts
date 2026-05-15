/**
 * TanStack Query hook for dashboard summary statistics.
 *
 * Wraps fetchDashboardStats() in useQuery so the Dashboard page gets
 * cached stats data with automatic background refresh.
 */

import { useQuery } from '@tanstack/react-query'
import { fetchDashboardStats } from '../api/logs'
import type { DashboardStats } from '../types'

/**
 * Fetches summary statistics for the Dashboard page.
 *
 * Data is refreshed every 60 seconds in the background so the stat cards
 * and chart stay current without a full page reload.
 *
 * @returns TanStack Query result containing DashboardStats
 */
export function useDashboard() {
  return useQuery<DashboardStats, Error>({
    queryKey: ['dashboard', 'stats'],
    queryFn: ({ signal }) => fetchDashboardStats(signal),
    // Dashboard stats are relatively cheap to recompute and operators want
    // up-to-date numbers, so refetch every minute.
    refetchInterval: 60_000,
  })
}
