-- MAP2MAP SP lab seed (local H2 / lab PG only) — EXAMPLE VALUES ONLY.
-- Redirect USSD / hop GT/SSN below are examples for one prove rule; product supports
-- N independent rules with arbitrary short/long/mark codes and redirects.
-- Do NOT run casually on Digicom without operator approval / backup.
-- Prefer INSERT of a dedicated lab short_code if *804# is live SoT.
-- Option A: upsert lab rule for *804# (local only)
UPDATE ussd_short_code
   SET reroute_enable = TRUE,
       bypass = FALSE,
       map2map_gt = '*875#',
       hop_dest_gt = '251971200201',
       hop_dest_ssn = 6,
       as_url = COALESCE(NULLIF(TRIM(as_url), ''), 'http://127.0.0.1:8090/ussd/pull')
 WHERE short_code = '*804#'
   AND (app_username IS NULL OR app_username = '');

-- Option B: dedicated lab dial *8804# (safer — leaves *804# alone)
INSERT INTO ussd_short_code
  (short_code, rule_type, as_url, enabled, tenant_id, network_id, mark, app_username,
   bypass, reroute_enable, map2map_gt, hlr_mode, hop_dest_gt, hop_dest_ssn)
SELECT '*8804#', 'HTTP', 'http://127.0.0.1:8090/ussd/pull', TRUE, NULL, 0, FALSE, '',
       FALSE, TRUE, '*875#', NULL, '251971200201', 6
 WHERE NOT EXISTS (
   SELECT 1 FROM ussd_short_code WHERE short_code = '*8804#'
     AND (app_username IS NULL OR app_username = '')
 );
