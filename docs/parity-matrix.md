# Classic ussdgateway vs ussdgw-jainslee parity

| Feature | Classic WildFly | ussdgw-jainslee |
|---------|-----------------|----------------|
| MAP MO ProcessUnstructuredSS | ParentSbb | MapUssdParentSbb (live) |
| MAP NI UnstructuredSS-Request | Child / NI | MapNiPushSbb + AccessNiDispatcher |
| Diameter USSD | — | **Live** MO (`DiameterUssdSbb`) + NI (`SendDiameterRequest`) when `ra-diameter` peer ready; STUB_QUEUED when peer down |
| SMPP USSD TLV | — | Lab MO → AS pull; NI plain `submit_sm` when client bound; **local in-tree SMPP RA** |
| SIP / USSI | SipClient/Server | **Live** MO (`SipUssiSbb` / MESSAGE) + NI (`SendMessage`) when `ra-sip-servlet` active; STUB_QUEUED when RA down |
| HTTP pull AS | HttpClientSbb | HttpClientSbb + `JsonPostRequest` (raw body R/R) |
| HTTP async callback / NI ingress | HttpServerSbb | HttpServerSbb + /as/callback |
| gRPC pull | GrpcClientSbb (50ms poll) | GrpcClientSbb callback-only |
| gRPC server | GrpcServerSbb | GrpcServerSbb |
| SRI for NI | SriSbb | SriSbb + MapSendRoutingInfoForSm |
| HLR face (inbound SRI-SM) | — | HlrResponderSbb + FAKE/PROXY_MAP/PROXY_DIAMETER/FAKE_THEN_RESOLVE |
| Adaptive EWMA gate | AdaptiveTimeout | AdaptiveTimeout CDI |
| Virtual Session Bridge | session-bridge + ChildSbb | VirtualSessionBridge (S1/S2 + generation bump on MS input only) |
| CDR dual S1/S2 | USSDCDRState | CdrService + CdrDbFlusher (PG/H2); greenfield phase names |
| In-flight saga | Infinispan VirtualSessionStore | **ProfileFacility `ussdTx`** (`UssdTxProfile`) |
| Admin UI | Jolokia / management WAR | **OTA-shell** disk admin (`AdminPageRenderer` + `app/html/admin/`) + Monitor Hub |
| AS wire format | XmlMAPDialog XML | Greenfield JSON/proto — **intentional non-port** |
| Runtime | WildFly 10 Mobicents SLEE | Quarkus + micro-jainslee |
| Dist | WildFly DU | RestLink/Ussdgw fast-jar + `dist-package-script.sh` |
| Jolokia | Classic ops | **Intentional non-port** — Monitor Hub + HTMX admin |

## Intentional non-ports

- **XmlMAPDialog** AS wire — greenfield JSON/proto only ([`docs/as-contract/`](as-contract/)).
- **Jolokia** management — replaced by Monitor Hub + disk admin shell.
- **OTA fleet / CAP / `/sendota`** — not part of this USSD GW product.

Scenario checklist (behavior): reuse classic `docs/e2e-grpc-ussd-test.md` §8 adaptive/bridge matrix as lab cases — not wire copy.

Lab access checklist:

1. MAP MO/NI on shared jSS7 sim (SSN 8 USSD / SSN 6 HLR face).
2. Diameter: enable `ussd.diameter.enabled`, peer CER/CEA → `diameter.live=true`; NI CDR `DIAMETER_SENT`.
3. SIP: enable `ussd.sip.enabled`, RA active → `sip.live=true`; MESSAGE MO + NI `SIP_SENT`.
4. Bridge S1/S2 + EWMA gate (classic matrix §8).
5. CDR dual-leg phases visible in admin CDR page.
