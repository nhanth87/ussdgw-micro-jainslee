# AdaptiveTimeout + Bridge lab smoke (pull / push)

Phase-1 ops checklist. Does **not** require Digicom SS7. Digicom live MAP push is blocked until NI `/ussd` stops returning HTTP 500.

JDK unit matrix (run after code changes):

```bash
cd /path/to/ussd-microjainslee
eval "$(mise activate bash)"   # zulu-25
export JAVA_HOME="$(mise where java)"
mvn -Dtest=AdaptiveTimeoutTest,BridgeGateBehaviourTest,BridgeConcurrencyTest,ClassicNiHttpParkTest,HttpServerSbbNiAuthTest,HttpServerSbbNiParkTest,AsPullStateRegistryTest,HttpClientSbbPullStateTest test
```

## Pull (MO → AS) — `tools/as-node`

AS listens on `:8090` (or Digicom `ussdgw-as-node`). Point GW pull URL at `http://127.0.0.1:8090/ussd/pull`.

| Case | Command | Expect |
|------|---------|--------|
| SYNC inside gate | `npm run pull:fast` (`DELAY_MS=0`) | AS reply before AdaptiveTimeout → MAP S1 content; EWMA seeds |
| SYNC after gate (bridge) | `npm run pull:bridge` (`DELAY_MS=8000`) | Gate → wait text / S1 release → late AS → S2 NI push |
| ASYNC_ACK + late content | `npm run pull:async` | Fast ACK does **not** shrink EWMA; content callback delivers |

Also see [tools/as-node/README.md](../as-node/README.md) § AdaptiveTimeout + VirtualSessionBridge.

## Push (classic NI `/ussd`)

Auth header: **`X-USSD-Api-Key`** (not Admin-Key). Local key from lab props or Digicom `build/digicom-secrets-rotated-*.txt` → `ussd.admin.api-key`.

### map.enabled=false (lab echo)

Parked HTTP completes via short lab echo (~50ms) — good smoke without SS7:

```bash
KEY=...   # ussd.admin.api-key
GW=http://127.0.0.1:8088   # or https://100.110.205.176 with -k

curl -sS -D- -o /tmp/ni-body.txt \
  -X POST "${GW}/ussd" \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -H "X-USSD-Api-Key: ${KEY}" \
  --data-binary @- <<'EOF'
<dialog mapMessagesSize="1">
  <unstructuredSSRequest_Request dataCodingScheme="15" string="lab push echo">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </unstructuredSSRequest_Request>
</dialog>
EOF
# Expect HTTP 200 + JSESSIONID + dialog CONTINUE with "lab push echo"
```

Interactive multi-step: `cd tools/as-node && npm run push:ni` (uses `JSESSIONID`).

### map.enabled=true (live AdaptiveTimeout park)

HTTP stays open until MAP progress **or** adaptive gate → ABORT dialog. Needs working MAP + `ss7.live`.

```bash
# Same curl as above; body arrives when MAP continues or gate fires ABORT.
# Digicom: fix NI 500 first, confirm ss7.live=true, then push E.164 MSISDN (ET: 251…).
```

## Pass criteria

- Pull fast: no S1 wait; dialog continues/ends with AS text.
- Pull bridge: wait message then NI with late AS text (one push only).
- Push lab echo: 200 + cookie; no `UnsupportedOperationException` in slee log.
- Push live: park completes from MAP or gate ABORT — never Thread.sleep on SBB; never double HTTP reply.
