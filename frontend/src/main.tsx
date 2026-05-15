import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import './index.css'

/**
 * TanStack Query client with SentinelAI-specific defaults.
 *
 * staleTime 30s — audit log entries are immutable once written; short caching
 * reduces API calls without showing stale risk data for too long.
 *
 * retry 1 — surface API errors to the user quickly rather than silently
 * retrying three times (the TanStack default) and delaying error feedback.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {/* QueryClientProvider makes the queryClient available to all useQuery hooks */}
    <QueryClientProvider client={queryClient}>
      {/* BrowserRouter enables React Router's <Routes> and <Link> components */}
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
)
