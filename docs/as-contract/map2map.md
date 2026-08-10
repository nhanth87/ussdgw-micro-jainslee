# MAP2MAP re-route — Case 2 (upper HLR USSD, no SRI)

**AS XML + JSON samples (MO + MAP2MAP pull/response):** [`map2map-as-xml.md`](map2map-as-xml.md) —
dual-wire AS HTTP contract. Enable JSON from **Routing** (`HTTP AS wire`) or Tenants.


Authoritative MO enrich path (routing-driven). **One unique short-code rule** covers both
exact short dials and long mark prefixes — no separate short/long rule types.

**Stay-on-call** during the long hop+AS path ≡ **AdaptiveTimeout + Virtual Session Bridge**
(budget armed **after hop USSD is on the wire** — CDR `GATE_ARMED`, not UE async-wait).
Not a separate app-user / profile column — ensure `ussd.bridge.enabled=true` (default) and
MAP2MAP Parent always `setAdaptiveBridgeArm(true)`.

**Per-MSISDN user profile** (`ussdUser` ProfileFacility table, PK = digits MSISDN) stores the
**last MAP2MAP TX snapshot** across sessions (shortCode, redirect, hop dest/SSN, hopOutcome,
gateMs, EWMA). Distinct from in-flight `ussdTx` (PK = correlationId). Temporary pull EWMA also
lives in `AdaptiveTimeout` per-MSISDN.

## Call flow (locked) — HLR text vs none → AS → UE

Operator law (do not regress CDR chips or AS wire):

| Hop from HLR/MSC | CDR | Admin chip | AS pull |
|------------------|-----|------------|---------|
| **USSD text** response | `MAP2MAP_HOP_CLOSE` (phase `S1_ACTIVE`) | **Amber** `cdr-status--gated` (same family as `GATE_ARMED`) — **never** red via `phase=FAILED` | `string=` = hop text, `hlrResult=responded` |
| **No** USSD text (empty CLOSE / timeout / abort / reject path) | **FAIL** family (`MAP2MAP_HOP_FAIL` / `HLR_REJECT` / `MAP2MAP_HOP_ABORT` / `MAP2MAP_TIMEOUT`, phase `FAILED`) | **Red** `cdr-status--fail` | `string=` **empty**, `hlrResult=none` (or `reject`); **never** put literal `hlr none` in `string=` |
| AS replies (CONTINUE/END) | — | — | GW **forwards AS text** to UE |
| AS silent after AdaptiveTimeout | `BRIDGED` / gate path | — | UE gets **Bridge page** text (`ussd.bridge.async-wait-message` / hard-fail) |

```
UE *804#  (or mark *101 → *101123456#)
  → MSC → GW          MAP processUnstructuredSS-Request
  → MapUssdParentSbb  rule.map2mapArmed() (reroute_enable + redirect USSD)
                      persist session (`adaptiveBridgeArm`); **do not** arm gate yet
  → Map2MapSbb         Case 2 hop (NO SRI / NO FAKE→MSC):
                        hop_dest_gt set → processUnstructuredSS-Request (op 59) → that GT/SSN
                        hop_dest blank → processUnstructuredSS-Request → ussd.hlr.upper-gt
                                          (+ hop_dest_ssn or default SSN 6)
                        Calling SSN 6; destRef + component = MSISDN; USSD = redirect (e.g. *875#)
                      **then** `startAwaitingAs` → CDR `GATE_ARMED` (budget countdown;
                        **not** UE async-wait). Covers remaining hop+AS wait.
  → peer upper HLR    processUnstructuredSS-Response / REJECT / abort / empty CLOSE
                      (legacy UnstructuredSS-Response still accepted)
  → Map2MapCompletion  sync AS pull via AsPullRouter (HTTP|gRPC|SIP per rule_type):
                        **hop text** → CDR `MAP2MAP_HOP_CLOSE` (amber) + ussdString=hop + `hlrResult=responded`;
                          re-arm AdaptiveTimeout for AS budget;
                        **hop REJECT** → CDR fail + ussdString=`hlr reject` + `hlrResult=reject`;
                          **no second GATE_ARMED** (hop already answered);
                        **hop empty/timeout/abort/CLOSE-no-text** → CDR fail + ussdString empty + `hlrResult=none`
                          (still emit `redirectUssd`/`hopUssd`; AS must not echo placeholder onto UE);
                        additive originatedUssd + shortCode + codeKind + redirectUssd + hopUssd;
                        if already S1_RELEASED → keep bridged (no CAS reset)
  → AS (HttpClientSbb / GrpcClientSbb / Sip MESSAGE) 200 / OK body
  → GW → MSC → UE     forward AS CONTINUE/END  (or Bridge wait/hard-fail if AS silent)
  (hop+AS still open after budget) BridgeGate → CDR `BRIDGED` + UE async-wait + gated XML
```

