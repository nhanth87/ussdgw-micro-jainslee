-- Tenant HTTP Application Server wire format (XML default | JSON).
ALTER TABLE ussd_tenant ADD COLUMN IF NOT EXISTS http_as_wire_format VARCHAR(8) NOT NULL DEFAULT 'XML';
