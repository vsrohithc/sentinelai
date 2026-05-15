/**
 * API function for the license info endpoint.
 *
 * The license key is read from the VITE_LICENSE_KEY environment variable so it
 * doesn't have to be hardcoded. In production operators set this in their build
 * environment; in development it can be left unset (the backend returns FREE tier).
 */

import type { LicenseInfo } from '../types'

/**
 * Fetches the current license tier and retention configuration from
 * GET /api/license/info.
 *
 * The license key is sent as the X-Sentinel-License header. If VITE_LICENSE_KEY
 * is not set, the header is omitted and the backend returns FREE tier info.
 *
 * @param signal - AbortSignal for request cancellation
 * @returns LicenseInfo object with tier and retentionDays
 */
export async function fetchLicenseInfo(signal?: AbortSignal): Promise<LicenseInfo> {
  // Read the license key from the build-time env variable.
  // In local dev this is typically not set, so we get FREE tier info.
  const licenseKey = import.meta.env.VITE_LICENSE_KEY as string | undefined

  const headers: Record<string, string> = { Accept: 'application/json' }
  if (licenseKey) {
    headers['X-Sentinel-License'] = licenseKey
  }

  // We call fetch directly here (rather than apiFetch) so we can set a custom header.
  const base = import.meta.env.VITE_API_BASE_URL ?? ''
  const response = await fetch(`${base}/api/license/info`, { headers, signal })

  if (!response.ok) {
    const body = await response.text().catch(() => '(no body)')
    throw new Error(`License API error ${response.status}: ${body}`)
  }

  return response.json() as Promise<LicenseInfo>
}
