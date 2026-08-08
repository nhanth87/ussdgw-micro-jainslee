# SS7 lab pair — ussdgw HLR face + MAP USSD

Peer pattern mirrors OTA [`ss7-lab-pair.md`](../../../../ota-service/ota-sim-push/docs/agents/ss7-lab-pair.md).

## Addressing

| Role | GT (example) | SSN |
|------|--------------|-----|
| GW USSD / SC | `ussd.map.ussd-gt` (e.g. `251900000100`) | **8** |
| GW gsmSCF (Digicom MO peer) | same GT as USSD when peer dials SSN 147 | **147** (`services` name `gsmscf`) |
| GW HLR face | same stack, listen SSN **6** | **6** (`ussd.map.hlr-ssn`) |
| Upper HLR (PROXY_MAP) | `ussd.hlr.upper-gt` | 6 |
| Peer SMSC / sim | PC 2 `SMS_TEST_SERVER` | 8 + 6 |

**Loop guard:** `ussd.hlr.upper-gt` must **not** equal `ussd.map.ussd-gt` (or fake MSC). PROXY_MAP fail-closes with abort if it would loop.

**Extra SSN / gsmSCF:** Lab stacks that only advertise SSN **8** (+ HLR **6**) will **UDTS Subsystem failure** when the peer’s Called SSN is **147**. Digicom Balance Plus MO does that. Declare `{name:gsmscf,ssn:147,protocol:map}` in `services`; `Ss7StackBuilder` calls TCAP `setExtraSsns` for every non-primary SSN. Boot must log `Registered SCCP listener with extra ssn 147`.

## HLR modes (`ussd.hlr.mode`)

| Mode | Behaviour |
|------|-----------|
| `PROXY_MAP` (default) | Forward inbound SRI-SM to upper GT; relay response. Fail-closed. |
| `FAKE` | Immediate `MapSendRoutingInfoForSmResponse` from `ussd.hlr.fake.imsi` + `fake.msc-gt`. |
| `PROXY_DIAMETER` | ULR/ULA stub via `DiameterLocationClient`; map to SRI-SM Response. Fail-closed. |
| `FAKE_THEN_RESOLVE` | Fake answer first, then async MAP (or Diameter) enrich. |

Per-network override: `ussd.hlr.network.<networkId>.mode`.

## Test matrix

### Always (`mvn test`)

- `HlrFaceServiceTest` — all four modes + loop guard
- `MapSriUssdBasicStubTest` — push SRI→USSD NI commands; pull MO reply; HLR response command shape

### Lab (`-Dlab-ss7=true`)

```bash
mvn test -Dlab-ss7=true -Dgroups=lab-ss7
```

| Flow | Assert |
|------|--------|
| Push | SRI-SM out → resp → UnstructuredSS-Request |
| Pull | ProcessUnstructuredSS-Request in → AS pull → MAP reply |
| Inbound SRI | Peer SRI-SM to SSN 6 → HLR face answer per mode |

## Admin

Dedicated **`/admin/hlr`** (not SS7 JSON): mode, fake IMSI/MSC, upper GT, Diameter host/realm.
Outbound SRI-SM (NI `SriSbb` + PROXY_MAP face) CalledParty = resolved `ussd.hlr.upper-gt`
(admin overlay when non-blank, else `application.properties`). Empty admin field → props default.

## Digicom carrier (Balance Plus) — prod-bound live host

**Digicom is future production**, not a disposable test lab: real Balance Plus SS7 peer, **PostgreSQL** DB **`ussdgw`**, and live configs that must survive deploys.

**Source of truth = Digicom host** (`digicom-nb`, APP_HOME `/home/app/ota-push-services/ussdgw-micro-jainslee/`).  
Worktree seed `build/ss7-digicom-balance.json` must match server `configs/ss7-digicom-balance.json`.  
**Never** overwrite Digicom `configs/` on rsync (`application.properties`, `ss7-digicom-balance.json`, `ss7-persist/`) — ship **jars/`lib`/`quarkus`/`app/html` only** (+ seed JSON only when intentionally updating SS7). Digicom package = build-time **`db-kind=postgresql`**, then restore local **H2** for the dev tree.

Localhost stack stays in `build/ss7-lab.json` (127.0.0.1:8013↔8014). **Do not** point local H2 lab props at the Digicom carrier JSON. Stop/disable `ussdgw-ss7sim` on Digicom (lab sim binds 8013/8014).

### Roles (RFC / SP table — no compromise)

| Layer | Digicom (this GW) | Balance Plus peer |
|-------|-------------------|-------------------|
| SCTP (RFC 4960) | **server** — listen; peer sends INIT | **client** — dials in |
| M3UA IPSP (RFC 4666) | **ipsp: server** + **exchangeType: DE** | **ipsp: client** |
| SPC / GT | **1470** / **251971200490** | L1 **1404**, L2 **1403** |
| Routing Context | **12** (SP-confirmed; classic `.167` used **101** → err 25) | ASPAC with RC **12** |

Local IP **172.16.144.163** (eth1) — **not** old ussdgw **.167**.

