/**
 * Typed fetch wrapper for the SentinelAI backend API.
 *
 * All API functions call `apiFetch` instead of `fetch` directly so that:
 *  - The base URL is set in one place (via VITE_API_BASE_URL or the Vite dev proxy)
 *  - HTTP errors (4xx, 5xx) are converted to thrown Error objects with useful messages
 *  - JSON parsing happens in one place
 *
 * The Vite dev server is configured to proxy `/api/*` to `http://localhost:8080`
 * so no CORS configuration is needed during local development.
 */

/**
 * The base URL for all API requests.
 *
 * In development: empty string — the Vite proxy forwards /api/* to port 8080.
 * In production: set VITE_API_BASE_URL to your backend origin (e.g. https://api.yourco.com).
 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

/**
 * Makes a typed GET request to the SentinelAI API.
 *
 * Throws an Error if the HTTP status is not in the 2xx range, including the
 * status code and the response body text in the error message so callers and
 * TanStack Query error boundaries can surface useful information.
 *
 * @param path    - the API path, e.g. "/api/logs?page=0&size=25"
 * @param signal  - optional AbortSignal for cancellation (passed by TanStack Query)
 * @returns       the parsed JSON response body cast to type T
 */
export async function apiFetch<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    headers: {
      // Tell the backend to return JSON — Spring Boot defaults to this for @RestController
      // but being explicit avoids any content-negotiation surprises.
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    // Include the status and body text so the error shows up usefully in the console
    // and in React Query's error state.
    const body = await response.text().catch(() => '(no body)')
    throw new Error(`API error ${response.status}: ${body}`)
  }

  return response.json() as Promise<T>
}
