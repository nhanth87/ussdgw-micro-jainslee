# Classic ussdgateway vs ussdgw-jainslee parity

| Feature | Classic WildFly | ussdgw-jainslee |
|---------|-----------------|----------------|
| MAP MO ProcessUnstructuredSS | ParentSbb | MapUssdParentSbb (live) |
| MAP NI UnstructuredSS-Request | Child / NI | MapNiPushSbb + AccessNiDispatcher |
| Diameter USSD | — | Lab MO → AS pull; NI STUB_QUEUED; **RA = micro-jainslee `ra-diameter`** (not in this app) |
| SMPP USSD TLV | — | Lab MO → AS pull; NI plain `submit_sm` when client bound; **local in-tree SMPP RA** |
| SIP / USSI | SipClient/Server | Lab MO → AS pull; NI STUB_QUEUED; **RA = micro-jainslee `ra-sip-servlet`** |
| HTTP pull AS | HttpClientSbb | HttpClientSbb + `JsonPostRequest` (raw body R/R) |
| HTTP async callback / NI ingress | HttpServerSbb | HttpServerSbb + /as/callback |
| gRPC pull | GrpcClientSbb (50ms poll) | GrpcClientSbb callback-only |
| gRPC server | GrpcServerSbb | GrpcServerSbb |
| SRI for NI | SriSbb | SriSbb + MapSendRoutingInfoForSm |
| HLR face (inbound SRI-SM) | — | HlrResponderSbb + FAKE/PROXY_MAP/PROXY_DIAMETER/FAKE_THEN_RESOLVE |
| Adaptive EWMA gate | AdaptiveTimeout | AdaptiveTimeout CDI |
| Virtual Session Bridge | session-bridge + ChildSbb | VirtualSessionBridge |
| CDR dual S1/S2 | USSDCDRState | CdrService + CdrDbFlusher (PG/H2) |
| In-flight saga | Infinispan VirtualSessionStore | **ProfileFacility `ussdTx`** (`UssdTxProfile`) |
| Admin UI | Jolokia / management WAR | HTMX admin + **jainslee-monitor** hub (SS7/SMPP/HTTP; OTA pattern) |
| AS wire format | XmlMAPDialog XML | Greenfield JSON/proto |
| Runtime | WildFly 10 Mobicents SLEE | Quarkus + micro-jainslee |
| Dist | WildFly DU | RestLink/Ussdgw fast-jar + `dist-package-script.sh` |

Scenario checklist (behavior): reuse classic `docs/e2e-grpc-ussd-test.md` §8 adaptive/bridge matrix as lab cases — not wire copy.
