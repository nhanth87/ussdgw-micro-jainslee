# Classic HTTP AS wire (`<dialog>` XML)

RestLink USSD GW speaks **dual-mode** HTTP AS wire:

| Mode | Content-Type | Default |
|------|--------------|---------|
| **XML** (classic XmlMAPDialog-compatible) | `text/xml; charset=utf-8` | **Yes** (global + per-tenant) |
| **JSON** (greenfield) | `application/json; charset=utf-8` | Opt-in |

Resolve format: tenant `http_as_wire_format` (set from **Routing** `HTTP AS wire` or Tenants)
→ global `ussd.as.http.wire-format` → **XML**.  
Samples + Content-Type: [`map2map-as-xml.md`](map2map-as-xml.md).  
Detail: [`openapi-as.yaml`](openapi-as.yaml) (JSON schemas) · this file (XML).

Classic oracle: WildFly `ussdgw` `XmlMAPDialog` / `EventsSerializeFactory` and
[Chapter-HTTP_Architecture](../../../../ussdgateway/master/docs/adminguide/sources-asciidoc/src/main/asciidoc/Chapter-HTTP_Architecture.adoc).

3GPP Stage 1/2 + MAP ops (and AdaptiveTimeout/Virtual bridge **on top** of MAP NI): [`ussd-3gpp-notes.md`](ussd-3gpp-notes.md).

## Root element

Root is always `<dialog …>`. Common attributes (subset):

| Attribute | Role |
|-----------|------|
| `type` | Informational TCAP shape: `Begin` / `Continue` / `End` / `Abort` / `Unknown` (AS usually omits) |
| `appCntx` | MAP AC, typically `networkUnstructuredSsContext_version2` |
| `networkId` | Multi-network id (default `0`) |
| `localId` / `remoteId` | TCAP dialog ids (GW may map correlation into localId on pull) |
| `mapMessagesSize` | Count of child MAP message elements |
| `emptyDialogHandshake` | NI/push only — see below |
| `prearrangedEnd` | End dialog without payload |
| `customInvokeTimeout` | Per-invoke MAP timeout (ms) |
| `returnMessageOnError` | Classic flag |
| `dialogTimedOut` / abort attrs | Error / timeout signaling toward AS |

Child MAP elements (element or attribute style both accepted by classic):

| Element | Typical use |
|---------|-------------|
| `processUnstructuredSSRequest_Request` | MO pull initial (`*123#`) |
| `processUnstructuredSSRequest_Response` | Final MO END text |
| `unstructuredSSRequest_Request` | Menu CONTINUE (MO continue or NI) |
| `unstructuredSSRequest_Response` | Subscriber digits / reply |
| `unstructuredSSNotify_Request` / `_Response` | Notify (often NI) |

USSD text: child `<ussdString>…</ussdString>` or attribute `string="…"`.  
MSISDN: `<msisdn nai="…" npi="…" number="…"/>`. DCS: `dataCodingScheme` (e.g. `15`).

### Example — MO pull request (GW → AS)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<dialog type="Begin" appCntx="networkUnstructuredSsContext_version2"
        networkId="0" localId="corr-1" mapMessagesSize="1"
        returnMessageOnError="false">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="*100#">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

### Example — AS CONTINUE menu

```xml
<dialog mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="1. Balance&#10;2. Topup"/>
</dialog>
```

## Paths and Content-Types

| Path | Direction | Role | Body |
|------|-----------|------|------|
| AS pull URL (routing rule) | GW → AS | **PULL** MO/continue | XML or JSON per tenant; response same mode |
| `/as/callback` | AS → GW | Async / bridge late response | Greenfield JSON `AsResponse` **or** XML dialog ack (dual-mode) |
| `/ussd` (config `ussd.http.ni-path`, default `/ussd`) | AS → GW | **Classic NI sync** push | Classic `<dialog>` XML (JSON NI optional when tenant JSON) |

Pull uses the **raw** request/response body — never a `CallbackRequest` envelope.  
HTTP/gRPC completion = **RA callbacks only** (no timer poll).

## Classic NI sync + `JSESSIONID`

Classic NI is a **multi-request HTTP dialog** on the NI path (RestLink default **`/ussd`**; classic lab often `/restcomm` or `/mobicents`):

1. AS POSTs initial NI `<dialog>` (e.g. `unstructuredSSRequest_Request` + MSISDN).
2. GW answers with `Set-Cookie: JSESSIONID=…` and keeps the HTTP exchange **parked** until MAP/SRI/dialog progress warrants a response body (or error).
3. Every subsequent AS POST for that NI dialog must send `Cookie: JSESSIONID=…`.
4. AS finishes with empty/end dialog (`mapMessagesSize="0"`, optional `prearrangedEnd`).

**Parked HTTP must not block a worker with `Thread.sleep`.** Hold the response with async/suspend + **`AdaptiveTimeout`** (EWMA gate) / SLEE or Vert.x completion — same adaptive gate used for pull/bridge. Operator Stop and peer-down still follow link-status truth.

## `emptyDialogHandshake`

Attribute on NI/push `<dialog>` from the AS only:

```xml
<dialog mapMessagesSize="1" emptyDialogHandshake="true">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="Press 1 to confirm">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </unstructuredSSRequest_Request>
</dialog>
```

Meaning: GW opens an **empty** MAP dialog first; only after the peer accepts the dialog does it send the USSD payload. Omit or `false` for normal single-shot open+payload.

## Adaptive gate (on top of MAP NI)

