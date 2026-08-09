-- Session ledger: 1 correlationId → 1 admin/DB CDR row (upsert).
-- Legacy ussd_cdr stays as historical append-only event tape (no DELETE / no UNIQUE on it).
-- New writes go to ussd_cdr_session via CdrDbFlusher coalesce + UPSERT.
-- File logger USSD_CDR remains append-only.

CREATE TABLE IF NOT EXISTS ussd_cdr_session (
  id                UUID PRIMARY KEY,
  recorded_at       TIMESTAMP WITH TIME ZONE NOT NULL,
  correlation_id    VARCHAR(128) NOT NULL,
  phase             VARCHAR(32)  NOT NULL,
  status            VARCHAR(64)  NOT NULL,
  msisdn            VARCHAR(32),
  short_code        VARCHAR(32),
  detail            VARCHAR(1024),
  network_id        INT,
  tenant_id         VARCHAR(128),
  origination_type  VARCHAR(32),
  gate_ms           BIGINT,
  observed_ewma_ms  BIGINT,
  hop_outcome       VARCHAR(32),
  refuse_reason     VARCHAR(128),
  as_ussd           VARCHAR(256),
  csv_line          VARCHAR(4000) NOT NULL,
  started_at        TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
  event_count       INT NOT NULL DEFAULT 1,
  events_json       VARCHAR(8192),
  CONSTRAINT uk_ussd_cdr_session_corr UNIQUE (correlation_id)
);

CREATE INDEX IF NOT EXISTS idx_ussd_cdr_session_updated ON ussd_cdr_session (updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_ussd_cdr_session_msisdn ON ussd_cdr_session (msisdn);
CREATE INDEX IF NOT EXISTS idx_ussd_cdr_session_tenant ON ussd_cdr_session (tenant_id);
CREATE INDEX IF NOT EXISTS idx_ussd_cdr_session_tenant_updated ON ussd_cdr_session (tenant_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_ussd_cdr_session_status ON ussd_cdr_session (status);
