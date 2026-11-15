# Multi-level USSD over MAP2MAP — worked example (Java servlet AS with HttpSession)

A complete, tested example of a three-level USSD menu where the gateway first **proxies the
dial to the upper USSD platform (MAP2MAP)** and the Application Server (AS) then drives the
menu, keeping the conversation in a **Java servlet `HttpSession`** (`JSESSIONID`).

Runnable AS: [`tools/as-java-servlet`](../../tools/as-java-servlet) (Jakarta Servlet 6 on
embedded Jetty 12). Wire reference: [`classic-xml.md`](classic-xml.md),
[`map2map-as-xml.md`](map2map-as-xml.md). MAP2MAP call flow and CDRs: [`map2map.md`](map2map.md).

---

## 1. What the subscriber sees

```
*804#
 ┌──────────────────────────────┐   ┌──────────────────────────┐   ┌──────────────────────────┐   ┌───────────────────────────┐
 │ Balance: 50.00 ETB, valid …  │ 2 │ Bundles                  │ 1 │ Buy Daily 50MB for 5 ETB?│ 1 │ Daily 50MB is active.     │
 │ My Ethio                     │──▶│ 1. Daily 50MB - 5 ETB    │──▶│ 1. Yes                   │──▶│ New balance 45 ETB.       │
 │ 1. Check balance             │   │ 2. Weekly 500MB - 25 ETB │   │ 2. No                    │   │ (dialog ends)             │
 │ 2. Buy bundle                │   │ 0. Back                  │   └──────────────────────────┘   └───────────────────────────┘
 │ 3. Exit                      │   └──────────────────────────┘
 └──────────────────────────────┘
   level 1 (upper answer + menu)     level 2                         level 3                        END
```

## 2. The call flow

```
Handset        MSC            ussdgw                        Upper USSD platform        AS (servlet)
   │ *804#      │               │                                   │                       │
   │───────────▶│──── MAP processUnstructuredSS-Request ──▶│        │                       │
   │            │               │ rule *804# = RE_ROUTE, redirect *875#                     │
   │            │               │── MAP2MAP hop: processUnstructuredSS-Request "*875#" ──▶│ │
   │            │               │◀──────────── "Balance: 50.00 ETB, valid to 30/11" ──────│ │
   │            │               │── HTTP POST turn 1 (hlrResult="responded") ─────────────────────▶│ getSession(true)
   │            │               │◀───────────────────────────── menu + Set-Cookie: JSESSIONID ─────│
   │◀───────────│◀── menu ──────│                                                                  │
   │ "2"        │               │                                                                  │
   │───────────▶│──────────────▶│── HTTP POST turn 2 "2" + Cookie: JSESSIONID ─────────────────────▶│ getSession(false)
   │◀───────────│◀── menu ──────│◀──────────────────────────────────────────────── Bundles menu ────│
   │ "1"        │               │── turn 3 "1" + Cookie ────────────────────────────────────────────▶│
   │◀───────────│◀── menu ──────│◀─────────────────────────────────────────── Confirm menu ─────────│
   │ "1"        │               │── turn 4 "1" + Cookie ────────────────────────────────────────────▶│ session.invalidate()
   │◀───────────│◀── END ───────│◀──────────────────────────────────── final text (END) ────────────│
```

- **One MAP dialog** with the handset for the whole conversation. **One** MAP2MAP hop, at the
  dial; menu digits go to the AS, never to the upper platform (locked contract,
  [`map2map-as-xml.md` §4d](map2map-as-xml.md)).
- **One HTTP POST per turn.** ussdgw returns the AS's cookies (`JSESSIONID`) on every later POST
  of the **same** USSD dialog and on no other dialog — exactly like the classic ussdgateway. The
  servlet container therefore maps each USSD dialog to one `HttpSession`.
- AdaptiveTimeout + virtual bridge cover a slow upper platform or a slow AS: if the answer is not
  back within the budget, the handset gets the "please wait" text and the late answer is
  delivered afterwards ([`map2map.md`](map2map.md) § Bridge).

## 3. The HTTP exchange, turn by turn

All bodies are `Content-Type: text/xml;charset=UTF-8`, POSTed to the rule's AS URL.

### Turn 1 — the dial, after the MAP2MAP hop

