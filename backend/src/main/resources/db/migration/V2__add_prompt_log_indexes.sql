-- =============================================================================
-- V2__add_prompt_log_indexes.sql
--
-- Adds performance indexes to the prompt_logs table.
--
-- These indexes are created in a separate migration from V1 so that:
--   1. The table can be inspected / tested without index overhead during dev.
--   2. Each migration is a single logical unit of work — easier to reason
--      about and roll back independently if needed.
--
-- Index strategy:
--   - created_at  : the dashboard's primary sort key and the retention job's
--                   filter predicate (DELETE WHERE created_at < cutoff).
--   - license_key : the retention job deletes by license_key to enforce per-tier
--                   retention windows efficiently.
--   - risk_score  : the audit log UI filters by risk level; a partial index
--                   covering only non-null scores keeps it compact and fast.
-- =============================================================================

-- Index on created_at for:
--   1. The nightly retention job: DELETE FROM prompt_logs WHERE created_at < $1
--   2. The audit log UI: paginated queries ordered by created_at DESC
--   3. The dashboard trend chart: aggregated counts grouped by date
CREATE INDEX idx_prompt_logs_created_at
    ON prompt_logs (created_at DESC);

COMMENT ON INDEX idx_prompt_logs_created_at
    IS 'Supports time-ordered queries and the nightly retention cleanup job.';

-- Index on license_key for:
--   The retention job filters by license_key to compute per-tenant cutoff dates
--   and delete only that tenant's expired rows in a single efficient scan.
CREATE INDEX idx_prompt_logs_license_key
    ON prompt_logs (license_key);

COMMENT ON INDEX idx_prompt_logs_license_key
    IS 'Supports per-tenant retention cleanup and license-scoped audit queries.';

-- Partial index on risk_score for:
--   The audit log UI's "filter by risk level" feature — most queries target
--   medium/high risk rows. Excluding NULLs keeps the index small because
--   failed detection calls (risk_score IS NULL) are not filterable by score.
CREATE INDEX idx_prompt_logs_risk_score
    ON prompt_logs (risk_score)
    WHERE risk_score IS NOT NULL;

COMMENT ON INDEX idx_prompt_logs_risk_score
    IS 'Partial index (non-null only) for risk-level filtering in the audit UI.';
