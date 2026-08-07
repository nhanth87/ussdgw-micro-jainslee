# SS7 lab pair — ussdgw HLR face + MAP USSD

Peer pattern mirrors OTA [`ss7-lab-pair.md`](../../../../ota-service/ota-sim-push/docs/agents/ss7-lab-pair.md).

## Addressing

| Role | GT (example) | SSN |
|------|--------------|-----|
| GW USSD / SC | `ussd.map.ussd-gt` (e.g. `251900000100`) | **8** |
| GW HLR face | same stack, listen SSN **6** | **6** (`ussd.map.hlr-ssn`) |
| Upper HLR (PROXY_MAP) | `ussd.hlr.upper-gt` | 6 |
| Peer SMSC / sim | PC 2 `SMS_TEST_SERVER` | 8 + 6 |

**Loop guard:** `ussd.hlr.upper-gt` must **not** equal `ussd.map.ussd-gt` (or fake MSC). PROXY_MAP fail-closes with abort if it would loop.

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

## Digicom carrier (Balance Plus) — live server snapshot

**Source of truth = Digicom host** (`digicom-nb`, APP_HOME `/home/app/ota-push-services/ussdgw-micro-jainslee/`).  
Worktree seed `build/ss7-digicom-balance.json` must match server `configs/ss7-digicom-balance.json`.  
Never overwrite live `configs/application.properties` on rsync — only jars/`lib`/`quarkus`/`app/html` (+ seed JSON when intentional).

Lab localhost stack stays in `build/ss7-lab.json` (127.0.0.1:8013↔8014). **Do not** point local H2 lab props at the Digicom carrier JSON. Stop/disable `ussdgw-ss7sim` on Digicom (lab sim binds 8013/8014).

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
    { "name": "hlr", "ssn": 6, "protocol": "map" }
  ]
}
```

**M3UA:** two separate AS; both `ipsp: server`, **`exchangeType: DE`** (dual exchange — Digicom may send ASPUP after SCTP UP; default SE waits forever if peer only HEARTBEATs), RC **12** via single field `routingContext`.

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