ussdgw → AS (no cookie yet). `hlrResult="responded"` and `string=` = **the upper platform's text**.
`originatedUssd` is what the subscriber dialed, `hopUssd` what was sent upward.

```xml
<dialog appCntx="networkUnstructuredSsContext" localId="e37caa26-9d16-4239-a2ff-deff0687da8d"
        shortCode="*804#" originatedUssd="*804#" codeKind="SHORT"
        redirectUssd="*875#" hopUssd="*875#" hlrResult="responded" networkId="0">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="Balance: 50.00 ETB, valid to 30/11">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </processUnstructuredSSRequest_Request>
</dialog>
```

AS → ussdgw: a menu (`unstructuredSSRequest_Request` = keep the dialog open) and the new session:

```
HTTP/1.1 200 OK
Set-Cookie: JSESSIONID=node01y20hn424f1dsycl1t6sltb650.node0; Path=/
Content-Type: text/xml;charset=UTF-8
```
```xml
<dialog mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="Balance: 50.00 ETB, valid to 30/11&#10;My Ethio&#10;1. Check balance&#10;2. Buy bundle&#10;3. Exit"/>
</dialog>
```

### Turn 2 — the subscriber chose `2`

ussdgw → AS, **with** `Cookie: JSESSIONID=node01y20hn424f1dsycl1t6sltb650.node0`. `string=` is the
digit only; the same `localId` for the whole dialog.

```xml
<dialog appCntx="networkUnstructuredSsContext" localId="e37caa26-9d16-4239-a2ff-deff0687da8d"
        originatedUssd="*804#" networkId="0">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="2">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </unstructuredSSRequest_Request>
</dialog>
```

AS → ussdgw (session level `MAIN` → `BUNDLES`):

```xml
<dialog mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15"
      string="Bundles&#10;1. Daily 50MB - 5 ETB&#10;2. Weekly 500MB - 25 ETB&#10;0. Back"/>
</dialog>
```

### Turn 3 — `1` → confirmation menu

```xml
<dialog mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="Buy Daily 50MB for 5 ETB?&#10;1. Yes&#10;2. No"/>
</dialog>
```

### Turn 4 — `1` (yes) → final text

`processUnstructuredSSRequest_Response` = **END**: ussdgw sends it and closes the MAP dialog. The AS
calls `session.invalidate()`; a later POST with the old cookie gets "Session expired".

```xml
<dialog mapMessagesSize="1">
  <processUnstructuredSSRequest_Response dataCodingScheme="15" string="Daily 50MB is active. New balance 45 ETB."/>
</dialog>
```

### When the upper platform did not answer

| `hlrResult` | `string=` on turn 1 | What the AS should do |
|---|---|---|
| `responded` | the upper platform's text | use it (here: first line of the menu) |
| `none` | empty | its **own** text ("Upper service not available now.") — never echo the empty string |
| `reject` | `hlr reject` | its own text — never echo the sentinel |
| `pending` | `hlr pending` | its own "being processed" text |
| *(absent)* | the dial (`*804#`) | plain MO rule without re-route: normal menu |

## 4. AS rules in one table

| AS answers | Meaning on the handset |
|---|---|
| `<unstructuredSSRequest_Request string="…"/>` | show the menu, wait for a digit (next level) |
| `<processUnstructuredSSRequest_Response string="…"/>` | final text, dialog closed |
| `<dialog mapMessagesSize="0" mapUserAbortChoice="isUserSpecificReason"/>` | abort the dialog |

- New line in a menu: `&#10;`. One screen: ≤ 182 GSM-7 characters, ≤ 80 characters in UCS-2
  (e.g. Amharic) — the gateway picks the alphabet from the text.
- Session key: the servlet `HttpSession` (`JSESSIONID`). For logs and the late `/as/callback`
  use `localId` (= gateway correlation id). Never key on `hopUssd` or MSISDN — one subscriber can
  have two dialogs.
- A request must be answered within the gateway's AS timeout (`ussd.http.client.request-timeout-ms`);
  past the AdaptiveTimeout budget the handset already got the bridge "please wait" text.

## 5. The servlet (core of `MyEthioMenuServlet`)

