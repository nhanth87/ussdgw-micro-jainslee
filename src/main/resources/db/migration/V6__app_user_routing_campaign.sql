-- API app-users (NI key + routing ownership), short_code binding, campaign approve columns.

CREATE TABLE IF NOT EXISTS ussd_app_user (
    username       VARCHAR(64)  PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    api_key_hash   VARCHAR(128) NOT NULL,
    api_key_fp     VARCHAR(8),
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ussd_app_user_tenant ON ussd_app_user (tenant_id);

ALTER TABLE ussd_short_code ADD COLUMN IF NOT EXISTS app_username VARCHAR(64);

ALTER TABLE ussd_campaign ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE ussd_campaign ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP;
ALTER TABLE ussd_campaign ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(64);
ALTER TABLE ussd_campaign ADD COLUMN IF NOT EXISTS review_note VARCHAR(512);