Case 1 HLR face **SRI** (`SriSbb` / NI push) is **unchanged** and separate from this path.

![HLR Face FAKE (admin)](map2map-hlr-face.jpeg)

![Call flow *804# → *8744# → AS](map2map-call-flow.jpeg)


## Per-rule model (N rules)

Re-route is **not** tied to a single Digicom code. Each `ussd_short_code` row may independently set
`reroute_enable`, redirect USSD (`map2map_gt`), optional `hop_dest_gt`/`hop_dest_ssn`, `hlr_mode`
(ignored for Case 2 hop; used by NI / HLR face), `mark`, and `as_url`. Operators add/edit many
rules on `/admin/routing` (e.g. `*804#` → `*875#`).

![Routing rules with RE_ROUTE / redirect `*875#`](routing-rules-reroute.png)

There is **no** Java/UI special-case for `*875#` or any carrier GT — those values appear only as
**examples** in docs, placeholders, and optional lab seeds.

## Service-provider lab parameters (example prove only)

| Param | Example value | Role |
|-------|---------------|------|
| Redirect USSD (`map2map_gt`) | e.g. `*875#` | Outbound processUnstructuredSS-Request **string** (any code) |
| Hop dest GT (`hop_dest_gt`) | e.g. `251971200201` | SCCP **CalledParty** GT when fixed peer (optional) |
| Hop dest SSN (`hop_dest_ssn`) | e.g. `6` | CalledParty SSN (default 6); alone with Re-route = SSN for upper-gt |
| MO dial (lab) | e.g. `*804#` or dedicated lab code | One example rule with `reroute_enable=true` |

When `hop_dest_gt` is set, hop → that GT/SSN. When **blank**, hop → HLR Face
`ussd.hlr.upper-gt` from `/admin/hlr` + SSN 6 (fail-closed if blank/self-loop).
**Never** SRI or FAKE→MSC on Case 2.

Admin form fields: `hopDestGt` / `hopDestSsn` (also accept `map2map_dest_gt` /
`map2map_dest_ssn` synonyms). Schema: Flyway **V11** (`hop_dest_gt`, `hop_dest_ssn`).

### Lab SQL (local / operator — do **not** silently mutate Digicom `*804#`)

Prefer a **dedicated lab rule** (or ask before touching live Digicom rows):

```sql
-- Lab/test rule example (adjust short_code / as_url for your env)
-- Blank hop_dest → upper-gt from HLR Face (Case 2 default)
INSERT INTO ussd_short_code
  (short_code, rule_type, as_url, enabled, tenant_id, network_id, mark, app_username,
   bypass, reroute_enable, map2map_gt, hlr_mode, hop_dest_gt, hop_dest_ssn)
VALUES
  ('*804#', 'HTTP', 'http://127.0.0.1:8090/ussd/pull', TRUE, NULL, 0, FALSE, '',
   FALSE, TRUE, '*875#', NULL, NULL, NULL)
ON CONFLICT DO NOTHING;

-- Or fixed peer GT (still no SRI):
-- hop_dest_gt = '251971200201', hop_dest_ssn = 6

-- Digicom operator (surgical, backup first) — ask before running on Digicom PG.
```

Full lab runbook: [`tools/map2map-lab/README.md`](../../tools/map2map-lab/README.md).

## Re-route flag (source of truth)

| `reroute_enable` | Behaviour |
|------------------|-----------|
| `false` (default) | Skip MAP2MAP — classic direct `asUrl` pull |
| `true` + non-blank redirect USSD (`map2map_gt`) | Arm Case 2 hop then AS |

`bypass` is kept as a DB mirror (`bypass = NOT reroute_enable`) for transition only — prefer `reroute_enable`.

## Routing fields (single rule model)

| Field | Example | Notes |
|-------|---------|-------|
| short_code | `*804#` or mark `*101` | Exact short when mark=false; mark prefix for long |
| mark | `true` / `false` | Prefix match; Ethiopia use `*101` not `*101*` |
| reroute_enable | `true` | Positive arm flag (Case 2) |
| map2map_gt | `*875#` short / `*875*` long | **Redirect** base on outbound Request (not SCCP GT). Long mark: prefix shape. |
| hop_dest_gt | `251971200201` | Optional fixed SCCP CalledParty GT |
| hop_dest_ssn | `6` | Optional; default **6**; alone with Re-route = SSN for upper-gt |
| hlr_mode | `INHERIT` / … | **Ignored** for Case 2 hop; NI / HLR face Case 1 only |
| as_url | HTTP AS | Step after hop; also gated XML push target |
| network_id | tenant / SCCP | |

**Matching law (unchanged):** exact (mark=false) wins on equality; else longest enabled mark
prefix. One mark rule can cover both short and long (e.g. `*101` matches `*101#` and
`*101123456#`). Prefer mark prefix over inventing a second rule type.

## Long-code suffix preserve (MAP2MAP hop USSD)

Hop USSD is **not** always the literal `map2map_gt`. Resolver:
`ShortCodeRule.resolveHopUssd` + chain fold `ShortCodeRoutingService.resolveMap2MapHopUssd`.

| Dial / rule | Hop USSD |
|-------------|----------|
| `mark=false` exact (e.g. `*804#` → redirect `*875#`) | Literal redirect `*875#` |
| `mark=true` short under prefix (`*804#` with key `*804`) | Literal redirect |
| `mark=true` long (`*804*1234#`, key `*804*`, redirect `*875*`) | **Prefix replace only** → `*875*1234#` (leftover incl. `#`) |

**Chain always:** after the first rewrite, if the hop string matches another armed RE_ROUTE
rule, apply the same law again (cap 8). Example:
`*804*1234#` → `*875*1234#` → mark `*875*` + redirect `*8775*` → `*8775*1234#`.

Ops for Digicom long form (`*{n}*xxx#`): set **`mark=true`**, short_code **`*804*`** (not
`*804#`), redirect **`*875*`** (prefix shape, not `*875#`). Ask before mutating live `*804#`.
If a second RE_ROUTE sits on `*875*` / `*875#`, short `*804#` may chain past `*875#` — disable
reroute on the intermediate or keep redirect prefixes intentional.

CDR / Slee OUT: `dialed=…` + `hopUssd=…` (and `redirect=` = configured `map2map_gt`).

## Hop dest matrix (Case 2)

| `hop_dest_gt` | Routing |
|---------------|---------|
| blank | `processUnstructuredSS-Request` (op **59**) → **`ussd.hlr.upper-gt`** + SSN (`hop_dest_ssn` or 6); destRef+component = MSISDN; Calling SSN **6**; **no SRI** |
| set (e.g. `251971200201`) | Same op **59** hop to that GT + `hop_dest_ssn` (default 6); USSD = **resolved** hop (suffix preserve + chain); **no SRI** |

Ethio live wire (2026-08-09): hop is **not** `unstructuredSS-Request` (op 60). Op 60 is the UE menu leg after hop/AS. Digicom prove must show hop Begin **op 59**, Calling SSN **6**, destReference MSISDN.

Fail-closed: unusable/blank/self-loop upper GT when hop dest blank (`MAP2MAP_UPPER_GT_FAIL`);
blank hop GT digits when hop_dest set (`MAP2MAP_HOP_DEST_FAIL`).

## AS pull enrich (additive)

XML dialog attrs / JSON fields (classic AS may ignore unknowns):

| Field | Meaning |
|-------|---------|
| `msisdn` | Subscriber |
| `ussdString` / `string=` | Hop RESULT text when `hlrResult=responded`; empty when `none`; `hlr reject` / `hlr pending` sentinels otherwise |
| `hlrResult` | `responded` \| `none` \| `reject` \| `pending` |
| `originatedUssd` | Full UE dialed string |
| `shortCode` | Matched rule key |
| `redirectUssd` | Rule redirect short code (e.g. `*875#`) |
| `hopUssd` | Resolved hop USSD sent to upper HLR (short or long) |
| `codeKind` | `SHORT` \| `LONG` |

## Case 1 HLR Face SRI (not MAP2MAP)

Inbound SRI-SM / NI PROXY_MAP outbound SRI remain on `SriSbb` + `/admin/hlr`.
MAP2MAP Case 2 does **not** increment `map2map.hopSri` / `map2map.hopFake`.

## Bridge / AdaptiveTimeout / stay-on-call

Re-route (`MapUssdParentSbb` map2map branch): persist `adaptiveBridgeArm=true` at ingress
**without** arming the gate; after hop UnstructuredSS is sent → `armGateAfterHopSent` /
`startAwaitingAs(…, "hop")` → CDR `GATE_ARMED` with deadline = configured
`ussd.bridge.async-gate-timeout-ms` ceiling (default **25s**), **not** EWMA×1.5.
Stay-on-call = that budget; UE async-wait only if gate **fires** (`BRIDGED`) after the full
ceiling elapses with hop/AS still silent. Observed EWMA is still recorded for CDR/admin.

| When gate fires | UE | AS |
|-----------------|----|----|
| During hop (before UnstructuredSS-Response) | `asyncWaitMessage` via `onGateExpired` (`AWAITING_AS → S1_RELEASED`) | `GatedAsNotifyService` / `encodeGatedPush` |
| During AS pull (after hop, still `AWAITING_AS`) | same | same |

Hop completion (`Map2MapCompletionService`):

- Still `AWAITING_AS` → re-arm for AS wait
- Already `S1_RELEASED` → do **not** `startAwaitingAs` again (CAS)

TTL: `max(ussd.map2map.pending-ttl-ms, dialogTimeoutMs)`; TTL after bridge does not
double `replyAndEnd` (`MAP2MAP_TIMEOUT_AFTER_BRIDGE`).

## MO hold while hop outstanding

While outbound hop UnstructuredSS is on the wire (`VirtualSession.map2mapHopOutstanding=true`
persisted on `ussdTx`), MO must **not** `replyAndEnd` until hop terminal (Response / Abort /
Reject / Timeout) clears the flag:

| Path | Behaviour while hop outstanding |
|------|----------------------------------|
| `UssdSagaCoordinator` AS empty/fail | CDR `MAP2MAP_MO_HOLD` — no MO end |
| `VirtualSessionBridge` hard gate (`!bridge`) | CDR `MAP2MAP_MO_HOLD` — no MO end |
| `VirtualSessionBridge` AS END/ABORT | CDR `MAP2MAP_MO_HOLD` — restore `AWAITING_AS` |
| Gate with bridge armed | **Allowed** — `BRIDGED` async-wait (stay-on-call) |

Operator note: gsm_map filter may hide TC-Abort; packet order that looks like
`returnResultLast` without hop Response is often MO hard-fail after hop Abort + AS empty —
still two TCAP dialogs; enforce hop-terminal before MO end.

## Per-MSISDN `ussdUser` profile (last MAP2MAP TX + multimenu snapshot)

| Field | Meaning |
|-------|---------|
| PK `msisdn` | Digits-only subscriber id |
| `lastCorrId` / `lastShortCode` / `lastRedirectUssd` | Last dialed rule + redirect |
| `lastHopDestGt` / `lastHopDestSsn` | Last hop CalledParty |
| `lastHopOutcome` | `pending` \| `text` \| `reject` \| `abort` \| `empty` \| … |
| `lastGateMs` / `lastEwmaMs` | Last AdaptiveTimeout budget + observed EWMA |
| `lastGeneration` / `lastDigit` / `lastMenuAsUssd` (≤50) / `lastAsAction` / `lastDialogId` | Last multimenu stamp |
| `map2mapTxCount` | Count of terminal hop outcomes (not `pending`) |
| `lastUpdatedAtMs` | Wall clock of last stamp |

**Writes:** hop-arm/complete (`recordMap2Map`); MS digit after dup-skip (`recordMenuState` pending);
AS→UE CONTINUE/END (`recordMenuState`). **MO:** seed AdaptiveTimeout from `lastEwmaMs` only —
**never** reuse `lastCorrId` as AS/BPLUS session. In-flight CAS stays on **`ussdTx`** (corr).
**Not** Digicom JDBC — ProfileFacility JVM-local until clustering.

## Telemetry / CDR

| Key / status | Meaning |
|--------------|---------|
| `map2map.armed` | Rule queued MAP2MAP hop (`MAP2MAP_ARMED`; gate not yet armed) |
| `map2map.hopStarted` | Live hop started (SS7 up) |
| `map2map.hopFixedGt` / `hopUpperGt` | Explicit hop_dest vs upper-gt fallback |
| `map2map.hopFake` / `hopSri` | Legacy (Case 2 does not increment) |
| `map2map.hopOk` / `asRouted` | Hop complete + AS pull routed |
| `map2map.gatedDuringHop` | Adaptive gate fired while pending MAP2MAP still open |
| `MAP2MAP_ARMED` | Case 2 hop queued (redirect + path); AdaptiveTimeout not armed yet |
| `MAP2MAP_HOP_START` | Case 2 hop started (`path=fixed` or `path=upper-gt`) |
| `MAP2MAP_USSD_SENT` | Outbound UnstructuredSS toward hop GT (gate armed just before/with this) |
| `MAP2MAP_GATED_HOP` | Gate fired during hop (also classic `BRIDGED`) |
| `MAP2MAP_HOP_CLOSE` | Hop returned **USSD text** → AS pull with that text (`hlrResult=responded`); admin chip **amber** (never red) |
| `MAP2MAP_OK` / `MAP2MAP_COMPLETE_AFTER_GATE` | Legacy/alias hop-done → AS (prefer `MAP2MAP_HOP_CLOSE` when hop text present) |
| `MAP2MAP_HOP_FAIL` | Hop **no** USSD text (empty CLOSE/RELEASE) — AS `hlrResult=none` + empty `string=`; chip **red** |
| `HLR_REJECT` / `MAP2MAP_HOP_ABORT` | Peer REJECT / abort — AS-pulls `hlr reject` / empty+`hlrResult=none` |
| `MAP2MAP_TIMEOUT` / `MAP2MAP_TIMEOUT_AFTER_BRIDGE` | Real hop TTL (`BridgeGateScheduler`) or MAP `onDialogTimeout` only |
| `GATE_ARMED` / `BRIDGED` / `GATE_EXPIRED` | Budget armed (not fired) / UE async-wait / NI park |
| `MAP2MAP_MO_HOLD` | MO end deferred — hop still outstanding |
| `GATED_AS_NOTIFY` / `GATED_AS_ACK` / `GATED_AS_FAIL` | Gated XmlMAPDialog POST to AS |
| **`END` / `CONTINUE` / `ABORT`** | **AS body applied toward UE** by `VirtualSessionBridge` (not hop-close). `END` = final AS→UE reply received and forwarded (`phase=COMPLETED`). Detail carries `asUssd=` (~50-char snippet) + `asLen=` + `note=AS→UE` |

Detail pipe fields: `sc|redirect|dialed|hopGt|hopSsn|path|asUssd|asLen|…`. Columns `gate_ms` / `observed_ewma_ms` / `as_ussd` via `CdrDbFlusher`. Admin filter: `/admin/cdr?status=MAP2MAP_*` or `GATED*`. Catalog: `CdrStatuses` + `Map2MapCdr` + `CdrUssdSnippet`.

**Timeline honesty:** `… → MAP2MAP_AS_ROUTED → END` means AS pull was routed, then AS replied and GW applied `action=END` to the UE — **not** “session ended before AS response”. Hop-close is `MAP2MAP_HOP_CLOSE` / `MAP2MAP_HOP_FAIL` only.

## Digicom note

`reroute_enable=true`, `map2map_gt='*875#'`, optional `hop_dest_*` or blank → upper-gt —
**ask before mutating** operator DB.
