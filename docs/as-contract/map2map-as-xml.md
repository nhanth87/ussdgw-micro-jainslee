# Digicom-ET USSDGW — AS HTTP wire contract (XML + JSON, MO + MAP2MAP)

Application-server integrator guide for Digicom-ET USSDGW **HTTP AS pull**. The gateway is
**dual-mode**: classic XmlMAPDialog **XML** (default) and greenfield **JSON** (`AsRequest` /
`AsResponse`). Digicom production AS endpoints today typically use **XML**.

| Mode | Content-Type | Codec | Default |
|------|--------------|-------|---------|
| **XML** | `text/xml; charset=utf-8` | `ClassicDialogXmlCodec` | **Yes** |
| **JSON** | `application/json; charset=utf-8` | `AsWireCodec` ↔ `AsRequest` / `AsResponse` | Opt-in per tenant |

**How the tenant selects wire mode**

1. **Routing dashboard** (`/admin/routing`) — form field **HTTP AS wire** (`XML` \| `JSON`).
   Persists on the rule’s **tenant** (`ussd_tenant.http_as_wire_format`); Live rules table
   shows a **wire** column. TENANT principals update their locked tenant; ADMIN/OPS pick
   any tenant on the form. Unbound rules (no tenantId) cannot enable JSON from Routing.
2. **Tenants catalog** (`/admin/tenants`) — same field `httpAsWireFormat`.
3. Else global `ussd.as.http.wire-format` (`xml` \| `json`).
4. Else **XML**.

Resolver: `WireFormatResolver` (tenant → global → XML). Same AS URL; only Content-Type and body shape change.
Hot-read: next AS pull uses the tenant row immediately after Save (no restart).

Canonical peers:

| Doc | Role |
|-----|------|
| [`classic-xml.md`](classic-xml.md) | Full classic `<dialog>` grammar + NI park |
| [`openapi-as.yaml`](openapi-as.yaml) | JSON schemas (`AsRequest` / `AsResponse`) |
| [`map2map.md`](map2map.md) | MAP2MAP Case 2 hop / AdaptiveTimeout / CDR |
| [`ussd-3gpp-notes.md`](ussd-3gpp-notes.md) | Request vs Notify (3GPP) |
| Codecs | XML: `ClassicDialogXmlCodec` · JSON: `AsWireCodec` |

---

## HTTP basics

| Item | XML | JSON |
|------|-----|------|
| Direction | **GW → AS** HTTP `POST` (AS pull) | same |
| Example URL | Short-code rule `as_url` | same |
| Content-Type | `text/xml; charset=utf-8` | `application/json; charset=utf-8` |
| Body | Raw `<dialog>…</dialog>` | Raw `AsRequest` / `AsResponse` JSON — **not** a Callback envelope |
| Success | HTTP **200** + body (empty body → END / `AS_EMPTY_BODY`) | same |
| Push-back key | Echo `localId` | Echo `correlationId` (or `virtualBridgeId`) |

AS must answer within `adaptiveTimeoutMs`. Empty HTTP 200 is **not** a menu.

### Identity map (XML attr ↔ JSON field)

| XML dialog attr | JSON field | Role |
|-----------------|------------|------|
| `localId` | `correlationId` | Store / push-back key (`ussdTx` PK) |
| `sessionId` | `sessionId` | Logical `virtualSessionId` |
| `virtualBridgeId` | `virtualBridgeId` | Bridge arm id (usually = correlationId) |
| `adaptiveTimeoutMs` | `adaptiveTimeoutMs` | Gate budget ms |
| `asMode` | `asMode` | `SYNC` \| `BRIDGE` |
| Child `string=` (pull) | `ussdString` | Hop text or sentinel / empty |
| AS menu/final `string=` | `text` | Handset text on CONTINUE/END |
| — (element type) | `action` | `CONTINUE` \| `END` \| `ABORT` |
| `dataCodingScheme` | `alphabet` | e.g. `72` → `UNICODE`; omit → `AUTO` |
| `hlrResult` | *(XML-only)* | JSON AS: infer from `ussdString` + `redirectUssd`/`hopUssd` — see below |

**JSON hop outcome (no `hlrResult` field on `AsRequest`):**

| Condition | Treat as |
|-----------|----------|
| Non-empty `ussdString` (not a sentinel) + MAP2MAP codes | hop **responded** |
| `ussdString` empty + `redirectUssd` / `hopUssd` present | hop **none** |
| `ussdString` = `"hlr reject"` | hop **reject** |
| `ussdString` = `"hlr pending"` | hop **pending** |

