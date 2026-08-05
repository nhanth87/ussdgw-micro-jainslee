# Cutover — replace classic WildFly USSD GW

## Track
1. Lab parity on same jSS7 sim (MAP MO, HTTP/gRPC AS, bridge S1/S2, CDR).
2. Point `ussdgw-prod-release` packaging at `RestLink/Ussdgw` (`ussdgw-jainslee` dist) instead of WildFly DU.
3. Operator short-code + SS7 configs migrated to `configs/` + admin Apply.
4. SIP only if still required — separate phase.
5. Decommission WildFly 10 node after soak.

## Coexistence
Run classic and ussdgw-jainslee on different PC/SSN or lab GT during soak. Do not dual-bind same M3UA AS.

## Prod packaging note
`ussdgw-prod-release` should gain a profile/path that consumes `worktrees/ussd-service/ussdgw-jainslee/dist` (or mirrored `RestLink/Ussdgw`) rather than `release-wildfly` artifacts.
