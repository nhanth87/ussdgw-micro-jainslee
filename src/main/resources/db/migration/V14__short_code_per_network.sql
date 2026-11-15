-- Routing per tenant link: the same short code may now have one rule per SS7 network.
-- Company A (link network 1) and company B (link network 2) can both own *123# and point it at
-- their own AS. network_id = 0 stays the shared rule used by any link without its own rule.
-- Old key (short_code, app_username) → new key (short_code, network_id, app_username).
-- Every existing row keeps its network_id, so the new key is satisfied by the existing data.
ALTER TABLE ussd_short_code DROP CONSTRAINT IF EXISTS uk_ussd_short_code_app;
ALTER TABLE ussd_short_code
  ADD CONSTRAINT uk_ussd_short_code_net_app UNIQUE (short_code, network_id, app_username);
