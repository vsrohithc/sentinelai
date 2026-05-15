/**
 * Vitest global test setup.
 *
 * This file runs once before each test file. It extends Vitest's expect()
 * with @testing-library/jest-dom matchers so tests can write assertions like:
 *   expect(element).toBeInTheDocument()
 *   expect(button).toBeDisabled()
 *   expect(badge).toHaveTextContent('High')
 */
import '@testing-library/jest-dom'