Never echo pull `ussdString` onto the UE unless `action` intentionally forwards hop text (`responded`).

---

## Field glossary (dialog attributes)

| Attr | Who sets | Meaning |
|------|----------|---------|
| `localId` | GW | Correlation / push-back key (echo this) — see **Session identity** below |
| `sessionId` | GW | Logical virtual session id (not the store key) |
| `virtualBridgeId` | GW | Bridge arm id when AdaptiveTimeout/bridge armed (usually = `localId`) |
| `adaptiveTimeoutMs` | GW | Live gate budget ms for this session |
| `asMode` | GW | `SYNC` or `BRIDGE` |
| `networkId` | GW | SCCP / tenant network id (Digicom live often `0`) |
| `shortCode` | GW | Matched routing-rule key (e.g. `*804#`) |
| `originatedUssd` | GW | Full UE dialed string (e.g. `*804#` or long mark dial) |
| `codeKind` | GW | `SHORT` or `LONG` |
| `redirectUssd` | GW (MAP2MAP) | Rule redirect / re-route short code (e.g. `*875#`) |
| `hopUssd` | GW (MAP2MAP) | Resolved hop USSD actually sent to upper HLR/MSC (may be long `*875*…#`) |
| `hlrResult` | GW (MAP2MAP) | Hop outcome flag — see table below |
| `jsessionId` | GW (gated / NI) | Cookie value for classic NI park — **not** the same as `localId` |
| `appCntx` | GW | Usually `networkUnstructuredSsContext` |
| Child `string=` | GW or AS | USSD text on the MAP message element |
| Child `<msisdn …/>` | GW | Subscriber MSISDN |

### Session identity — `localId`, `sessionId`, `virtualBridgeId`

Three string attributes often appear together on GW→AS pulls. They look similar but play
**different** roles. Getting them wrong is the usual cause of “AS replied but GW ignored it”.

#### What each is

| Attr | Plain-language role | Internal name |
|------|---------------------|---------------|
| **`localId`** | **The session key.** One in-flight USSD transaction. Use this to look up the call on late push-back (`/as/callback`) and in your own AS logs. | `correlationId` |
| **`sessionId`** | **Logical / display session id.** A UUID for the virtual session object. Useful for tracing; **not** the primary store lookup key. | `virtualSessionId` |
| **`virtualBridgeId`** | **Bridge arm id** when AdaptiveTimeout / Virtual Session Bridge is armed (`asMode="BRIDGE"`). Tells the AS “this pull can recover via bridge / gated notify”. | Usually equals `correlationId` (= `localId`) when armed; omitted or unused when `asMode="SYNC"` |

#### What each equals (and what it does **not** equal)

| Attr | Equals | Does **not** equal |
|------|--------|---------------------|
| `localId` | `correlationId` · ProfileFacility / saga table **`ussdTx` PK** · VirtualSessionStore key · bridge `claimForAsResponse` / push-back key | `sessionId` · HTTP `JSESSIONID` · outbound MAP hop dialog key (`m2m-{corr}`) |
| `sessionId` | `virtualSessionId` on the in-memory / profile row | `ussdTx` PK · push-back key (unless you wrongly echo only this and omit `localId`) |
| `virtualBridgeId` | Usually **`localId` / `correlationId`** when bridge armed | A separate “bridge database”; not `JSESSIONID` |
| `jsessionId` (when present) | Classic **NI HTTP park** cookie (`Set-Cookie: JSESSIONID=…` on `/ussd`) | `localId` / `ussdTx` — NI park only |

**Rule of thumb:** treat **`localId` as the truth**. If only one id can be stored in the AS, store `localId`.

#### What the AS must echo back

| Path | Required echo | Optional but useful |
|------|---------------|---------------------|
| **Sync pull response** (HTTP 200 body on the same POST) | Identity attrs are **optional** — GW already knows the session from the outstanding pull. Still safe to echo `localId`. | `sessionId`, `virtualBridgeId` |
| **Late `/as/callback`** (or gRPC Callback) after AdaptiveTimeout / async | **Must** echo **`localId`** (= correlation). GW resolves via `AsResponse.resolvePushBackId()`: `localId` / `correlationId` → else `virtualBridgeId` → else `sessionId`. | Echo all three as received |
| **Classic NI** subsequent POSTs | Cookie **`JSESSIONID`** (= `jsessionId`), not `localId` | `localId` may also appear on gated bodies |

