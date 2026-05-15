/**
 * App — root routing component for the SentinelAI dashboard.
 *
 * Route map:
 *   /          → Dashboard  (summary stat cards + risk trend chart)
 *   /logs      → AuditLog   (paginated table + prompt/response detail drawer)
 *   /settings  → Settings   (license key display + retention tier info)
 *
 * All routes are wrapped in the Layout component which renders the persistent
 * sidebar navigation. The Layout's <Outlet /> renders the matched page.
 */

import { Routes, Route, Navigate } from 'react-router-dom'
import { Layout } from './components/Layout'
import { Dashboard } from './pages/Dashboard'
import { AuditLog } from './pages/AuditLog'
import { Settings } from './pages/Settings'

function App() {
  return (
    <Routes>
      {/* The Layout wraps all routes so the sidebar is always visible */}
      <Route element={<Layout />}>
        <Route index element={<Dashboard />} />
        <Route path="logs" element={<AuditLog />} />
        <Route path="settings" element={<Settings />} />
        {/* Catch-all: redirect unknown paths back to the dashboard */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

export default App
