-- Adaptive-gate observability: make the gate that was actually applied, and the EWMA that
-- produced it, queryable per CDR row instead of buried in the free-text `detail` column.
-- H2 MODE=PostgreSQL and PostgreSQL both accept ADD COLUMN IF NOT EXISTS.
ALTER TABLE ussd_cdr ADD COLUMN IF NOT EXISTS gate_ms BIGINT;
ALTER TABLE ussd_cdr ADD COLUMN IF NOT EXISTS observed_ewma_ms BIGINT;