If the AS omits `localId` on a late callback and only sends `sessionId`, GW *may* still match when `sessionId` was the only fallback — but that is fragile. Always echo **`localId`**.

#### How they relate on the MAP2MAP hop path

```
UE dials *804#
  → GW creates VirtualSession
       correlationId  = UUID  → XML localId          → ussdTx PK
       virtualSessionId = UUID → XML sessionId
  → Bridge armed               → XML virtualBridgeId ≈ localId
                               → asMode=BRIDGE, adaptiveTimeoutMs=…
  → Outbound hop dialog key    = m2m-{correlationId}  (NOT written as localId)
  → Hop RESULT / CLOSE / REJECT
  → GW POST pull to AS as_url with localId / sessionId / virtualBridgeId
       string= = hop USSD text | empty (hlrResult=none) | "hlr reject"
  → AS HTTP 200 CONTINUE/END (or later /as/callback with localId echoed)
  → GW MAP toward UE (Request menu or final Response)
```

Notes for integrators:

1. **`localId` does not change** across hop RESULT → AS pull → UE menu continues for that MO.
2. The outbound hop uses a **different** dialog id (`m2m-…`); never put that into `localId`.
3. After hop, AS may start a **multi-menu** (§4d) — each continue pull still uses the **same** `localId`.
4. If AdaptiveTimeout fires first, GW may send a gated Notify with the same ids; re-push with
   `localId` (and `jsessionId` / Cookie when NI).

### MAP2MAP `hlrResult` + `string=` (locked)

**`string=` on `processUnstructuredSSRequest_Request` is only the upper HLR/MSC hop USSD body**
(or a hop sentinel). Never put the UE dial (`*804#`) or redirect/hop code (`*875#`) into `string=` —
those live in `originatedUssd` / `shortCode` / `redirectUssd` / `hopUssd`.

| Case | `hlrResult` | `string=` on `processUnstructuredSSRequest_Request` |
|------|-------------|------------------------------------------------------|
| Hop **no** response (empty CLOSE / no RESULT text) | **`none`** | **empty** (`string=""`) — do **not** echo onto UE |
| Hop Dialog **REJECT** | **`reject`** | **`hlr reject`** |
| Early gated pull while hop in flight | **`pending`** | **`hlr pending`** |
| Hop **did** respond with USSD text | **`responded`** | **That upper HLR/MSC USSD text** |

AS must use its **own** CONTINUE menu when `hlrResult` is `none` / `reject` / `pending`.
Only when `hlrResult="responded"` should business logic treat child `string=` as hop content
(and never blindly echo hop/`hlr*` strings as the handset menu unless that is intentional).

Non-MAP2MAP MO pulls leave `hlrResult` / `redirectUssd` / `hopUssd` unset.

CDR note: GW status `CONTINUE` means the **AS response** was a menu
(`unstructuredSSRequest_Request` with non-empty text) — not “hop continued”.

---

## 1. Normal MO pull (no MAP2MAP) — GW → AS

UE dials a short code that routes straight to HTTP AS (no re-route hop).

**XML**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<dialog appCntx="networkUnstructuredSsContext"
        localId="corr-mo-1"
        sessionId="vs-mo-1"
        virtualBridgeId="corr-mo-1"
        adaptiveTimeoutMs="7000"
        asMode="BRIDGE"
        shortCode="*100#"
        originatedUssd="*100#"
        codeKind="SHORT"
        networkId="0">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="*100#">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

**JSON** (`AsRequest` — fields from `AsWireCodec`)

```json
{
  "sessionId": "vs-mo-1",
  "correlationId": "corr-mo-1",
  "requestId": "corr-mo-1",
  "generation": 0,
  "msisdn": "251911000001",
  "shortCode": "*100#",
  "ussdString": "*100#",
  "networkId": 0,
  "virtualBridgeId": "corr-mo-1",
  "adaptiveTimeoutMs": 7000,
  "asMode": "BRIDGE",
  "originatedUssd": "*100#",
  "codeKind": "SHORT"
}
```

Generation 0 → XML `processUnstructuredSSRequest_Request` / JSON `generation: 0`. Later user
digits → XML `unstructuredSSRequest_Request` (continue pull) / JSON `generation` &gt; 0 with
digit text in `ussdString`.

