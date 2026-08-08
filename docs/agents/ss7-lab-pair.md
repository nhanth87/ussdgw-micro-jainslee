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

## Operator Digicom / live carrier (dual push — Digicom kept, not on nhanth87)

**Public `nhanth87` `main` = lab/test SS7 only** (`build/ss7-lab.json` / `dist/configs/ss7-lab.json`).

**Keep** Digicom carrier seeds (`ss7-digicom-balance.json`, `application-digicom.properties`, `install-on-digicom.sh`) for Digicom deploys. Push with **`./build/push-dual.sh`**:

| Push | Remote / branch | Contents |
|------|-----------------|----------|
| 1 | `origin` / `main` (nhanth87 PUBLIC) | Lab only — Digicom paths gitignored / not tracked |
| 2 | `digicom-et` / `main` (PRIVATE) | Branch `digicom` = `main` + Digicom overlay (force-added) |

Live Digicom host `configs/` remains operator SoT for secrets/persist. Legacy `private/digicom-carrier-seeds` is a backup tip; prefer **digicom-et `main`**.

When deploying to Digicom:

- Rsync **jars / `lib/` / `quarkus/` / `app/html`** only — **never** overwrite Digicom `configs/` (PG URL, secrets, SS7 seed/persist).
- Package with build-time **`db-kind=postgresql`**, then restore local **H2** for the public/dev tree.
- Ask before any Digicom DB mutation (routing / tenants / users / `network_id`).
- Live peer lessons (IPSP server, RC uniqueness, dual-homed SRI, gsmSCF SSN **147**, `networkId=0`) stay in [`lessons.md`](lessons.md) without embedding carrier topology on **nhanth87**.

Localhost stack: `build/ss7-lab.json` (127.0.0.1:8013↔8014). **Do not** point public lab props at a Digicom carrier JSON.

**Dedicated pull-lab pair** (isolated from `:8013`): `build/ss7-lab-sim-pull.json` (**8023** server) + jSS7 sim XML `tools/ss7-simulator/data/ussdgw_lab_pull_client.xml` (**8024** client). Services SSN **8+147+6**. Helper: [`tools/ss7-simulator/pull-lab.sh`](../../tools/ss7-simulator/pull-lab.sh) (`apply-ss7` → as-node → `reseed-pull` → `sim` → `dial-pull '*100#'`). Re-seeds classic MO pull `*100#` / `*123#` / mark `*101` → `http://127.0.0.1:8090/ussd/pull`.

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

| Check | Lab / live peer state |
|-------|----------------|
| `ss7.live` | must be **true** (peer carries MO in) |
| Local SSN | **8 + 147 + 6** in seed (`gsmscf` **147**) — peer Called SSN is often **147**, not 8 |
| Routing | **`*101` mark=true** → `http://127.0.0.1:8090/ussd/pull` (seeded live; **not** `*101*` — that misses `*101123456#`) |
| AS sim | `tools/as-node` on **:8090** (`npm run pull:fast`) — real XmlMAPDialog; **not** webhook.cool alone |
| Exact lab codes | `*100#` / `*123#` still exact → same AS |

**Mark footgun:** `extractShortCode("*101123456#")` → `*101123456#`. Prefix match is `startsWith`. Use mark short_code **`*101`**. Do **not** seed `*101*` unless the MMI has a second asterisk.

**AS / AdaptiveTimeout footgun:** HTTP 200 with **empty body** → `AS_EMPTY_BODY` (saga compensate). Handset “Please wait…” is easy to misread as the AdaptiveTimeout gate — it is not. Dump endpoints are not an AS; optional as-node `MIRROR_URL` can copy XML to a dump while as-node still replies.

**Prove checklist (operator live peer or handset — Digicom host only):**

1. `ss7.live=true` + boot log `Registered SCCP listener with extra ssn 147` + as-node up on 8090.
2. Handset / Balance Plus MO: dial `*101xxxxxx#` toward the operator GW GT (Called SSN often **147** on some peers).
3. Logs: `onProcessUnstructured` route (not UDTS / `no-route sc=…` / `dup-skip`) → HTTP pull → AS menu XML → MAP reply out. One dialog → one AS POST (dialogId dedup).
4. Pcap: inbound `processUnstructuredSS-Request` string=`*101…#`; outbound Request/Response on same dialog. If AS is HTTPS, filter TLS SNI — not cleartext `http`.
5. AS continue digits `1–4` / `0` exercise same-dialog bridge (as-node interactive menus).

Lab AS XML smoke (no MAP): `POST http://127.0.0.1:8090/ussd/pull` with classic `processUnstructuredSSRequest_Request` body — expect `unstructuredSSRequest_Request` menu.
