-- Classic USSD pull seed for local lab (H2 / lab PG only).
-- Points short-codes at tools/as-node pull (:8090/ussd/pull).
-- Do NOT run casually on Digicom without operator approval.

-- Exact short codes (classic ussdgateway-style MO pull)
INSERT INTO ussd_short_code
  (short_code, rule_type, as_url, enabled, tenant_id, network_id, mark, app_username,
   bypass, reroute_enable)
SELECT '*100#', 'HTTP', 'http://127.0.0.1:8090/ussd/pull', TRUE, NULL, 0, FALSE, '',
       TRUE, FALSE
 WHERE NOT EXISTS (
   SELECT 1 FROM ussd_short_code WHERE short_code = '*100#'
     AND (app_username IS NULL OR app_username = '')
 );

INSERT INTO ussd_short_code
  (short_code, rule_type, as_url, enabled, tenant_id, network_id, mark, app_username,
   bypass, reroute_enable)
SELECT '*123#', 'HTTP', 'http://127.0.0.1:8090/ussd/pull', TRUE, NULL, 0, FALSE, '',
       TRUE, FALSE
 WHERE NOT EXISTS (
   SELECT 1 FROM ussd_short_code WHERE short_code = '*123#'
     AND (app_username IS NULL OR app_username = '')
 );

-- Ethiopia-style mark prefix (*101123456# → *101)
INSERT INTO ussd_short_code
  (short_code, rule_type, as_url, enabled, tenant_id, network_id, mark, app_username,
   bypass, reroute_enable)
SELECT '*101', 'HTTP', 'http://127.0.0.1:8090/ussd/pull', TRUE, NULL, 0, TRUE, '',
       TRUE, FALSE
 WHERE NOT EXISTS (
   SELECT 1 FROM ussd_short_code WHERE short_code = '*101'
     AND (app_username IS NULL OR app_username = '')
 );

-- Brook-like *804# exact short-code → as-node (lab load / AdaptiveTimeout prove)
INSERT INTO ussd_short_code
  (short_code, rule_type, as_url, enabled, tenant_id, network_id, mark, app_username,
   bypass, reroute_enable)
SELECT '*804#', 'HTTP', 'http://127.0.0.1:8090/ussd/pull', TRUE, NULL, 0, FALSE, '',
       TRUE, FALSE
 WHERE NOT EXISTS (
   SELECT 1 FROM ussd_short_code WHERE short_code = '*804#'
     AND (app_username IS NULL OR app_username = '')
 );

-- Re-point existing lab rules at as-node (local only — empty/webhook → pull)
UPDATE ussd_short_code
   SET as_url = 'http://127.0.0.1:8090/ussd/pull',
       enabled = TRUE,
       rule_type = COALESCE(NULLIF(TRIM(rule_type), ''), 'HTTP')
 WHERE short_code IN ('*100#', '*123#', '*101', '*804#')
   AND (app_username IS NULL OR app_username = '')
   AND (as_url IS NULL OR TRIM(as_url) = ''
        OR as_url LIKE '%webhook.cool%'
        OR as_url LIKE '%127.0.0.1:8090%');