---

## 2. AS → GW CONTINUE menu (what CDR `CONTINUE` means)

AS wants the handset to show a menu and wait for digits. Return HTTP 200 with:

**XML**

```xml
<dialog mapMessagesSize="1" localId="corr-mo-1">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="1. Balance&#10;2. Topup&#10;0. Exit"/>
</dialog>
```

**JSON** (`Content-Type: application/json; charset=utf-8`)

```json
{
  "correlationId": "corr-mo-1",
  "requestId": "corr-mo-1",
  "generation": 1,
  "text": "1. Balance\n2. Topup\n0. Exit",
  "action": "CONTINUE",
  "async": false,
  "alphabet": "AUTO",
  "sessionId": "vs-mo-1",
  "virtualBridgeId": "corr-mo-1"
}
```

GW maps this to MAP **`unstructuredSS-Request`** toward the UE (interactive). CDR **CONTINUE**.

**Never** use `unstructuredSSNotify_Request` / Notify for a menu — one-shot, no digits.
See [`ussd-3gpp-notes.md`](ussd-3gpp-notes.md).

Optional RestLink attrs on the XML response (echo identity if useful):

```xml
<dialog localId="corr-mo-1" sessionId="vs-mo-1" virtualBridgeId="corr-mo-1"
        mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="Choose:&#10;1 OK&#10;2 Cancel"/>
</dialog>
```

---

## 3. AS → GW final (END)

End the MO dialog with final text. Use **`processUnstructuredSSRequest_Response`** (XML) or
JSON **`action":"END"`**. GW maps to MAP **`processUnstructuredSS-Response`** + TC-END
(`end=true`). CDR **END** (not CONTINUE).

**XML** (Amharic / UCS-2 — must reach the handset)

```xml
<dialog mapMessagesSize="1" localId="corr-mo-1"
        sessionId="vs-mo-1" virtualBridgeId="corr-mo-1"
        prearrangedEnd="false" returnMessageOnError="true">
  <processUnstructuredSSRequest_Response
      invokeId="1"
      dataCodingScheme="72"
      string="ውድ ደንበኛ ፤ ውጤቱ በአጭር መለእክት ተልኳል፡፡ ኢትዮ ቴሌኮም"/>
</dialog>
```

**JSON** (same final text)

```json
{
  "correlationId": "corr-mo-1",
  "requestId": "corr-mo-1",
  "generation": 1,
  "text": "ውድ ደንበኛ ፤ ውጤቱ በአጭር መለእክት ተልኳል፡፡ ኢትዮ ቴሌኮም",
  "action": "END",
  "async": false,
  "alphabet": "UNICODE",
  "sessionId": "vs-mo-1",
  "virtualBridgeId": "corr-mo-1"
}
```

| Field | Notes |
|-------|--------|
| XML element | **`processUnstructuredSSRequest_Response`** = final; Request = menu |
| JSON `action` | **`END`** = final; **`CONTINUE`** = menu |
| `dataCodingScheme="72"` / `alphabet":"UNICODE"` | CBS UCS-2 — Amharic/Ethiopic |
| `localId` / `correlationId` | Echo from pull when possible; sync pull may omit (GW uses outstanding corr). Late `/as/callback` **must** echo |

Empty end — also END:

```xml
<dialog mapMessagesSize="0"/>
```

```json
{ "correlationId": "corr-mo-1", "action": "END", "text": "", "async": false }
```

Abort:

```xml
<dialog mapMessagesSize="0" mapUserAbortChoice="isUserSpecificReason"/>
```

```json
{ "correlationId": "corr-mo-1", "action": "ABORT", "text": "", "async": false }
```

**Common mistake:** putting final text in `unstructuredSSRequest_Request` / `action":"CONTINUE"`,
or echoing pull `hlr none` — handset shows a menu/placeholder instead of the final message.

---

## 4. MAP2MAP after hop — GW → AS

Rule example: UE dials `*804#`, re-route redirect `*875#`, hop may resolve to a long code
(e.g. `*8775#` after mark/chain fold). GW POSTs to the rule `as_url` after the upper hop
settles (or with empty `string=` / `ussdString` / `hlr reject` when the hop had no usable text).

### 4a. Success — hop RESULT text (`hlrResult=responded`)

Upper HLR/MSC returned USSD text (any alphabet — e.g. UCS-2 Amharic). That text is the
**only** content of child `string=` / JSON `ussdString`:

