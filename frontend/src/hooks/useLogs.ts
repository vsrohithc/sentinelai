/**
 * TanStack Query hook for paginated audit log data.
 *
 * Wraps fetchLogs() in useQuery so components get:
 *  - Automatic caching keyed by page + filter params
 *  - Loading / error / success states
 *  - Background refetch on window focus
 *  - Request cancellation when params change mid-flight
 */

import { useQuery } from '@tanstack/react-query'
import { fetchLogs } from '../api/logs'
import type { LogsParams } from '../api/logs'
import type { PagedResponse, PromptLog } from '../types'

/**
 * Fetches a paginated page of audit log entries with optional filters.
 *
 * The query key includes all filter params so that changing any filter
 * immediately triggers a fresh fetch and the cache is invalidated correctly.
 *
 * @param params - filter and pagination parameters; defaults to page 0, size 25
 * @returns TanStack Query result containing the paged log data
 */
export function useLogs(params: LogsParams = {}) {
  return useQuery<PagedResponse<PromptLog>, Error>({
    // All params are part of the key so each unique filter combo is cached separately.
    queryKey: ['logs', params],
    queryFn: ({ signal }) => fetchLogs(params, signal),
    // Keep previous page data visible while the next page loads to avoid layout
    // flicker when the user clicks a pagination button.
    placeholderData: (prev) => prev,
    // Refetch every 30 seconds so the Audit Log table stays reasonably fresh
    // without the user having to manually reload the page.
    refetchInterval: 30_000,
  })
}
