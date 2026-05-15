-- =============================================================================
-- V3__add_provider_column.sql
--
-- Adds a `provider` column to the prompt_logs table.
--
-- Motivation:
--   Phase 5 introduced multi-provider routing (OpenAI, Anthropic, Gemini,
--   Azure OpenAI). Without this column, audit records cannot be grouped or
--   filtered by provider — a significant reporting gap for operators running
--   mixed-provider deployments.
--
-- Design notes:
--   - VARCHAR(64) matches the max size validation on ProxyRequest.provider.
--   - DEFAULT 'OPENAI' is applied to existing rows so that historical records
--     (which were proxied before multi-provider support existed) are attributed
--     to OpenAI, the only provider available at that time.
--   - The column is NOT NULL with a DEFAULT, which means the ALTER is safe on
--     a live table with existing rows — PostgreSQL fills existing rows with the
--     default value inline without a table rewrite.
--   - An index is added immediately to support future GROUP BY / filter queries
--     on provider without a sequential scan.
-- =============================================================================

-- NOT NULL DEFAULT 'OPENAI' means PostgreSQL fills all existing rows with 'OPENAI'
-- at ALTER time — no separate backfill statement is required.
ALTER TABLE prompt_logs
    ADD COLUMN provider VARCHAR(64) NOT NULL DEFAULT 'OPENAI';

-- Index supports GROUP BY provider, WHERE provider = ?, and mixed filters
CREATE INDEX idx_prompt_logs_provider ON prompt_logs (provider);

-- Column-level documentation visible in psql \d+ and pg_catalog
COMMENT ON COLUMN prompt_logs.provider
    IS 'AI provider routing key (OPENAI, ANTHROPIC, GEMINI, AZURE_OPENAI). '
       'Populated from X-Sentinel-Provider header or ProxyRequest.provider field. '
       'Defaults to OPENAI for pre-Phase-5 records.';