**XML**

```xml
<dialog appCntx="networkUnstructuredSsContext"
        localId="e37caa26-9d16-4239-a2ff-deff0687da8d"
        sessionId="4203367b-c862-4307-81a7-3fbaa50b2afd"
        virtualBridgeId="e37caa26-9d16-4239-a2ff-deff0687da8d"
        adaptiveTimeoutMs="25000"
        asMode="BRIDGE"
        shortCode="*804#"
        originatedUssd="*804#"
        codeKind="SHORT"
        redirectUssd="*875#"
        hopUssd="*8775#"
        hlrResult="responded"
        networkId="0">
  <processUnstructuredSSRequest_Request dataCodingScheme="15"
      string="ውድ ደንበኛ ፣ ውጤቱ በአጭር መልእክት ተልኳል። ኢትዮ ቴሌኮም">
    <msisdn nai="international_number" npi="ISDN" number="251911230398"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

**JSON** (`AsRequest` — no `hlrResult` field; non-empty `ussdString` + MAP2MAP codes ⇒ responded)

```json
{
  "sessionId": "4203367b-c862-4307-81a7-3fbaa50b2afd",
  "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "requestId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "generation": 0,
  "msisdn": "251911230398",
  "shortCode": "*804#",
  "ussdString": "ውድ ደንበኛ ፣ ውጤቱ በአጭር መልእክት ተልኳል። ኢትዮ ቴሌኮም",
  "networkId": 0,
  "virtualBridgeId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "adaptiveTimeoutMs": 25000,
  "asMode": "BRIDGE",
  "originatedUssd": "*804#",
  "codeKind": "SHORT",
  "redirectUssd": "*875#",
  "hopUssd": "*8775#"
}
```

- `string=` / `ussdString` = **upper HLR/MSC USSD text** (Amharic/UTF-8 allowed; XML-escape `<&"`).
- `originatedUssd` = what the subscriber dialed.
- `redirectUssd` = routing-rule redirect short code.
- `hopUssd` = code actually sent on the outbound hop (short or long).
- Identity: `localId` / `correlationId` = `ussdTx` PK; `sessionId` = virtual session; `virtualBridgeId` ≈ correlation.

AS then answers with a **single** CONTINUE menu (§2), a **multi-menu** flow (§4d), or final
Response / `action":"END"` (§3).

### 4b. Hop empty / CLOSE — no RESULT text (`hlrResult=none`)

When the peer closes the hop dialog without a USSD RESULT (empty TC-END / NOTICE+CLOSE),
GW still pulls the AS so the application can decide the UE message. Hop status is on
**`hlrResult="none"`** (XML); child **`string=` / `ussdString` is empty** so a naive AS that
echoes inbound text cannot put the literal `hlr none` onto the handset.

**XML**

```xml
<dialog appCntx="networkUnstructuredSsContext"
        localId="e37caa26-9d16-4239-a2ff-deff0687da8d"
        sessionId="4203367b-c862-4307-81a7-3fbaa50b2afd"
        virtualBridgeId="e37caa26-9d16-4239-a2ff-deff0687da8d"
        adaptiveTimeoutMs="25000"
        asMode="BRIDGE"
        shortCode="*804#"
        originatedUssd="*804#"
        codeKind="SHORT"
        redirectUssd="*875#"
        hopUssd="*8775#"
        hlrResult="none"
        networkId="0">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="">
    <msisdn nai="international_number" npi="ISDN" number="251911230398"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

**JSON** (empty `ussdString` + `redirectUssd`/`hopUssd` ⇒ treat as hop **none**)

```json
{
  "sessionId": "4203367b-c862-4307-81a7-3fbaa50b2afd",
  "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "requestId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "generation": 0,
  "msisdn": "251911230398",
  "shortCode": "*804#",
  "ussdString": "",
  "networkId": 0,
  "virtualBridgeId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "adaptiveTimeoutMs": 25000,
  "asMode": "BRIDGE",
  "originatedUssd": "*804#",
  "codeKind": "SHORT",
  "redirectUssd": "*875#",
  "hopUssd": "*8775#"
}
```

Honest contract: hop **none** means **no hop USSD text was available**. Re-route codes remain
so the AS can still key off `*875#` / `hopUssd`. Return your configured menu via
`unstructuredSSRequest_Request` / `action":"CONTINUE"` — do **not** echo pull `string=` /
`ussdString` unless hop **responded**.