### Live `application.properties` (map / SS7 / HLR)

```properties
ussd.map.enabled=true
ussd.map.auto-apply-on-boot=true
ussd.map.config-file=configs/ss7-digicom-balance.json
ussd.map.opc=1470
ussd.map.ussd-gt=251971200490
ussd.hlr.upper-gt=251900000006
ussd.ss7.persist-dir=configs/ss7-persist
```

### Live `configs/ss7-digicom-balance.json` (captured from Digicom)

```json
{
  "stackName": "ra-jss7",
  "protocols": { "map": true, "cap": false },
  "sctp": {
    "connectDelay": 10000,
    "workerThreads": 8,
    "links": [
      {
        "name": "L1-BP-1404",
        "type": "server",
        "channel": "sctp",
        "local": "172.16.144.163:2011",
        "peer": "10.177.55.241:2501",
        "localSecondary": []
      },
      {
        "name": "L2-BP-1403",
        "type": "server",
        "channel": "sctp",
        "local": "172.16.144.163:2019",
        "peer": "10.177.54.241:2502",
        "localSecondary": []
      }
    ]
  },
  "m3ua": {
    "as": [
      {
        "name": "AS-BP-1404",
        "mode": "loadshare",
        "functionality": "ipsp",
        "ipsp": "server",
        "exchangeType": "DE",
        "routingContext": 12,
        "links": ["L1-BP-1404"]
      },
      {
        "name": "AS-BP-1403",
        "mode": "loadshare",
        "functionality": "ipsp",
        "ipsp": "server",
        "exchangeType": "DE",
        "routingContext": 12,
        "links": ["L2-BP-1403"]
      }
    ],
    "routes": [
      { "to": { "dpc": 1404, "opc": 1470 }, "via": "AS-BP-1404" },
      { "to": { "dpc": 1403, "opc": 1470 }, "via": "AS-BP-1403" }
    ]
  },
  "sccp": {
    "localPoints": [
      {
        "pc": 1470,
        "networkIndicator": "national",
        "networkId": 0,
        "reachablePointCodes": [1404, 1403]
      }
    ],
    "routing": [
      { "from": "remote", "match": { "gt": "*" }, "to": { "pc": 1470 }, "mask": "K" },
      { "from": "local", "match": { "gt": "*" }, "to": { "pc": 1404 }, "mask": "K" }
    ]
  },
  "tcap": {
    "dialogIdleTimeout": 60000,
    "invokeTimeout": 30000,
    "maxDialogs": 50000
  },
  "services": [
    { "name": "primary", "ssn": 8, "protocol": "map" },
    { "name": "gsmscf", "ssn": 147, "protocol": "map" },
    { "name": "hlr", "ssn": 6, "protocol": "map" }
  ]
}
```

**M3UA:** two separate AS; both `ipsp: server`, **`exchangeType: DE`** (dual exchange — Digicom may send ASPUP after SCTP UP; default SE waits forever if peer only HEARTBEATs), RC **12** via single field `routingContext`.

**Services / SSN (MO pull):** `primary` **8**, **`gsmscf` 147**, `hlr` **6**. Without **147**, Balance Plus MO to Digicom GT gets SCCP UDTS (“no local SSN is present”) and never reaches `MapUssdParentSbb`. After Apply/restart, grep logs for `Registered SCCP listener with extra ssn 147`.

### Live status (2026-08-07) — RC12 ACTIVE

Digicom is **LIVE** (`ss7.live=true`): SCTP listen **2011/2019**, M3UA ASP/AS **ACTIVE** after Balance Plus ASPAC RC **12** + Digicom ASPAC_ACK. No ERR **25** after the successful restart.

Path that worked (agent `4607235a` on Digicom — already applied; do **not** re-bounce unless config drifts):

1. Seed JSON = single `"routingContext": 12` (not `routingContexts` list), `ipsp: server`, `exchangeType: DE`, SCTP `type: server`.
2. Quarantine corrupt / stale `configs/ss7-persist/*` and replace with clean persist (or wipe + re-apply).
3. Restart `ussdgw` so ra-jss7 loads the RC12 seed.
4. Peer ASPAC RC12 → ASPAC_ACK → `ss7.live` LIVE.

Proof pcap: [`build/pcap/m3ua-aspac-rc12-20260807-135839.pcap`](../../build/pcap/m3ua-aspac-rc12-20260807-135839.pcap).

**RC 12 vs dual RC (jar footgun — keep):**

| Intent | Digicom `ss7-config-9.2.8` (shipped) | Notes |
|--------|--------------------------------------|--------|
| **Prod / LIVE** | `"routingContext": 12` | Peer ASPAC RC **12** → ASP/AS ACTIVE · `ss7.live=true` |
| Dual ASPAC test `[12, 101]` | **Not supported** on Digicom jar | Coral-valley has `routingContexts` list, but **deployed** `Ss7Config$As` only has single `routingContext`. JSON `routingContexts` is **ignored** → null RC → **RC 0** → peer `Invalid_Routing_Context` (25). Do **not** use dual list until ss7-config jar is upgraded. |
| Classic `.167` | RC **101** alone | Rejected by Balance Plus (err 25) when Digicom advertised 101 |

