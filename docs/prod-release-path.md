# ussdgw-prod-release — pre-production checklist

Point prod packaging at:

- `worktrees/ussd-service/ussdgw-jainslee/dist` (this tree), or
- `worktrees/ussd-service/RestLink/Ussdgw` after `USSD_MIRROR_LEGACY=1 ./build/package-dist.sh`

Do **not** consume classic WildFly DU artifacts from `ussdgateway/release-wildfly`.

## Before first prod boot

1. **Secrets (fail-closed)** — rotate or the node refuses to start:
   - `ussd.admin.session-hmac-secret` / `USSD_ADMIN_SESSION_HMAC_SECRET` (forging this forges ADMIN cookies)
   - `ussd.admin.api-key` / `USSD_ADMIN_API_KEY` (header `X-USSD-Admin-Key` only — never `?key=`)
   - `ussd.admin.first-run-password` — leave blank to mint a random password (console only), or set a strong value
   - Delete `ussd.lab.allow-default-secrets=true` (or set `false`)
2. **DB password** — `QUARKUS_DATASOURCE_PASSWORD` in the unit / `run.sh` export. Never commit into `dist/configs/application.properties`.
3. **SMPP binds** — `SMPP_PASSWORD` / `SMPP_SERVER_PASSWORD` (packaged defaults are lab stubs).
4. **TLS** — terminate TLS at nginx `:443` → HTTP RA `:8088`. Then remove `ussd.admin.cookie-secure=false` so the session cookie carries `Secure`.
5. **CSRF** — leave `ussd.admin.csrf.enabled=true`. Shell sends `X-USSD-CSRF` from the `ussd_admin_csrf` cookie.
6. **AS URL SSRF** — set `ussd.as.url.host-allowlist` to the real AS hosts. Keep `ussd.as.url.allow-private-hosts=false` off-lab.
7. **package-dist on the server** — never overwrites an existing `dist/configs/application.properties`; it writes `application.properties.new`. Diff before adopting. `db-kind` is **build-time**.
8. **Log retention** — Log4j2 size+age rollover (`ussd.log.max-size` / `max-files` / `max-age`). SLEE/CDR are async. See [docs/agents/logging.md](agents/logging.md).
9. **Diameter / SIP** — shipped `ussd.diameter.enabled=false` / `ussd.sip.enabled=false` ⇒ effectively STUB_QUEUED until enabled and a peer is up.
10. **Load-test gate** — prove MAP MO + AS pull at target TPS on the packaged `dist/` (not the worktree), with `ss7.live` / peer truth from `LinkStatusService`.

## Cutover soak

See [cutover.md](cutover.md) for acceptance, rollback, and soak exit criteria.