Example AS CONTINUE after empty hop (illustrative):

**XML**

```xml
<dialog mapMessagesSize="1" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="meow meow meow meow"/>
</dialog>
```

**JSON**

```json
{
  "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "requestId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "generation": 1,
  "text": "meow meow meow meow",
  "action": "CONTINUE",
  "async": false,
  "alphabet": "AUTO"
}
```

(Legacy note: older GW builds used `string="hlr none"`; treat that as hop none if still seen.)

### 4c. Hop REJECT (`hlrResult=reject`)

**XML**

```xml
<dialog … shortCode="*804#" originatedUssd="*804#" redirectUssd="*875#" hopUssd="*875#"
        hlrResult="reject" networkId="0"
        localId="corr-…" sessionId="vs-…" virtualBridgeId="corr-…">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="hlr reject">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

**JSON** (`ussdString` = `"hlr reject"`)

```json
{
  "sessionId": "vs-…",
  "correlationId": "corr-…",
  "requestId": "corr-…",
  "generation": 0,
  "msisdn": "251911000001",
  "shortCode": "*804#",
  "ussdString": "hlr reject",
  "networkId": 0,
  "virtualBridgeId": "corr-…",
  "adaptiveTimeoutMs": 25000,
  "asMode": "BRIDGE",
  "originatedUssd": "*804#",
  "codeKind": "SHORT",
  "redirectUssd": "*875#",
  "hopUssd": "*875#"
}
```

### 4d. Multi-menu on the return path (AS → GW → UE)

After the hop pull (§4a–4c), the AS may drive an **interactive multi-menu** toward the UE.
This is the same CONTINUE machine as ordinary MO (§2), including MAP2MAP.

#### Supported model (preferred) — successive HTTP round-trips

Each AS HTTP response carries **one** interactive menu. After the UE presses digits, GW pulls
the AS again with those digits; the AS returns the next menu or END.

**Turn 1 — AS → GW (first menu after hop):**

**XML**

```xml
<dialog mapMessagesSize="1"
        localId="e37caa26-9d16-4239-a2ff-deff0687da8d"
        sessionId="4203367b-c862-4307-81a7-3fbaa50b2afd"
        virtualBridgeId="e37caa26-9d16-4239-a2ff-deff0687da8d">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="1. Balance&#10;2. Data&#10;3. Help&#10;0. Exit"/>
</dialog>
```

**JSON**

```json
{
  "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "requestId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "generation": 1,
  "text": "1. Balance\n2. Data\n3. Help\n0. Exit",
  "action": "CONTINUE",
  "async": false,
  "alphabet": "AUTO",
  "sessionId": "4203367b-c862-4307-81a7-3fbaa50b2afd",
  "virtualBridgeId": "e37caa26-9d16-4239-a2ff-deff0687da8d"
}
```

GW → MAP **`unstructuredSS-Request`** to the UE (stay-on-call). CDR **CONTINUE**.

**Turn 2 — UE digits → GW → AS pull** (generation &gt; 0; child is continue Request with digit
string — see §1). AS replies with the next menu:

**XML**

```xml
<dialog mapMessagesSize="1" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="Balance menu&#10;1. Main&#10;2. Bonus&#10;0. Back"/>
</dialog>
```

**JSON**

```json
{
  "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "text": "Balance menu\n1. Main\n2. Bonus\n0. Back",
  "action": "CONTINUE",
  "async": false,
  "alphabet": "AUTO"
}
```

**Turn 3 — final** (must reach UE as END):

**XML**

```xml
<dialog mapMessagesSize="1" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
  <processUnstructuredSSRequest_Response dataCodingScheme="15"
      string="Thank you."/>
</dialog>
```

**JSON**

```json
{
  "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
  "text": "Thank you.",
  "action": "END",
  "async": false,
  "alphabet": "AUTO"
}
```

`localId` / `correlationId` stays the **same** for the whole MO / MAP2MAP session.

#### Optional — `mapMessagesSize` &gt; 1 in one AS body

Classic XmlMAPDialog can list more than one child MAP message. This GW decodes the **first**
meaningful `unstructuredSSRequest_Request` / Response string and applies **one** MAP action
toward the UE (same as a single-menu CONTINUE/END). Extra sibling Request elements in the
**same** HTTP body are **not** queued as later menus. JSON `AsResponse` is always one action.

```xml
<!-- Not a multi-step queue: only the first Request string is applied -->
<dialog mapMessagesSize="2" localId="corr-mm">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="Menu 1&#10;1 Next"/>
  <unstructuredSSRequest_Request dataCodingScheme="15" string="would-be-ignored"/>
