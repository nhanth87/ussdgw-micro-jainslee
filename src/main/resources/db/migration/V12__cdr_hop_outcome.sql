-- MAP2MAP / hop outcome columns — queryable truth (reject ≠ timeout ≠ AS re-arm).
-- detail pipe k=v still carries the full story; these columns mirror the keys.

ALTER TABLE ussd_cdr ADD COLUMN IF NOT EXISTS hop_outcome VARCHAR(32);
ALTER TABLE ussd_cdr ADD COLUMN IF NOT EXISTS refuse_reason VARCHAR(128);
ALTER TABLE ussd_cdr ADD COLUMN IF NOT EXISTS as_ussd VARCHAR(256);

CREATE INDEX IF NOT EXISTS idx_ussd_cdr_hop_outcome ON ussd_cdr (hop_outcome);
CREATE INDEX IF NOT EXISTS idx_ussd_cdr_status_hop ON ussd_cdr (status, hop_outcome);
