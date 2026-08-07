-- AS-facing SIP trunks + tenant binding.

CREATE TABLE IF NOT EXISTS ussd_sip_trunk (
    trunk_id             VARCHAR(64)  PRIMARY KEY,
    display_name         VARCHAR(256),
    peer_host            VARCHAR(256) NOT NULL,
    peer_port            INT          NOT NULL DEFAULT 5060,
    transport            VARCHAR(8)   NOT NULL DEFAULT 'UDP',
    from_uri             VARCHAR(512),
    request_uri_template VARCHAR(512),
    inbound_body         VARCHAR(16)  NOT NULL DEFAULT 'BODY',
    tenant_id            VARCHAR(64),
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ussd_sip_trunk_tenant ON ussd_sip_trunk (tenant_id);

ALTER TABLE ussd_tenant ADD COLUMN IF NOT EXISTS sip_trunk_id VARCHAR(64);