</dialog>
```

For true multi-step menus, use **successive** responses after UE digits (§4d preferred model).

#### How GW maps AS wire → MAP toward UE

| AS wire (XML / JSON) | MAP toward UE | Interactive? |
|----------------------|---------------|--------------|
| `unstructuredSSRequest_Request` + text / `action":"CONTINUE"` | `unstructuredSS-Request` | **Yes** — wait for digits |
| `processUnstructuredSSRequest_Response` / empty dialog / `action":"END"` | Final Response / TC-END | No |
| `unstructuredSSNotify_Request` / Notify | `unstructuredSS-Notify` | **No** — one-shot; do not use as menu |
| Abort attrs / `action":"ABORT"` | MAP abort | No |

Aligns with [`classic-xml.md`](classic-xml.md) and [`ussd-3gpp-notes.md`](ussd-3gpp-notes.md).

---

## 5. What the AS should implement (checklist)

1. Accept `POST` with the tenant wire: `text/xml; charset=utf-8` **or**
   `application/json; charset=utf-8`. Enable JSON from **Routing → HTTP AS wire**
   (or Tenants `httpAsWireFormat` / global `ussd.as.http.wire-format`).
2. Parse `<dialog>` **or** `AsRequest`; read identity (`localId` / `correlationId`), MSISDN,
   hop text (`string=` / `ussdString`), and MAP2MAP attrs. Persist **`localId`/`correlationId`**
   for late push-back.
3. Hop **responded** (`hlrResult="responded"` or non-empty hop `ussdString`) → treat that text
   as upper-HLR content for business logic.
4. Hop **none** / **reject** / **pending** → use dialog / `AsRequest` attrs for routing; return
   **your** menu — never echo pull empty / `hlr reject` / `hlr pending` onto the UE unless
   intentional.
5. Reply 200 with CONTINUE or END (§2 / §3). Multi-menu = successive CONTINUEs after each UE
   digit pull (§4d). Final text that must reach the UE = `processUnstructuredSSRequest_Response`
   or `action":"END"`.
6. Never confuse **Notify** with an interactive menu — menus use **Request** / `CONTINUE`.
7. On `/as/callback`, echo **`localId`/`correlationId`** (and ideally `virtualBridgeId`).

---

## 6. Common mistakes

| Mistake | Symptom |
|---------|---------|
| HTTP 200 + empty body | CDR / log `AS_EMPTY_BODY`; session ends |
| Wrong element names (`ProcessUnstructured…` camelCase drift) | Decode miss → END / ignore |
| Using `unstructuredSSNotify_Request` as a menu | Notify is one-shot; no digit collection |
| Final text in Request / `CONTINUE` instead of Response / `END` | Handset gets another menu, not TC-END |
| Ignoring `localId` / `correlationId` on late callback | Bridge cannot match session |
| Echoing only `sessionId` | Fragile push-back; always prefer correlation |
| Confusing `jsessionId` / Cookie with `localId` | NI park vs pull correlation |
| Assuming Digicom is JSON-only | Default wire is **XML**; JSON is opt-in per tenant |
| Expecting hop text inside `originatedUssd` | Dialed stays in `originatedUssd`; hop text is `string=` / `ussdString` when responded |
| Expecting `*875#` in `shortCode` | `shortCode` is the **matched rule** (`*804#`); redirect is `redirectUssd` / `hopUssd` |
| Packing many menus in one `mapMessagesSize>1` body | Only first string applied; use successive turns |

---

## 7. Quick sequence (MAP2MAP)

```
UE *804#
  → GW matches re-route rule (redirect *875#, hopUssd maybe *8775#)
  → Outbound MAP hop to upper HLR/MSC
  → Hop RESULT text | CLOSE empty | REJECT
  → GW POST XML or JSON to AS as_url (identity + attrs above)
  → AS returns CONTINUE (menu 1) or END
  → [optional multi-menu] UE digits → GW pull → AS menu 2… → END
  → GW MAP reply toward UE (or bridge/gated path if AdaptiveTimeout fired)
```

Detail, CDR statuses, and stay-on-call: [`map2map.md`](map2map.md).
