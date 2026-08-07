# SIP AS trunking

AS-facing SIP trunks (not UE-facing USSI replacement).

## Roles

| Direction | Meaning |
|-----------|---------|
| Inbound MESSAGE → GW | AS **NI push** — SDP / `a=ussd-string:` (explicit) → MAP NI to UE; **or** AS **pull reply** (menu/text) when a parked session matches |
| Outbound MESSAGE → trunk | MO **pull** when routing `RuleType.SIP` and `asUrl=trunkId` |

Global listen remains `ussd.sip.*` / `/admin/sip` Apply. Trunk rows are peer + URI templates on the same RA.

## Config

- Table `ussd_sip_trunk` (admin `/admin/sip` trunks section)
- `ussd_tenant.sip_trunk_id` — preferred trunk for the tenant
- Routing: type `SIP`, `asUrl` = `trunkId`
- Enabled `peer_host` must be unique (upsert fail-closed)

## Outbound pull body

When `AsWireFacade` is available, outbound MESSAGE uses classic **XML** or greenfield **JSON** via `encodePullRequest`, keyed by the session/tenant `httpAsWireFormat` (default **XML**):

| Format | Content-Type |
|--------|----------------|
| XML | `application/vnd.3gpp.ussd+xml` |
| JSON | `application/json` |

If the wire facade is unavailable, fall back to `SipUssdBodyCodec.encodePullPlain` (`text/plain`).

Call-ID is `pull-{correlationId}`. After SendMessage, `AsPullRouter` arms the session `AWAITING_AS` (when present) so the adaptive gate sweeper can reclaim — same idea as HTTP/gRPC park.

Admin upsert of `request_uri_template` is SSRF-checked via `AsUrlValidator.rejectSipRequestUriTemplate` (sip/sips, no `{msisdn}` in host). Enabled `peer_host` must be unique across trunks.

## Inbound pull reply (best-effort V1)

Trunk-matched MESSAGE that is **not** dial-shaped is tried as an AS pull response **before** NI:

1. Call-ID `pull-{corr}` → parked session (`AWAITING_AS` / `S1_RELEASED` only)
2. Wire body (`AsWireFacade.decodePullResponse` XML/JSON) correlation / bridge ids
3. MSISDN + tenant → `findAwaitingAsByMsisdn`

On match: `VirtualSessionBridge.onAsResponse` — **no** `NiPushRequestEvent`. Explicit NI (SDP / `a=ussd-string:`) still yields to a matching parked pull session first; NI fires only when correlation misses. Correlation is best-effort (not full in-dialog SIP); INVITE/INFO remain out of scope.

## Classification (`SipUssdBodyCodec`)

1. SDP Content-Type / `a=ussd-string:` / inboundBody=SDP / NI header → NI_PUSH (**explicit**)
2. Body starts with `*` or `#` → MO_PULL
3. Other non-empty text → soft NI_PUSH (SBB prefers pull-reply correlate first)

NI fires only with a matched enabled trunk, and only when pull correlation misses (or for explicit NI with no parked pull).

## Link truth

`ss7.live` / SIP listen alone ≠ AS trunk up. Trunk match is by inbound From host == `peer_host`.
