-- Mark key: when true, any USSD string starting with short_code routes to this AS URL
-- (classic ScRoutingRule exactMatch=false / startsWith). Default false = exact match only.
ALTER TABLE ussd_short_code ADD COLUMN IF NOT EXISTS mark BOOLEAN NOT NULL DEFAULT FALSE;
