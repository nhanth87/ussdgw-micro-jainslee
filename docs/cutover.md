# Cutover — replace classic WildFly USSD GW

## Track
1. Lab parity on same jSS7 sim (MAP MO, HTTP/gRPC AS, bridge S1/S2, CDR).
2. Point `ussdgw-prod-release` packaging at `RestLink/Ussdgw` (`ussdgw-jainslee` dist) instead of WildFly DU.
3. Operator short-code + SS7 configs migrated to `configs/` + admin Apply.
4. **Diameter + SIP/USSI live** (micro-jainslee RAs) — in scope with MAP; not deferred. Shipped defaults keep both `enabled=false` until peers exist.
5. Admin UX = OTA shell adapted for USSD pages (not Jolokia).
6. Decommission WildFly 10 node after soak.

## Coexistence
Run classic and ussdgw-jainslee on different PC/SSN or lab GT during soak. Do not dual-bind same M3UA AS.

## Acceptance criteria (lab → soak)
- MAP MO pull reaches AS (XML default and/or JSON per tenant) and returns a dialog body to the handset.
- NI push (admin Lab MO / campaign) delivers when `ss7.live=true` (SCTP up **and** M3UA AS ACTIVE).
- Admin login works with rotated secrets (or documented lab opt-out); `?key=` is rejected; header/session auth works.
- CDR rows land in DB; SLEE/CDR logs rotate under `dist/logs/`.
- Routing / tenants survive a `./build/package-dist.sh` on the server (configs not clobbered).

## Soak exit criteria
- Target TPS held for agreed window without MAP dialog / timer leaks.
- No silent FAKE HLR while mode is `PROXY_*`.
- Diameter/SIP either live with peer or explicitly left disabled (STUB_QUEUED) with ops sign-off.

## Rollback
1. Stop ussdgw-jainslee (`dist/run.sh` / systemd).
2. Re-point signalling (PC/SSN/GT or M3UA) at the classic WildFly node.
3. Keep `dist/configs/application.properties` + `data/` / PostgreSQL — do not delete; they are the recovery source for a re-cutover.
4. Classic DU remains the behavior oracle until soak exit is signed.

## Prod packaging note
`ussdgw-prod-release` should consume `worktrees/ussd-service/ussdgw-jainslee/dist` (or mirrored `RestLink/Ussdgw`) rather than `release-wildfly` artifacts. Checklist: [prod-release-path.md](prod-release-path.md).
