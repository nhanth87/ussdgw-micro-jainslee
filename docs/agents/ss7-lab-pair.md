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

## Digicom carrier (Balance Plus)

Lab localhost stack stays in `build/ss7-lab.json` (127.0.0.1:8013↔8014). Carrier peering uses a **separate** file:

- Worktree seed: `build/ss7-digicom-balance.json`
- Digicom server: `configs/ss7-digicom-balance.json` + `ussd.map.config-file=configs/ss7-digicom-balance.json`
- Props: `ussd.map.ussd-gt=251971200490` (SCP/USSD GT; SSN **8** + HLR face SSN **6**)

| Digicom (IPSP **client**) | Value |
|---------------------------|--------|
| Local IP | **172.16.144.163** (eth1) — **not** old ussdgw **.167** |
| Local SPC / GT | **1470** / **251971200490** |
| Local SCTP ports | **2011**, **2019** (outbound client) |
| RC | **101** (classic Digicom; keep until provider says otherwise) |
| Peer L1 | 10.177.55.241:2501 SPC **1404** |
| Peer L2 | 10.177.54.241:2502 SPC **1403** |

Stop/disable `ussdgw-ss7sim` on Digicom before carrier apply (lab sim binds 8013/8014). Do **not** point local H2 lab `build/application.properties` at the Digicom carrier JSON.

**M3UA:** two separate AS (not one loadshare with two links): `AS-BP-1404`↔L1 / `AS-BP-1403`↔L2, both `ipsp: client` + RC **101**, routes dpc 1404|1403 opc 1470. SCTP `type: client` (we INIT to Balance Plus).

**Pcap note:** peer may advertise/ASPAC with another RC (e.g. 12) and return `Invalid_Routing_Context` for 101 until their ASP is keyed to `.163` — do not flip Digicom RC without provider confirm.
