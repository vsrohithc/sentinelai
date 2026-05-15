/**
 * TanStack Query hook for license tier information.
 *
 * Wraps fetchLicenseInfo() so the Settings page gets cached license data
 * with automatic retry on failure.
 */

import { useQuery } from '@tanstack/react-query'
import { fetchLicenseInfo } from '../api/license'
import type { LicenseInfo } from '../types'

/**
 * Fetches the current license tier and retention configuration.
 *
 * Data is considered stable (does not change during a session), so we set
 * a long staleTime and no background refetch interval.
 *
 * @returns TanStack Query result containing LicenseInfo
 */
export function useLicense() {
  return useQuery<LicenseInfo, Error>({
    queryKey: ['license', 'info'],
    queryFn: ({ signal }) => fetchLicenseInfo(signal),
    // License tier doesn't change mid-session — refetch only on mount.
    staleTime: 5 * 60 * 1000, // 5 minutes
  })
}
