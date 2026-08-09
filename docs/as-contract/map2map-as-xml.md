# Digicom-ET USSDGW — AS HTTP XML contract (MO + MAP2MAP)

Application-server integrator guide for the **classic XmlMAPDialog** HTTP wire that Digicom-ET USSDGW
uses by default when pulling an AS. Dual-mode exists (XML default + optional JSON per tenant); this
document focuses on **XML**, which Digicom production AS endpoints expect.

Canonical peers:

| Doc | Role |
|-----|------|
| [`classic-xml.md`](classic-xml.md) | Full classic `<dialog>` grammar + NI park |
| [`map2map.md`](map2map.md) | MAP2MAP Case 2 hop / AdaptiveTimeout / CDR |
| [`ussd-3gpp-notes.md`](ussd-3gpp-notes.md) | Request vs Notify (3GPP) |
| [`openapi-as.yaml`](openapi-as.yaml) | Greenfield JSON schemas |
| Codec | `ClassicDialogXmlCodec` (source of truth for attrs) |

---

## HTTP basics (Digicom)

| Item | Value |
|------|--------|
| Direction | **GW → AS** HTTP `POST` (AS pull) |
| Example URL | Short-code rule `as_url` (e.g. `https://bph.vas.et/v2/ussd`) |
| Content-Type | `text/xml; charset=utf-8` |
| Body | Raw `<dialog>…</dialog>` — **not** a JSON envelope |
| Success | HTTP **200** + XML body (or intentionally empty → GW treats as END / `AS_EMPTY_BODY`) |
| Push-back key | Echo dialog `localId` (= correlation id) on `/as/callback` if using late push |

AS must answer quickly enough for the AdaptiveTimeout gate on the session
(`adaptiveTimeoutMs` on the pull). Empty HTTP 200 body is **not** a menu — it ends the dialog.

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
       string= = hop USSD text | "hlr none" | "hlr reject"
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
| Hop **no** response (empty CLOSE / no RESULT text) | **`none`** | **`hlr none`** |
| Hop Dialog **REJECT** | **`reject`** | **`hlr reject`** |
| Early gated pull while hop in flight | **`pending`** | **`hlr pending`** |
| Hop **did** respond with USSD text | **`responded`** | **That upper HLR/MSC USSD text** |

Non-MAP2MAP MO pulls leave `hlrResult` / `redirectUssd` / `hopUssd` unset.

CDR note: GW status `CONTINUE` means the **AS response** was a menu
(`unstructuredSSRequest_Request` with non-empty text) — not “hop continued”.

---

## 1. Normal MO pull (no MAP2MAP) — GW → AS

UE dials a short code that routes straight to HTTP AS (no re-route hop).

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

Generation 0 → `processUnstructuredSSRequest_Request`. Later user digits →
`unstructuredSSRequest_Request` (continue pull).

---

## 2. AS → GW CONTINUE menu (what CDR `CONTINUE` means)

AS wants the handset to show a menu and wait for digits. Return HTTP 200 with:

```xml
<dialog mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="1. Balance&#10;2. Topup&#10;0. Exit"/>
</dialog>
```

GW maps this to MAP **`unstructuredSS-Request`** toward the UE (interactive — UE may send
digits). CDR phase/status **CONTINUE**.

**Never** use `unstructuredSSNotify_Request` for a menu — Notify is one-shot (no digit
collection). See [`ussd-3gpp-notes.md`](ussd-3gpp-notes.md) and [`classic-xml.md`](classic-xml.md).

Optional RestLink attrs on the response (echo identity if useful):

```xml
<dialog localId="corr-mo-1" sessionId="vs-mo-1" virtualBridgeId="corr-mo-1"
        mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="Choose:&#10;1 OK&#10;2 Cancel"/>
</dialog>
```

---

## 3. AS → GW final (END)

End the MO dialog with final text (MAP `processUnstructuredSS-Response` / TC-END path):

```xml
<dialog mapMessagesSize="1">
  <processUnstructuredSSRequest_Response dataCodingScheme="15"
      string="Thank you. Goodbye."/>
</dialog>
```

Empty end (no menu text) — also END:

```xml
<dialog mapMessagesSize="0"/>
```

