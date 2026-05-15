import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// Vitest needs this triple-slash reference to type-check test globals (describe, it, expect)
/// <reference types="vitest" />

/**
 * Vite configuration for SentinelAI frontend.
 *
 * Key decisions:
 * - Dev proxy: routes /api/* to the Spring Boot backend so the browser
 *   never makes cross-origin requests during development.
 * - Path alias: @/ maps to src/ for clean imports like '@/components/Foo'.
 * - Source maps: enabled in prod builds to help debug minified errors.
 */
export default defineConfig({
  plugins: [
    react(), // Enables Fast Refresh and JSX transform for React 18
  ],

  resolve: {
    alias: {
      // @/ → src/ — avoids brittle relative paths like '../../../components'
      '@': path.resolve(__dirname, './src'),
    },
  },

  server: {
    port: 5173,
    proxy: {
      // Proxy all /api/* requests to the Spring Boot backend during local dev.
      // This avoids CORS issues without configuring CORS on the backend.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },

  build: {
    outDir: 'dist',
    sourcemap: true, // Enables source-mapped stack traces in production error reporting
  },

  // ── Vitest configuration ────────────────────────────────────────────────────
  // Vitest reads config from vite.config.ts so we don't need a separate
  // vitest.config.ts. Tests run in jsdom to simulate a browser environment.
  test: {
    globals: true,        // Exposes describe/it/expect/vi globally (no imports needed)
    environment: 'jsdom', // Simulates browser DOM for React component tests
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/main.tsx', 'src/test/**'],
      // Minimum thresholds — CI fails if coverage drops below these
      thresholds: {
        lines: 70,
        functions: 70,
        branches: 60,
      },
    },
  },
})
