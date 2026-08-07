# RestLink USSD GW — Node Application Server sim

Fastify lab AS for **PULL**, **ASYNC_ACK → `/as/callback`**, and **classic NI** (`/ussd`).
Wire default matches the gateway: classic **`<dialog>` XML** (`ClassicDialogXmlCodec`); JSON `AsRequest` / `AsResponse` optional.

Requires **Node 20+**.

## Interactive menus (PULL + PUSH)

Default `ACTION=CONTINUE` runs a **3–4 menu catalog** with multi-turn digits `1–4` / `0`:

| id | First screen |
|----|----------------|
| `main` | Welcome Digicom lab — Balance / Bundle / Help / Exit |
| `lang` | Digicom language lab — EN / AM / OM / Exit |
| `promo` | Digicom promo lab — Daily / Weekly / Monthly / Exit |
| `help` | Digicom support lab — FAQ / Agent / Status / End |

**Menu pick** (`MENU_PICK`, default **`hash`**): stable `hash(msisdn) % 4` so the same
subscriber always gets the same menu. Alternatives: `random`, `rotate`, or force
`main` / `lang` / `promo` / `help`.

Leaf replies are labeled `[menu/digit] …` for log grepping. Session key = `correlationId`
(XML `localId`). Set `INTERACTIVE=false` to restore flat `MENU_TEXT` one-shot CONTINUE.

## Install

```bash
cd tools/as-node
npm install
```

## Presets (npm scripts)

| Script | What it exercises |
|--------|-------------------|
| `npm run pull:fast` | `DELAY_MS=0` SYNC interactive menus inside AdaptiveTimeout gate |
| `npm run pull:bridge` | `DELAY_MS=8000` SYNC — late body → VirtualSessionBridge |
| `npm run pull:async` | `MODE=async_ack` + `WIRE=json` — ACK then `/as/callback` |
| `npm run push:ni` | Classic NI interactive menu → GW `/ussd` + `JSESSIONID` multi-turn |
| `npm run push:ni:static` | NI one-shot (`INTERACTIVE=false`) |

Env overrides:

```bash
WIRE=xml MENU_PICK=promo DELAY_MS=0 npm run pull:fast
INTERACTIVE=false MENU_TEXT='Hello lab' npm run pull:fast
MSISDN=251911000001 GW_NI=http://127.0.0.1:8088/ussd npm run push:ni
```

## Config

| Env | Default | Role |
|-----|---------|------|
| `PORT` / `HOST` | `8090` / `0.0.0.0` | Pull listen |
| `DELAY_MS` | `0` | Sync pull sleep (`8000` → bridge lab) |
| `MODE` | `sync` | `sync` \| `async_ack` |
| `WIRE` | `auto` | `xml` \| `json` \| `auto` |
| `ACTION` | `CONTINUE` | `CONTINUE` \| `END` \| `ABORT` (END/ABORT skip menus) |
| `INTERACTIVE` | `true` if CONTINUE | Multi-turn menus |
| `MENU_PICK` | `hash` | Menu selection policy |
| `MENU_TEXT` / `END_TEXT` | (static) | Used when `INTERACTIVE=false` |
| `GW_CALLBACK` | `http://127.0.0.1:8088/as/callback` | Late async POST |
| `CALLBACK_DELAY_MS` | `DELAY_MS` or `100` | Async callback delay |
| `API_KEY` | _(empty)_ | `X-USSD-Api-Key` |
| `GW_NI` / `MSISDN` / `CORR` | lab defaults | NI client |

Seed GW routing pull URL to `http://127.0.0.1:8090/ussd/pull`.

## PULL + auto-digit handset

With **ss7-simulator** (jSS7) auto-sending `1,2,3,4` on the MAP dialog:

```bash
npm run pull:fast
# other terminal — see ../ss7-simulator/README.md
../ss7-simulator/run.sh http   # HTTP smoke vs as-node
# or real MAP MO *100# from jSS7 USSD_TEST_CLIENT
```

## Sample curls

**XML MO begin → menu:**

```bash
curl -sS -X POST 'http://127.0.0.1:8090/ussd/pull' \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<dialog type="Begin" appCntx="networkUnstructuredSsContext_version2"
        networkId="0" localId="corr-1" mapMessagesSize="1">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="*100#">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </processUnstructuredSSRequest_Request>
</dialog>'
```

**Digit continue:**

```bash
curl -sS -X POST 'http://127.0.0.1:8090/ussd/pull' \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<dialog type="Continue" localId="corr-1" mapMessagesSize="1">
  <unstructuredSSRequest_Response dataCodingScheme="15" string="1">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </unstructuredSSRequest_Response>
</dialog>'
```

## AdaptiveTimeout + VirtualSessionBridge

```
MO MAP ──► GW pull ──► AS :8090/ussd/pull
                │
                ├─ DELAY_MS=0 / fast ASYNC_ACK
                │     └─ response inside gateMs → apply on live dialog (S1)
                │
                └─ DELAY_MS=8000 (or callback after gate)
                      → gate → wait text / S1 release
                      → late AS → VirtualSessionBridge → S2 NI
```

## Layout

```
tools/as-node/
  package.json
  README.md
  src/
    index.mjs      # CLI: pull | ni
    server.mjs     # Fastify PULL + menus
    menus.mjs      # Interactive catalog
    dialog.mjs     # XML/JSON codec
    callback.mjs   # POST /as/callback
    ni-client.mjs  # classic NI + JSESSIONID
tools/ss7-simulator/
  README.md        # MAP auto-digit + multi-MSISDN
```

Python peers: `tools/as-http-sim.py`, `tools/as-grpc-json-sim.py`.
