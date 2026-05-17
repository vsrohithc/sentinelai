import { ShieldCheck, ShieldX, Shield } from 'lucide-react'

interface SignatureBadgeProps {
  signature: string | null | undefined
  /** undefined = not yet verified, true = valid, false = tampered */
  valid?: boolean | null
}

/**
 * Displays the cryptographic signature status of an audit record.
 *
 * - Green shield check: signed AND verified valid
 * - Red shield X:       signed but tampered (verification failed)
 * - Blue shield:        signed but not yet verified (table row — no verify call made)
 * - Grey shield:        unsigned (signing was disabled when record was created)
 */
export function SignatureBadge({ signature, valid }: SignatureBadgeProps) {
  if (!signature) {
    return (
      <span
        className="inline-flex items-center gap-1 text-gray-400"
        aria-label="Unsigned"
        title="Unsigned — signing was not enabled when this record was created"
      >
        <Shield className="h-4 w-4" />
      </span>
    )
  }

  if (valid === false) {
    return (
      <span
        className="inline-flex items-center gap-1 text-red-500"
        aria-label="Tampered — signature invalid"
        title="Tampered — record has been modified after signing"
      >
        <ShieldX className="h-4 w-4" />
      </span>
    )
  }

  if (valid === true) {
    return (
      <span
        className="inline-flex items-center gap-1 text-green-600"
        aria-label="Verified — record is unmodified"
        title="Verified"
      >
        <ShieldCheck className="h-4 w-4" />
      </span>
    )
  }

  // Signed but not yet verified (shown in table rows — verification happens in the drawer)
  return (
    <span
      className="inline-flex items-center gap-1 text-blue-500"
      aria-label="Signed — open record to verify"
      title="Signed — open to verify"
    >
      <Shield className="h-4 w-4" />
    </span>
  )
}
