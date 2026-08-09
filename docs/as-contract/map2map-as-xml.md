# Digicom-ET USSDGW — AS HTTP XML contract (MO + MAP2MAP)

Application-server integrator guide for the **classic XmlMAPDialog** HTTP wire that Digicom-ET USSDGW
uses by default when pulling an AS. Dual-mode exists (XML default + optional JSON per tenant); this
document focuses on **XML**, which Digicom production AS endpoints expect.

Canonical peers:

| Doc | Role |
|-----|------|
| [`classic-xml.md`](classic-xml.md) | Full classic `<dialog>` grammar + NI park |
| [`map2map.md`](map2map.md) | MAP2MAP Case 2 hop / AdaptiveTimeout / CDR |
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
| `localId` | GW | Correlation / push-back key (echo this) |
| `sessionId` | GW | Logical virtual session id (not the store key) |
| `virtualBridgeId` | GW | Bridge arm id when AdaptiveTimeout/bridge armed (often = `localId`) |
| `adaptiveTimeoutMs` | GW | Live gate budget ms for this session |
| `asMode` | GW | `SYNC` or `BRIDGE` |
| `networkId` | GW | SCCP / tenant network id (Digicom live often `0`) |
| `shortCode` | GW | Matched routing-rule key (e.g. `*804#`) |
| `originatedUssd` | GW | Full UE dialed string (e.g. `*804#` or long mark dial) |
| `codeKind` | GW | `SHORT` or `LONG` |
| `redirectUssd` | GW (MAP2MAP) | Rule redirect / re-route short code (e.g. `*875#`) |
| `hopUssd` | GW (MAP2MAP) | Resolved hop USSD actually sent to upper HLR/MSC (may be long `*875*…#`) |
| `hlrResult` | GW (MAP2MAP) | Hop outcome flag — see table below |
| `appCntx` | GW | Usually `networkUnstructuredSsContext` |
| Child `string=` | GW or AS | USSD text on the MAP message element |
| Child `<msisdn …/>` | GW | Subscriber MSISDN |

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

GW maps this to MAP `unstructuredSS-Request` toward the UE and CDR phase/status **CONTINUE**.

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
      string="Your balance is 12.50 ETB">
    <msisdn nai="international_number" npi="ISDN" number="251911230398"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

- `string=` = **upper HLR/MSC USSD text** (Amharic/UTF-8 allowed; XML-escape `<&"`).
- `originatedUssd` = what the subscriber dialed.
- `redirectUssd` = routing-rule redirect short code.
- `hopUssd` = code actually sent on the outbound hop (short or long).

AS then answers with CONTINUE menu or final Response as in §2 / §3.

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
<dialog mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="Service temporarily unavailable. Try again later."/>
</dialog>
```

### 4c. Hop REJECT (`hlrResult=reject`)

```xml
<dialog … shortCode="*804#" originatedUssd="*804#" redirectUssd="*875#" hopUssd="*875#"
        hlrResult="reject" networkId="0">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="hlr reject">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

---

## 5. What the AS should implement (checklist)

1. Accept `POST` + `Content-Type: text/xml; charset=utf-8`.
2. Parse root `<dialog>`; read `localId`, `msisdn`, `string=`, and MAP2MAP attrs when present.
3. If `hlrResult="responded"` → treat `string=` as upper-HLR content for your business logic.
4. If `hlrResult="none"` / `"reject"` → handle sentinel `hlr none` / `hlr reject`; use
   `redirectUssd` / `hopUssd` / `originatedUssd` for routing context.
5. Reply 200 with CONTINUE (`unstructuredSSRequest_Request`) or END
   (`processUnstructuredSSRequest_Response` / empty dialog).
6. Never confuse **Notify** (`unstructuredSSNotify_Request`) with an interactive menu — menus
   use **Request**.

---

## 6. Common mistakes

| Mistake | Symptom |
|---------|---------|
| HTTP 200 + empty body | CDR / log `AS_EMPTY_BODY`; session ends |
| Wrong element names (`ProcessUnstructured…` camelCase drift) | Decode miss → END / ignore |
| Using `unstructuredSSNotify_Request` as a menu | Notify is one-shot; no digit collection |
| Ignoring `localId` on late callback | Bridge cannot match session |
| Assuming Digicom is JSON-only | Default wire is **XML**; JSON is opt-in per tenant |
| Expecting hop text inside `originatedUssd` | Dialed stays in `originatedUssd`; hop text is `string=` when `hlrResult=responded` |
| Expecting `*875#` in `shortCode` | `shortCode` is the **matched rule** (`*804#`); redirect is `redirectUssd` / `hopUssd` |

---

## 7. Quick sequence (MAP2MAP)

```
UE *804#
  → GW matches re-route rule (redirect *875#, hopUssd maybe *8775#)
  → Outbound MAP hop to upper HLR/MSC
  → Hop RESULT text | CLOSE empty | REJECT
  → GW POST XML to AS as_url (attrs above)
  → AS returns CONTINUE or END XML
  → GW MAP reply toward UE (or bridge/gated path if AdaptiveTimeout fired)
```

Detail, CDR statuses, and stay-on-call: [`map2map.md`](map2map.md).
