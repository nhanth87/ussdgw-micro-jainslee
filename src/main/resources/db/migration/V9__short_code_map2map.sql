-- MAP2MAP hop + bypass on short-code rules.
-- bypass=TRUE (default): skip MAP→MAP; MO pull goes straight to as_url (HTTP/gRPC/SIP).
-- bypass=FALSE + map2map_gt set: Map2MapSbb sends MAP UnstructuredSS-Request to that GT
-- (e.g. *8744# / 8744), then continues to as_url with the hop response.
ALTER TABLE ussd_short_code ADD COLUMN IF NOT EXISTS bypass BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE ussd_short_code ADD COLUMN IF NOT EXISTS map2map_gt VARCHAR(64);
