-- MAP2MAP optional fixed hop destination (service-provider / HLR-face peer style).
-- When hop_dest_gt is set: skip SRI/FAKE MSC+IMSI; UnstructuredSS-Request toward that GT/SSN
-- with redirect USSD (map2map_gt) as the string (any code; e.g. *875# → example GT/SSN).
-- When hop_dest_gt is null/blank: keep existing FAKE/SRI → MSC+IMSI path.
-- hop_dest_ssn NULL with GT set → runtime default 6 (HLR face peer).
ALTER TABLE ussd_short_code ADD COLUMN IF NOT EXISTS hop_dest_gt VARCHAR(32);
ALTER TABLE ussd_short_code ADD COLUMN IF NOT EXISTS hop_dest_ssn INTEGER;
