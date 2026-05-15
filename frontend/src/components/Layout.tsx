/**
 * Layout — persistent shell wrapper for all pages.
 *
 * Renders a fixed left sidebar for navigation and a scrollable main content
 * area. All route-level page components are wrapped inside this Layout via
 * the root App router, so the sidebar is always visible.
 *
 * Structure:
 *   ┌──────────────────────────────┐
 *   │  Sidebar (fixed, 240px)      │
 *   │  ┌────────────────────────┐  │
 *   │  │  Logo                  │  │
 *   │  │  Nav links             │  │
 *   │  └────────────────────────┘  │
 *   ├──────────────────────────────┤
 *   │  Main content (scrollable)   │
 *   │  <Outlet /> renders here     │
 *   └──────────────────────────────┘
 */

import { NavLink, Outlet } from 'react-router-dom'
import { BarChart2, FileText, Settings as SettingsIcon, Shield } from 'lucide-react'

/**
 * Nav link descriptor — used to build the sidebar menu items programmatically
 * so adding a new route only requires one change in this array.
 */
interface NavItem {
  to: string
  label: string
  /** Lucide icon component */
  Icon: React.ComponentType<{ className?: string }>
}

const NAV_ITEMS: NavItem[] = [
  { to: '/',        label: 'Dashboard',  Icon: BarChart2  },
  { to: '/logs',    label: 'Audit Log',  Icon: FileText   },
  { to: '/settings', label: 'Settings', Icon: SettingsIcon },
]

/**
 * Renders the application shell (sidebar + main content area).
 *
 * The `<Outlet />` renders whichever route-level page component React Router
 * has matched — Dashboard, AuditLog, or Settings.
 */
export function Layout() {
  return (
    <div className="flex h-screen overflow-hidden bg-gray-50">
      {/* ── Sidebar ───────────────────────────────────────────────────────── */}
      <aside className="flex w-60 flex-shrink-0 flex-col border-r border-gray-200 bg-white">
        {/* Brand lockup */}
        <div className="flex items-center gap-2.5 px-5 py-5 border-b border-gray-100">
          <Shield className="h-6 w-6 text-sentinel-600" aria-hidden="true" />
          <span className="text-lg font-bold tracking-tight text-sentinel-700">SentinelAI</span>
        </div>

        {/* Navigation links */}
        <nav className="flex-1 overflow-y-auto px-3 py-4" aria-label="Main navigation">
          <ul className="space-y-1">
            {NAV_ITEMS.map(({ to, label, Icon }) => (
              <li key={to}>
                <NavLink
                  to={to}
                  // NavLink calls this function with { isActive } so we can apply
                  // active styling without a separate useState.
                  className={({ isActive }) =>
                    [
                      'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
                      isActive
                        ? 'bg-sentinel-50 text-sentinel-700'
                        : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900',
                    ].join(' ')
                  }
                  // Exact matching for the root route so "Dashboard" isn't always active
                  end={to === '/'}
                >
                  <Icon className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
                  {label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>

        {/* Footer — version / environment indicator */}
        <div className="border-t border-gray-100 px-5 py-3">
          <p className="text-xs text-gray-400">SentinelAI · Governance Proxy</p>
        </div>
      </aside>

      {/* ── Main content area ─────────────────────────────────────────────── */}
      <main className="flex-1 overflow-y-auto">
        {/* Outlet renders the matched route's page component */}
        <Outlet />
      </main>
    </div>
  )
}