```java
@Override
protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    UssdTurn turn = UssdTurn.parse(req.getInputStream());      // dial or digit, hlrResult, localId, msisdn
    String reply;
    if (turn.isFirst()) {                                       // processUnstructuredSSRequest_Request
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate();
        HttpSession session = req.getSession(true);             // container sets JSESSIONID
        session.setAttribute("msisdn", turn.msisdn());
        session.setAttribute("correlationId", turn.correlationId());
        session.setAttribute("menuLevel", "MAIN");
        reply = menu(firstScreen(turn) + MAIN_MENU);            // upper text only if hlrResult=responded
    } else {                                                    // unstructuredSSRequest_Request = a digit
        HttpSession session = req.getSession(false);            // found through the cookie ussdgw sent back
        reply = session == null ? end("Session expired. Please dial again.")
                                : nextStep(session, turn.text());   // MAIN → BUNDLES → CONFIRM → END
    }
    resp.setContentType("text/xml;charset=UTF-8");
    resp.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));
}
```

`nextStep` moves `menuLevel` through `MAIN → BUNDLES → CONFIRM`, returns `menu(...)` to keep the
dialog open, and `finish(session, text)` = `session.invalidate()` + `end(text)` for the last screen.
Full source: [`MyEthioMenuServlet.java`](../../tools/as-java-servlet/src/main/java/demo/ussd/MyEthioMenuServlet.java).

## 6. Run it and point a short code at it

```bash
cd tools/as-java-servlet
mvn -q package                          # → target/as-java-servlet.jar (one runnable jar)
java -jar target/as-java-servlet.jar 8090
# Demo AS listening on http://0.0.0.0:8090/ussd/pull
```

Admin UI → **Routing** → new rule:

| Field | Value |
|---|---|
| Short code | a **lab** code, e.g. `*999#` (do not repoint a live code) |
| Type | `RE_ROUTE` (MAP2MAP); AS plane `HTTP` |
| Redirect USSD | the upper platform's code, e.g. `*875#` |
| Hop dest GT / SSN | blank = HLR Face `ussd.hlr.upper-gt` + SSN 6, or the upper platform's GT |
| AS URL | `http://<as-host>:8090/ussd/pull` |
| HTTP AS wire | `XML` |

Without MAP2MAP (plain multi-level menu) use Type `HTTP`: turn 1 then has no `hlrResult` and the
servlet shows the menu only.

## 7. Test it

**Without a handset** — replay the gateway's POSTs (what was run to validate this page):

```bash
U=http://127.0.0.1:8090/ussd/pull; J=/tmp/ussd.jar; rm -f $J
post() { curl -s -b $J -c $J -H 'Content-Type: text/xml' --data "$1" $U; echo; }
post '<dialog localId="c1" hlrResult="responded" originatedUssd="*804#" hopUssd="*875#"><processUnstructuredSSRequest_Request dataCodingScheme="15" string="Balance: 50.00 ETB, valid to 30/11"><msisdn number="251911000001"/></processUnstructuredSSRequest_Request></dialog>'
post '<dialog localId="c1"><unstructuredSSRequest_Request dataCodingScheme="15" string="2"><msisdn number="251911000001"/></unstructuredSSRequest_Request></dialog>'
post '<dialog localId="c1"><unstructuredSSRequest_Request dataCodingScheme="15" string="1"><msisdn number="251911000001"/></unstructuredSSRequest_Request></dialog>'
post '<dialog localId="c1"><unstructuredSSRequest_Request dataCodingScheme="15" string="1"><msisdn number="251911000001"/></unstructuredSSRequest_Request></dialog>'
```

Expected: upper text + main menu (with `Set-Cookie: JSESSIONID`), Bundles menu, confirmation menu,
END "Daily 50MB is active. New balance 45 ETB.". One more POST with the same cookie → "Session
expired" (the session was invalidated at END). With `hlrResult="none"` on turn 1 → "Upper service
not available now." + menu.

**With a handset** — dial the lab code and check:

| Check | Where | Pass |
|---|---|---|
| Hop went up | CDR `/admin/cdr` | `MAP2MAP_HOP_START` → `MAP2MAP_USSD_SENT` → `MAP2MAP_HOP_CLOSE` (amber) |
| AS turns | CDR | `CONTINUE` ×3 with `asUssd=` snippets, then `END` |
| Same AS session | AS log | one `JSESSIONID` for all four turns of the dialog |
| HTTP plane | `/admin/http` | `sent` / `answered` +4 on the HTTP RA entities, `failed` 0 |
