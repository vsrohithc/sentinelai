import type { Config } from 'tailwindcss'

/**
 * Tailwind CSS configuration for SentinelAI.
 *
 * Extends the default palette with brand colors and semantic risk colors
 * used throughout the audit log and dashboard views.
 */
const config: Config = {
  // Scan only files that actually use Tailwind classes — keeps the CSS bundle small.
  // Must include index.html because some classes are set there directly.
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
  ],

  theme: {
    extend: {
      colors: {
        // SentinelAI brand — a strong blue used for primary actions and navigation
        sentinel: {
          50:  '#f0f4ff',
          100: '#e0e9ff',
          200: '#c7d7fe',
          300: '#a5b4fc',
          400: '#818cf8',
          500: '#3b5bdb',
          600: '#364fc7',
          700: '#2f44b0',
          800: '#243480',
          900: '#1e2f7a',
        },
        // Semantic risk level colors for the audit log table and risk badges.
        // These are intentionally distinct from the brand palette so risk status
        // is immediately recognisable without reading the label.
        risk: {
          low:    '#2f9e44', // green  — score < 0.3
          medium: '#f08c00', // amber  — score 0.3–0.7
          high:   '#e03131', // red    — score > 0.7
          none:   '#868e96', // grey   — score unavailable (detection API timed out)
        },
      },
    },
  },

  plugins: [],
}

export default config
