/**
 * Unit tests for the getRiskLevel() pure function in types/index.ts.
 *
 * getRiskLevel() is used by RiskBadge, filter logic, and any future
 * alert threshold checks. Testing it in isolation ensures the business
 * rule (0.7 = HIGH, 0.4 = MEDIUM) is never accidentally changed.
 */

import { describe, it, expect } from 'vitest'
import { getRiskLevel } from './index'

describe('getRiskLevel', () => {

  // ── Boundary values ──────────────────────────────────────────────────────────

  it('returns HIGH for score exactly 0.7', () => {
    expect(getRiskLevel(0.7)).toBe('HIGH')
  })

  it('returns HIGH for score above 0.7', () => {
    expect(getRiskLevel(1.0)).toBe('HIGH')
    expect(getRiskLevel(0.91)).toBe('HIGH')
  })

  it('returns MEDIUM for score exactly 0.4', () => {
    expect(getRiskLevel(0.4)).toBe('MEDIUM')
  })

  it('returns MEDIUM for score in (0.4, 0.7)', () => {
    expect(getRiskLevel(0.55)).toBe('MEDIUM')
    expect(getRiskLevel(0.6999)).toBe('MEDIUM')
  })

  it('returns LOW for score below 0.4', () => {
    expect(getRiskLevel(0.39)).toBe('LOW')
    expect(getRiskLevel(0.0)).toBe('LOW')
    expect(getRiskLevel(0.12)).toBe('LOW')
  })

  // ── Null / undefined handling ─────────────────────────────────────────────

  it('returns UNKNOWN for null score', () => {
    expect(getRiskLevel(null)).toBe('UNKNOWN')
  })

  it('returns UNKNOWN for undefined score', () => {
    expect(getRiskLevel(undefined)).toBe('UNKNOWN')
  })

  // ── Exhaustive boundary sweep ─────────────────────────────────────────────

  it('never returns HIGH for score just below 0.7', () => {
    expect(getRiskLevel(0.6999999)).toBe('MEDIUM')
  })

  it('never returns MEDIUM for score just below 0.4', () => {
    expect(getRiskLevel(0.3999999)).toBe('LOW')
  })
})