or empty string on a Response / Request element. **Do not** return HTTP 200 with a completely
empty body if you intended a menu — that logs `AS_EMPTY_BODY` and ends the session.

Abort:

```xml
<dialog mapMessagesSize="0" mapUserAbortChoice="isUserSpecificReason"/>
```

---

## 4. MAP2MAP after hop — GW → AS

Rule example: UE dials `*804#`, re-route redirect `*875#`, hop may resolve to a long code
(e.g. `*8775#` after mark/chain fold). Digicom POSTs to the rule `as_url` after the upper hop
settles (or with `hlr none` / `hlr reject` when the hop had no usable text).

### 4a. Success — hop RESULT text (`hlrResult=responded`)

Upper HLR/MSC returned USSD text (any alphabet — e.g. UCS-2 Amharic). That text is the
**only** content of child `string=`:

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

- `string=` = **upper HLR/MSC USSD text** (Amharic/UTF-8 allowed; XML-escape `<&"`).
- `originatedUssd` = what the subscriber dialed.
- `redirectUssd` = routing-rule redirect short code.
- `hopUssd` = code actually sent on the outbound hop (short or long).
- Identity: `localId` = correlation / `ussdTx`; `sessionId` = virtual session; `virtualBridgeId` ≈ `localId`.

AS then answers with a **single** CONTINUE menu (§2), a **multi-menu** flow (§4d), or final
Response (§3).

### 4b. Hop empty / CLOSE — no RESULT text (`hlrResult=none`)

When the peer closes the hop dialog without a USSD RESULT (empty TC-END / NOTICE+CLOSE),
GW still pulls the AS so the application can decide the UE message:

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
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="hlr none">
    <msisdn nai="international_number" npi="ISDN" number="251911230398"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

Honest contract: `string="hlr none"` means **no hop USSD text was available** — not a
handset-visible HLR phrase. Re-route codes remain on the dialog so the AS can still key off
`*875#` / `hopUssd` even when the hop body is empty.

Example AS CONTINUE after `hlr none` (illustrative; body length varies):

```xml
<dialog mapMessagesSize="1" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="Service temporarily unavailable. Try again later."/>
</dialog>
```

### 4c. Hop REJECT (`hlrResult=reject`)

```xml
<dialog … shortCode="*804#" originatedUssd="*804#" redirectUssd="*875#" hopUssd="*875#"
        hlrResult="reject" networkId="0"
        localId="corr-…" sessionId="vs-…" virtualBridgeId="corr-…">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="hlr reject">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

### 4d. Multi-menu on the return path (AS → GW → UE)

After the hop pull (§4a–4c), the AS may drive an **interactive multi-menu** toward the UE.
This is the same CONTINUE machine as ordinary MO (§2), including MAP2MAP.

#### Supported model (preferred) — successive HTTP round-trips

Each AS HTTP response carries **one** interactive menu. After the UE presses digits, GW pulls
the AS again with those digits; the AS returns the next menu or END.

**Turn 1 — AS → GW (first menu after hop):**

```xml
<dialog mapMessagesSize="1"
        localId="e37caa26-9d16-4239-a2ff-deff0687da8d"
        sessionId="4203367b-c862-4307-81a7-3fbaa50b2afd"
        virtualBridgeId="e37caa26-9d16-4239-a2ff-deff0687da8d">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="1. Balance&#10;2. Data&#10;3. Help&#10;0. Exit"/>
</dialog>
```

GW → MAP **`unstructuredSS-Request`** to the UE (stay-on-call). CDR **CONTINUE**.

**Turn 2 — UE digits → GW → AS pull** (generation &gt; 0; child is continue Request with digit
string — see §1). AS replies with the next menu:

```xml
<dialog mapMessagesSize="1" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="Balance menu&#10;1. Main&#10;2. Bonus&#10;0. Back"/>
</dialog>
```

**Turn 3 — final:**

```xml
<dialog mapMessagesSize="1" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
  <processUnstructuredSSRequest_Response dataCodingScheme="15"
      string="Thank you."/>
