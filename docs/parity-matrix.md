# Classic ussdgateway vs ussdgw-jainslee parity

| Feature | Classic WildFly | ussdgw-jainslee |
|---------|-----------------|----------------|
| MAP MO ProcessUnstructuredSS | ParentSbb | MapUssdParentSbb (live) |
| MAP NI UnstructuredSS-Request | Child / NI | MapNiPushSbb + AccessNiDispatcher |
| Diameter USSD | — | **Live** MO (`DiameterUssdSbb`) + NI (`SendDiameterRequest`) when `ra-diameter` peer ready; STUB_QUEUED when peer down |
| SMPP USSD TLV | — | Lab MO → AS pull; NI plain `submit_sm` when client bound; **local in-tree SMPP RA** |
| SIP / USSI | SipClient/Server | **Live** MO (`SipUssiSbb` / MESSAGE) + NI (`SendMessage`) when `ra-sip-servlet` active; STUB_QUEUED when RA down |
| HTTP pull AS | HttpClientSbb | HttpClientSbb + dual-mode pull body (XML default / JSON) |
| HTTP async callback / NI ingress | HttpServerSbb | HttpServerSbb + `/as/callback` + classic NI sync path `/ussd` |
| gRPC pull | GrpcClientSbb (50ms poll) | GrpcClientSbb callback-only |
| gRPC server | GrpcServerSbb | GrpcServerSbb |
| SRI for NI | SriSbb | SriSbb + MapSendRoutingInfoForSm |
| HLR face (inbound SRI-SM) | — | HlrResponderSbb + FAKE/PROXY_MAP/PROXY_DIAMETER/FAKE_THEN_RESOLVE |
| Adaptive EWMA gate | AdaptiveTimeout | AdaptiveTimeout CDI |
| Virtual Session Bridge | session-bridge + ChildSbb | VirtualSessionBridge (S1/S2 + generation bump on MS input only) |
| CDR dual S1/S2 | USSDCDRState | CdrService + CdrDbFlusher (PG/H2); greenfield phase names |
| In-flight saga | Infinispan VirtualSessionStore | **ProfileFacility `ussdTx`** (`UssdTxProfile`) |
| Admin UI | Jolokia / management WAR | **OTA-shell** disk admin (`AdminPageRenderer` + `app/html/admin/`) + Monitor Hub |
| AS wire format | XmlMAPDialog XML | **Dual-mode:** classic XmlMAPDialog-compatible **XML** (default) + greenfield **JSON**; classic NI sync with **JSESSIONID** in scope |
| Runtime | WildFly 10 Mobicents SLEE | Quarkus + micro-jainslee |
| Dist | WildFly DU | RestLink/Ussdgw fast-jar + `dist-package-script.sh` |
| Jolokia | Classic ops | **Intentional non-port** — Monitor Hub + HTMX admin |

## Classic-parity clarifications

Where classic gets a guarantee from its SBB tree, this gateway has to state it explicitly. These are
the same behavior, not new semantics.

| Guarantee | Classic mechanism | ussdgw-jainslee mechanism |
|-----------|-------------------|---------------------------|
| SRI-SM answer reaches **its own** pending NI push | `HttpServerSbb` creates a private `SriSbb` child per push; the answer arrives on that child's own MAP dialog activity, so an unmatched answer resolves to nothing | `PendingSriRegistry` keyed strictly on the outbound correlation id; a miss returns empty. Plus TTL (`ussd.sri.pending-ttl-ms`) because an explicit map can leak where an activity cannot |
| Upper HLR answer reaches **its own** inbound dialog | Same per-query child correlation | `PendingHlrProxyRegistry` keyed on the outbound correlation; `takeAny()` removed. TTL expiry **aborts** the still-open inbound dialog (`ussd.hlr.proxy.pending-ttl-ms`) |
| **One response per inbound SRI-SM dialog** | Classic has no `FAKE_THEN_RESOLVE`, so the case cannot arise | `Pending#enrichOnly` marks the leg whose inbound dialog `doFake` already closed; the upper answer only refreshes `HlrLocationCache` |
| `networkId` on the HTTP NI leg | `xmlMAPDialog.getNetworkId()` off the AS dialog | `<dialog networkId>` / JSON `networkId` → authenticated tenant → `ussd.http.ni.default-network-id` |
| Internal error on a MAP leg | Dialog ended toward the subscriber rather than left hanging | `MapUssdParentSbb.endDialogOnFailure` — `replyAndEnd` with the hard-fail text on an MS-facing leg, `abort` otherwise, nothing on an already-terminal dialog |

### Deliberate divergence

- **NI `/ussd` authentication.** Classic shipped the ingress with no application-level auth; it was
  reachable only from an internal VLAN. An unauthenticated POST here fires a real
  UnstructuredSS-Request at an arbitrary MSISDN, so the gateway requires a tenant or admin API key by
  default (`ussd.http.ni.auth-required=true`) and rejects in the request's own wire format (classic
  `<dialog>` XML or JSON). A lab opts out explicitly.

## Intentional non-ports

- **Jolokia** management — replaced by Monitor Hub + disk admin shell.
- **OTA fleet / CAP / `/sendota`** — not part of this USSD GW product.

**In scope (not a non-port):** classic XmlMAPDialog-compatible HTTP XML wire (default) alongside greenfield JSON — see [`docs/as-contract/classic-xml.md`](as-contract/classic-xml.md) and [`docs/as-contract/`](as-contract/).

Scenario checklist (behavior): reuse classic `docs/e2e-grpc-ussd-test.md` §8 adaptive/bridge matrix as lab cases — not wire copy-only.

Lab access checklist:

1. MAP MO/NI on shared jSS7 sim (SSN 8 USSD / SSN 6 HLR face).
2. Diameter: enable `ussd.diameter.enabled`, peer CER/CEA → `diameter.live=true`; NI CDR `DIAMETER_SENT`.
3. SIP: enable `ussd.sip.enabled`, RA active → `sip.live=true`; MESSAGE MO + NI `SIP_SENT`.
4. Bridge S1/S2 + EWMA gate (classic matrix §8).
5. CDR dual-leg phases visible in admin CDR page.
6. HTTP AS: tenant/global wire-format XML (default) or JSON; NI sync cookie `JSESSIONID` on `/ussd`.
