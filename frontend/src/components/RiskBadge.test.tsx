/**
 * Unit tests for the RiskBadge component.
 *
 * RiskBadge is the most reused UI primitive in the dashboard — it appears in
 * the audit log table (every row) and the detail drawer. Getting its colour
 * mapping and score formatting correct is critical for operator trust.
 */

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RiskBadge } from './RiskBadge'

describe('RiskBadge', () => {

  it('shows "High" for riskScore >= 0.7', () => {
    render(<RiskBadge score={0.91} />)
    expect(screen.getByText('High')).toBeInTheDocument()
  })

  it('shows "Medium" for riskScore in [0.4, 0.7)', () => {
    render(<RiskBadge score={0.55} />)
    expect(screen.getByText('Medium')).toBeInTheDocument()
  })

  it('shows "Low" for riskScore < 0.4', () => {
    render(<RiskBadge score={0.12} />)
    expect(screen.getByText('Low')).toBeInTheDocument()
  })

  it('shows "Unknown" for null riskScore', () => {
    render(<RiskBadge score={null} />)
    expect(screen.getByText('Unknown')).toBeInTheDocument()
  })

  it('shows "Unknown" for undefined riskScore', () => {
    render(<RiskBadge score={undefined} />)
    expect(screen.getByText('Unknown')).toBeInTheDocument()
  })

  it('shows "High" at exactly the 0.7 boundary', () => {
    render(<RiskBadge score={0.7} />)
    expect(screen.getByText('High')).toBeInTheDocument()
  })

  it('shows "Medium" at exactly the 0.4 boundary', () => {
    render(<RiskBadge score={0.4} />)
    expect(screen.getByText('Medium')).toBeInTheDocument()
  })

  it('shows numeric score when showScore=true', () => {
    render(<RiskBadge score={0.91} showScore />)
    // The badge should contain both the level label and the formatted score
    expect(screen.getByText('High')).toBeInTheDocument()
    expect(screen.getByText('(0.910)')).toBeInTheDocument()
  })

  it('does not show numeric score when showScore is omitted', () => {
    const { container } = render(<RiskBadge score={0.91} />)
    // The raw score string should not appear anywhere in the badge
    expect(container.textContent).not.toContain('0.910')
  })

  it('has accessible aria-label for screen readers', () => {
    render(<RiskBadge score={0.91} />)
    const badge = screen.getByRole('generic', { name: /risk level: high/i })
    expect(badge).toBeInTheDocument()
  })

  it('has accessible aria-label for unknown score', () => {
    render(<RiskBadge score={null} />)
    expect(screen.getByLabelText(/risk level: unknown/i)).toBeInTheDocument()
  })

  it('applies red styling for high risk', () => {
    const { container } = render(<RiskBadge score={0.91} />)
    const badge = container.firstChild as HTMLElement
    expect(badge.className).toContain('bg-red-100')
    expect(badge.className).toContain('text-red-800')
  })

  it('applies amber styling for medium risk', () => {
    const { container } = render(<RiskBadge score={0.55} />)
    const badge = container.firstChild as HTMLElement
    expect(badge.className).toContain('bg-amber-100')
  })

  it('applies green styling for low risk', () => {
    const { container } = render(<RiskBadge score={0.12} />)
    const badge = container.firstChild as HTMLElement
    expect(badge.className).toContain('bg-green-100')
  })
})