`AdaptiveTimeout` (EWMA per `networkId`) + **`ClassicNiHttpPark`** + **`VirtualSession` / BRIDGE** are the **top** AS park/gate layer. MAP NI (`unstructuredSS-Notify` / `-Request`, same-dialog continue, TC-END) sits **under** that layer — do not replace bridge/adaptive with “raw MAP only”. Detail + sequence: [`ussd-3gpp-notes.md`](ussd-3gpp-notes.md) §6.

`AdaptiveTimeout` gates how long GW waits for AS on pull and how long a **parked NI HTTP** response may stay open before abort/bridge policy. Floor/ceiling match classic (~1000–7000 ms lab defaults). MAP **invoke** timer (UE think-time on Request) is orthogonal — see grill Q6 in the 3GPP notes. Late AS after gate may hit Virtual Session Bridge (S1 wait text → S2 NI) when bridge is armed — same behavior matrix as classic §8.

### Late-push metadata (RestLink additive)

GW includes these on **PULL** encode (HTTP XML attrs / JSON fields; gRPC JSON bytes share the same schema):

| Field | XML attr | Meaning |
|-------|----------|---------|
| `localId` / `correlationId` | `localId` | **Real session id for push-back** — VirtualSessionStore / `bridge.onAsResponse` key. AS must echo on `/as/callback` or gRPC `Callback`. |
| `sessionId` | `sessionId` | Logical `virtualSessionId` (not the store key). |
| `virtualBridgeId` | `virtualBridgeId` | Bridge arm identity when armed (usually equals `correlationId`). |
| `adaptiveTimeoutMs` | `adaptiveTimeoutMs` | Live gate budget ms for this session (`AdaptiveTimeout.effectiveGateMs` = configured async-gate ceiling, not EWMA). |
| `asMode` | `asMode` | Hint: `SYNC` \| `BRIDGE` (ASYNC_ACK is AS response `async=true`). |
| `shortCode` | `shortCode` | Matched routing-rule key. |
| `originatedUssd` | `originatedUssd` | Full UE dialed string (MAP2MAP keeps hop text in `ussdString`). |
MAP2MAP AS pull samples (`hlrResult`, `redirectUssd`, `hopUssd`): [`map2map-as-xml.md`](map2map-as-xml.md).

| `codeKind` | `codeKind` | `SHORT` \| `LONG` (mark + longer dial → LONG). |

Classic AS may ignore unknown attributes. RestLink also accepts `async="true"` on response `<dialog>` for XML ASYNC_ACK.

Example pull fragment:

```xml
<dialog appCntx="networkUnstructuredSsContext" localId="corr-1"
        sessionId="vs-1" virtualBridgeId="corr-1" adaptiveTimeoutMs="4200"
        asMode="BRIDGE" networkId="0">
  …
</dialog>
```

### Gated session push (GW → AS, RestLink additive)

When AdaptiveTimeout / bridge **gate fires**, GW POSTs a **new** classic `<dialog>` XML to the short-code rule **`asUrl`** (same HTTP AS endpoint as MO pull). This is independent of the parked NI HTTP reply and of any in-flight pull (HTTP client session id = `gated-{correlationId}`).

Additive attrs on the dialog (classic AS may ignore unknowns):

| Attr | Meaning |
|------|---------|
| `virtualBridgeId` | Bridge / push-back id (usually = `localId` / correlation) |
| `adaptiveTimeoutMs` | Gate ms that fired |
| `observedEwmaMs` | Observed EWMA latency sample for `networkId` (when known) |
| `jsessionId` | Classic NI Cookie `JSESSIONID` when the gated leg was HTTP-NI park |
| `gateReason` | `GATE_EXPIRED` (NI park) \| `BRIDGED` (MO bridge wait) \| `GATE_NO_BRIDGE` |

Body uses one-shot `unstructuredSSNotify_Request` whose `string` echoes `gateReason` (prior session was gated — AS may re-push with the same `jsessionId` / `virtualBridgeId`).

```xml
<dialog appCntx="networkUnstructuredSsContext" localId="corr-1"
        sessionId="vs-1" virtualBridgeId="corr-1"
        adaptiveTimeoutMs="4200" observedEwmaMs="2800"
        asMode="BRIDGE" jsessionId="js-abc" gateReason="GATE_EXPIRED"
        networkId="0" mapMessagesSize="1">
  <unstructuredSSNotify_Request dataCodingScheme="15" string="GATE_EXPIRED">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </unstructuredSSNotify_Request>
</dialog>
```

Encoder: `ClassicDialogXmlCodec.encodeGatedPush` · dispatcher: `GatedAsNotifyService` → `GatedAsNotifyEvent` → `HttpClientSbb`.

## Mapping to greenfield JSON

| XML signal | JSON `AsResponse` / `AsRequest` |
|------------|----------------------------------|
| `unstructuredSSRequest_Request` text | `action=CONTINUE`, `text=…` |
| `processUnstructuredSSRequest_Response` / empty end | `action=END` |
| abort attrs / user abort | `action=ABORT` |
| MSISDN / shortCode / ussdString / originatedUssd / codeKind on pull | `AsRequest` fields (+ XML dialog attrs) |
| `localId` | `correlationId` (**push-back key**) |
| `sessionId` / `virtualBridgeId` / `adaptiveTimeoutMs` / `asMode` | same JSON field names |
| `async` late reconcile | Prefer `/as/callback` or gRPC Callback (+ optional `X-Ussd-Request-Id` bridge headers) |

gRPC remains greenfield JSON payload bytes ([`grpc-json.md`](grpc-json.md)); HTTP is the dual-mode plane. Same metadata fields on both.
