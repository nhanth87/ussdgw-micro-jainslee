-- MAP2MAP re-route: positive flag + optional per-rule HLR mode.
-- reroute_enable=TRUE + map2map_gt (redirect USSD string) arms Map2MapSbb hop.
-- Default FALSE preserves classic direct-to-as_url. Migrate prior armed rows:
--   old bypass=FALSE + non-blank map2map_gt → reroute_enable=TRUE.
-- map2map_gt column kept (compat) = redirect USSD string on UnstructuredSS-Request
-- (e.g. *8744#), never SCCP CalledParty GT.
-- hlr_mode NULL/blank/INHERIT = HLR Face global; else FAKE | PROXY_MAP | …
ALTER TABLE ussd_short_code ADD COLUMN IF NOT EXISTS reroute_enable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ussd_short_code ADD COLUMN IF NOT EXISTS hlr_mode VARCHAR(32);

UPDATE ussd_short_code
   SET reroute_enable = TRUE
 WHERE bypass = FALSE
   AND map2map_gt IS NOT NULL
   AND TRIM(map2map_gt) <> '';

-- Keep bypass mirrored for transition readers (bypass = NOT reroute_enable).
UPDATE ussd_short_code SET bypass = NOT reroute_enable;
