# RestLink USSD GW — Node Application Server sim

Fastify lab AS for **PULL**, **ASYNC_ACK → `/as/callback`**, and **classic NI** (`/ussd`).
Wire default matches the gateway: classic **`<dialog>` XML** (`ClassicDialogXmlCodec`); JSON `AsRequest` / `AsResponse` optional.

Requires **Node 20+**.

## Install

```bash
cd tools/as-node
npm install
```

## Presets (npm scripts)

| Script | What it exercises |
|--------|-------------------|
| `npm run pull:fast` | `DELAY_MS=0` SYNC — AS answers inside AdaptiveTimeout gate (~1–7 s EWMA). MAP stays live; CONTINUE/END applied on S1. |
| `npm run pull:bridge` | `DELAY_MS=8000` SYNC — AS answers **after** the ~7 s gate. GW releases MAP with wait text (S1); late body hits **VirtualSessionBridge** → S2 NI when bridge is armed. |
| `npm run pull:async` | `MODE=async_ack` + `WIRE=json` — quick `async:true` ACK, then POST late body to `GW_CALLBACK` (~8 s). Same late-reconcile path as bridge when gate fires first. |
| `npm run push:ni` | Classic NI first turn: POST `<dialog>` to GW `/ussd`, keep `JSESSIONID`, optional end turn. |

Env overrides work with any script, e.g.:

```bash
WIRE=json MENU_TEXT='Hello lab' DELAY_MS=0 npm run pull:fast
API_KEY=ussd-admin CALLBACK_DELAY_MS=2000 npm run pull:async
MSISDN=251911000001 GW_NI=http://127.0.0.1:8088/ussd npm run push:ni
```

## Config

| Env | Default | Role |
|-----|---------|------|
| `PORT` / `HOST` | `8090` / `0.0.0.0` | Pull listen |
| `DELAY_MS` | `0` | Sync pull sleep before response (`8000` → bridge lab) |
| `MODE` | `sync` | `sync` \| `async_ack` |
| `WIRE` | `auto` | `xml` \| `json` \| `auto` (from Content-Type / body) |
| `ACTION` | `CONTINUE` | `CONTINUE` \| `END` \| `ABORT` |
| `MENU_TEXT` | Balance/Topup menu | CONTINUE text |
| `END_TEXT` | Thank you… | END text |
| `GW_CALLBACK` | `http://127.0.0.1:8088/as/callback` | Late async POST |
| `CALLBACK_DELAY_MS` | `DELAY_MS` or `100` | Delay before callback in `async_ack` |
| `API_KEY` | _(empty)_ | `X-USSD-Api-Key` on callback / NI (lab admin key often `ussd-admin`) |
| `GW_NI` | `http://127.0.0.1:8088/ussd` | Classic NI URL |
| `MSISDN` / `NI_TEXT` / `CORR` | lab defaults | NI client |

Seed the GW routing pull URL to `http://127.0.0.1:8090/ussd/pull` (lab default).

## Sample curls (GW → AS pull perspective)

**XML CONTINUE (what GW posts on MO):**

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

Expected (fast preset): `text/xml` with `<unstructuredSSRequest_Request … string="1. Balance&#10;…"/>`.

**JSON AsRequest:**

```bash
curl -sS -X POST 'http://127.0.0.1:8090/ussd/pull' \
  -H 'Content-Type: application/json' \
  -d '{"correlationId":"corr-1","requestId":"corr-1","generation":0,"msisdn":"251911000001","shortCode":"*100#","ussdString":"*100#","networkId":0}'
```

**Simulate AS → GW late callback (after pull:async or manual bridge):**

```bash
curl -sS -X POST 'http://127.0.0.1:8088/as/callback' \
  -H 'Content-Type: application/json' \
  -H 'X-USSD-Api-Key: ussd-admin' \
  -d '{"correlationId":"corr-1","requestId":"corr-1","generation":1,"text":"1. Balance","action":"CONTINUE","async":false}'
```

**Classic NI first turn (AS → GW):**

```bash
curl -sS -D - -X POST 'http://127.0.0.1:8088/ussd' \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<dialog mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="Press 1 to confirm">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </unstructuredSSRequest_Request>
</dialog>'
```

Or: `npm run push:ni`.

## AdaptiveTimeout + VirtualSessionBridge

```
MO MAP ──► GW pull ──► AS :8090/ussd/pull
                │
                ├─ DELAY_MS=0 / fast ASYNC_ACK
                │     └─ response inside gateMs (~1000–7000 EWMA)
                │           → apply CONTINUE/END on live dialog (S1)
                │
                └─ DELAY_MS=8000 (or callback after gate)
                      → gate fires → wait text / S1 release
                      → late AS body → VirtualSessionBridge
                            → S2 NI push to subscriber (if bridge armed)
```

- Gate: `AdaptiveTimeout` EWMA per `networkId` (lab floor/ceiling ~1000–7000 ms).
- Bridge: `VirtualSessionBridge` — late content after S1 release reconciles via NI, not a second MAP open on the dead dialog.
- XML pull responses have **no** `async` bit (`ClassicDialogXmlCodec` always `async=false`). Use **`DELAY_MS=8000`** for XML bridge lab; use **`MODE=async_ack` + `WIRE=json`** for explicit ASYNC_ACK + `/as/callback`.

Callback auth: `CallbackAuthService` expects `X-USSD-Api-Key` or `X-API-Key` (tenant `http_api_key` or lab `ussd.admin.api-key`).

## Layout

```
tools/as-node/
  package.json
  README.md
  src/
    index.mjs      # CLI: pull | ni
    server.mjs     # Fastify PULL
    dialog.mjs     # XML/JSON codec
    callback.mjs   # POST /as/callback
    ni-client.mjs  # classic NI + JSESSIONID
```

Python peers (JSON-only / gRPC): `tools/as-http-sim.py`, `tools/as-grpc-json-sim.py`.
This Node sim is **HTTP-only**; gRPC Pull/Callback use the same JSON field names
(`correlationId`, `sessionId`, `virtualBridgeId`, `adaptiveTimeoutMs`, `async`) —
see `docs/as-contract/grpc-json.md`.

## Late-push metadata (echo on callback)

GW pull may include `sessionId` (virtualSessionId), `virtualBridgeId`, and
`adaptiveTimeoutMs`. **Push-back key = `correlationId`** (XML `localId`).
`MODE=async_ack` echoes those fields on the ACK and on `POST /as/callback`.
