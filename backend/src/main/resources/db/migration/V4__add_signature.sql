-- V4: Add Ed25519 signature column to prompt_logs
--
-- Each audit record is signed at write time using the operator's Ed25519 private key.
-- The signature covers: id, request_time, prompt, response, model, provider, risk_score
-- in a deterministic pipe-delimited canonical form.
--
-- NULL means the record was created before signing was enabled, or signing is disabled.
-- A non-null signature can be verified offline using the published public key.

ALTER TABLE prompt_logs
    ADD COLUMN signature TEXT;

COMMENT ON COLUMN prompt_logs.signature IS
    'Base64-encoded Ed25519 signature over the canonical record fields. '
    'NULL for records created before signing was enabled. '
    'Verify via GET /api/logs/{id}/verify or offline with the public key at GET /api/public-key.';