**Pcap / debug lessons:** RC **101** → peer err 25. SE-only after Digicom restart → SCTP UP + ASP DOWN (peer HEARTBEAT only) until DE. Success wire = ASPAC RC12 + ASPAC_ACK (pcap above). Admin `ss7.detail` for file apply must show real SCTP listen + `rc=12` — never props-fallback `8013→8014` (`Ss7ApplyService.formatWiredDetail`).

**SRI dual-homing (2026-08-08):** Outbound SRI may leave **L1/2011 → PC 1404** while `returnResultLast` returns on **L2/2019 ← PC 1403** (pcap `build/pcap/ussd-sri-hlr-20260808-020509.pcap`) — or the reverse under loadshare. Prefer **one AS** with both links (`AS-BP` + routes 1404/1403). m3ua must deliver PayloadData when **AS ACTIVE** even if the receive-side ASP FSM is DOWN. After SRI, NI USSD SCCP dest = **MSC `networkNodeNumber`**, not MSISDN.

**NI push prove (2026-08-08 retest):** `POST /ussd` notify MSISDN **251911230398** with `ss7.live=true` → log `USSD NI sent … mscGt=251971200146 imsi=…` + peer `unstructuredSSNotify_Response`. Wire proof: [`build/pcap/ussd-ni-push-msc-20260808-023952.pcap`](../../build/pcap/ussd-ni-push-msc-20260808-023952.pcap) (CalledParty **251971200146**). Handset screen = operator/UE confirm (server cannot see it).

**NI push pcap (2026-08-08 02:51 UTC fresh):** [`build/pcap/ussd-ni-push-msc-20260808-025117.pcap`](../../build/pcap/ussd-ni-push-msc-20260808-025117.pcap) — SCTP **2011/2019**; SRI → HLR GT **251900000006**; result MSC **251971200146** / IMSI **636010024533522**; `unstructuredSS-Notify` CalledParty = MSC (not MSISDN); peer Notify response.

### Ethiopia MO pull (`*101xxxxxx`) — prep (2026-08-08)

SP will dial **pull** short codes like `*101123456#` (user-request / MO). Wire is the opposite of NI push:

```text
UE ──MAP processUnstructuredSS-Request──► GW (MapUssdParentSbb)
  → extractShortCode → ShortCodeRoutingService.find (exact, else longest mark prefix)
  → VirtualSession + startAwaitingAs (AdaptiveTimeout / bridge on top)
  → AsPullRouter → HTTP POST AS raw XML (gen0 = processUnstructuredSSRequest_Request)
  ← AS <dialog> CONTINUE/END (unstructuredSSRequest_Request or process…_Response)
  → claimForAsResponse → MAP unstructuredSS-Request / processUnstructured reply → UE
  (continue: UE unstructuredSS-Response → encodeContinue → same AS corr)
```

| Check | Digicom state |
|-------|----------------|
| `ss7.live` | must be **true** (peer carries MO in) |
| Local SSN | **8 + 147 + 6** in seed (`gsmscf` **147**) — peer Called SSN is often **147**, not 8 |
| Routing | **`*101` mark=true** → `http://127.0.0.1:8090/ussd/pull` (seeded live; **not** `*101*` — that misses `*101123456#`) |
| AS sim | `tools/as-node` on **:8090** (`npm run pull:fast`) — real XmlMAPDialog; **not** webhook.cool alone |
| Exact lab codes | `*100#` / `*123#` still exact → same AS |

**Mark footgun:** `extractShortCode("*101123456#")` → `*101123456#`. Prefix match is `startsWith`. Use mark short_code **`*101`**. Do **not** seed `*101*` unless the MMI has a second asterisk.

**AS / AdaptiveTimeout footgun:** HTTP 200 with **empty body** → `AS_EMPTY_BODY` (saga compensate). Handset “Please wait…” is easy to misread as the AdaptiveTimeout gate — it is not. Dump endpoints are not an AS; optional as-node `MIRROR_URL` can copy XML to a dump while as-node still replies.

**Prove checklist (live peer or handset):**

1. `ss7.live=true` + boot log `Registered SCCP listener with extra ssn 147` + as-node up on 8090.
2. Handset / Balance Plus MO: dial `*101xxxxxx#` toward GW GT **251971200490** (Called SSN often **147**).
3. Logs: `onProcessUnstructured` route (not UDTS / `no-route sc=…` / `dup-skip`) → HTTP pull → AS menu XML → MAP reply out. One dialog → one AS POST (dialogId dedup).
4. Pcap: inbound `processUnstructuredSS-Request` string=`*101…#`; outbound Request/Response on same dialog. If AS is HTTPS, filter TLS SNI — not cleartext `http`.
5. AS continue digits `1–4` / `0` exercise same-dialog bridge (as-node interactive menus).

Lab AS XML smoke (no MAP): `POST http://127.0.0.1:8090/ussd/pull` with classic `processUnstructuredSSRequest_Request` body — expect `unstructuredSSRequest_Request` menu.