</dialog>
```

`localId` stays the **same** for the whole MO / MAP2MAP session.

#### Optional — `mapMessagesSize` &gt; 1 in one AS body

Classic XmlMAPDialog can list more than one child MAP message. This GW decodes the **first**
meaningful `unstructuredSSRequest_Request` / Response string and applies **one** MAP action
toward the UE (same as a single-menu CONTINUE/END). Extra sibling Request elements in the
**same** HTTP body are **not** queued as later menus.

```xml
<!-- Not a multi-step queue: only the first Request string is applied -->
<dialog mapMessagesSize="2" localId="corr-mm">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="Menu 1&#10;1 Next"/>
  <unstructuredSSRequest_Request dataCodingScheme="15" string="would-be-ignored"/>
</dialog>
```

For true multi-step menus, use **successive** responses after UE digits (§4d preferred model).

#### How GW maps AS XML → MAP toward UE

| AS child element | MAP toward UE | Interactive? |
|------------------|---------------|--------------|
| `unstructuredSSRequest_Request` + non-empty `string=` | `unstructuredSS-Request` | **Yes** — wait for digits |
| `processUnstructuredSSRequest_Response` / empty dialog / `mapMessagesSize="0"` | Final Response / TC-END | No |
| `unstructuredSSNotify_Request` | `unstructuredSS-Notify` | **No** — one-shot; do not use as menu |
| Abort attrs / `mapUserAbortChoice` | MAP abort | No |

Aligns with [`classic-xml.md`](classic-xml.md) and [`ussd-3gpp-notes.md`](ussd-3gpp-notes.md).

---

## 5. What the AS should implement (checklist)

1. Accept `POST` + `Content-Type: text/xml; charset=utf-8`.
2. Parse root `<dialog>`; read `localId`, `sessionId`, `virtualBridgeId`, `msisdn`, `string=`,
   and MAP2MAP attrs when present. Persist **`localId`** for late push-back.
3. If `hlrResult="responded"` → treat `string=` as upper-HLR content for your business logic.
4. If `hlrResult="none"` / `"reject"` → handle sentinel `hlr none` / `hlr reject`; use
   `redirectUssd` / `hopUssd` / `originatedUssd` for routing context.
5. Reply 200 with CONTINUE (`unstructuredSSRequest_Request`) or END
   (`processUnstructuredSSRequest_Response` / empty dialog). Multi-menu = successive CONTINUEs
   after each UE digit pull (§4d).
6. Never confuse **Notify** (`unstructuredSSNotify_Request`) with an interactive menu — menus
   use **Request**.
7. On `/as/callback`, echo **`localId`** (and ideally `virtualBridgeId`).

---

## 6. Common mistakes

| Mistake | Symptom |
|---------|---------|
| HTTP 200 + empty body | CDR / log `AS_EMPTY_BODY`; session ends |
| Wrong element names (`ProcessUnstructured…` camelCase drift) | Decode miss → END / ignore |
| Using `unstructuredSSNotify_Request` as a menu | Notify is one-shot; no digit collection |
| Ignoring `localId` on late callback | Bridge cannot match session |
| Echoing only `sessionId` | Fragile push-back; always prefer `localId` |
| Confusing `jsessionId` / Cookie with `localId` | NI park vs pull correlation |
| Assuming Digicom is JSON-only | Default wire is **XML**; JSON is opt-in per tenant |
| Expecting hop text inside `originatedUssd` | Dialed stays in `originatedUssd`; hop text is `string=` when `hlrResult=responded` |
| Expecting `*875#` in `shortCode` | `shortCode` is the **matched rule** (`*804#`); redirect is `redirectUssd` / `hopUssd` |
| Packing many menus in one `mapMessagesSize>1` body | Only first string applied; use successive turns |

---

## 7. Quick sequence (MAP2MAP)

```
UE *804#
  → GW matches re-route rule (redirect *875#, hopUssd maybe *8775#)
  → Outbound MAP hop to upper HLR/MSC
  → Hop RESULT text | CLOSE empty | REJECT
  → GW POST XML to AS as_url (localId/sessionId/virtualBridgeId + attrs above)
  → AS returns CONTINUE (menu 1) or END XML
  → [optional multi-menu] UE digits → GW pull → AS menu 2… → END
  → GW MAP reply toward UE (or bridge/gated path if AdaptiveTimeout fired)
```

Detail, CDR statuses, and stay-on-call: [`map2map.md`](map2map.md).
