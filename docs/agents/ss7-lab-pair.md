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

jSS7 plane → **HLR face** fields: mode, fake IMSI/MSC, upper GT, Diameter host/realm.
