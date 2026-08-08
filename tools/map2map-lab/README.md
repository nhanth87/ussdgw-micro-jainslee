# MAP2MAP lab (SP prove: `*875#` → GT `251971200201` SSN `6`)

End-to-end lab for Digicom-ET USSDGW MAP2MAP re-route with **fixed hop dest**
(service-provider peer style). Does **not** require live HLR SRI when `hop_dest_gt` is set.

## Parameters

| Knob | Value |
|------|-------|
| Redirect USSD | `*875#` (`map2map_gt`) |
| Hop CalledParty GT | `251971200201` (`hop_dest_gt`) |
| Hop CalledParty SSN | `6` (`hop_dest_ssn`, HLR) |
| Test AS | `tools/as-node` `npm run pull:map2map` → `:8090/ussd/pull` |
| MO dial | `*804#` (or safer lab `*8804#` — see `seed-lab-rule.sql`) |

Flow:

```
UE dial *804# (reroute_enable + map2map_gt=*875# + hop_dest_gt)
  → bridge armed at hop ingress
  → UnstructuredSS-Request CalledParty=251971200201/SSN6 string=*875#
  → peer UnstructuredSS-Response (hop text)
  → HTTP AS pull (enrich: msisdn, originatedUssd, shortCode, codeKind, ussdString=hop)
  → UE
```

When `hop_dest_gt` is **blank**, hop uses FAKE MSC or PROXY SRI→MSC as before.

## Digicom status

**Do not mutate live Digicom `*804#` without asking.** Prefer:

1. Local/lab H2 with `seed-lab-rule.sql`, or
2. Dedicated Digicom lab short-code, or
3. Operator-approved surgical `UPDATE` (backup CSV first; never wipe `as_url`).

## Run (local)

```bash
# 0) JDK 25
eval "$(mise activate bash)"
export JAVA_HOME="$(mise where java)"

# 1) Package + start GW
./build/package-dist.sh
./dist/run.sh

# 2) Test AS (MAP2MAP enrich assert + gated notify ACK)
cd tools/as-node && npm install
npm run pull:map2map
# slow AS / gate: npm run pull:map2map:bridge

# 3) Seed routing (admin UI or SQL)
# Admin → Routing: Re-route=true, redirect=*875#, hopDestGt=251971200201, hopDestSsn=6,
#   asUrl=http://127.0.0.1:8090/ussd/pull
# Or: apply tools/map2map-lab/seed-lab-rule.sql against local DB

# 4) SS7 sim + hop stub (auto-reply hop text when peer gets UnstructuredSS-Request)
./tools/ss7-simulator/run.sh sim
./tools/ss7-simulator/run.sh cli
ussd> connect
ussd> hop MAP2MAP hop UserInfo   # AutoResponseString = hop text; enable auto on USS Request
ussd> dial *804#
# Expect network menu from AS after hop; AS log shows originatedUssd=*804# ussd=*875# hop text

# One-shot:
./tools/ss7-simulator/run.sh cli dial '*804#' --hop 'MAP2MAP hop UserInfo' --msisdn 251911000001
```

### Without live MAP (unit / HTTP smoke)

```bash
# JVM tests (Java 25)
mvn -Dtest=Map2MapSbbTest,ShortCodeRuleMap2MapTest,Map2MapBridgeArmTest,PendingMap2MapRegistryTest test

# Curl Test AS with classic enrich (as GW would after hop)
curl -sS -X POST 'http://127.0.0.1:8090/ussd/pull' \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<dialog appCntx="networkUnstructuredSsContext" localId="corr-1"
        sessionId="vs-1" virtualBridgeId="corr-1" adaptiveTimeoutMs="4200"
        asMode="BRIDGE" shortCode="*804#" originatedUssd="*804#"
        codeKind="SHORT" networkId="0">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="MAP2MAP hop UserInfo">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </processUnstructuredSSRequest_Request>
</dialog>'
```

Gated notify POST (gate during slow hop/AS):

```bash
curl -sS -X POST 'http://127.0.0.1:8090/ussd/pull' \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<dialog appCntx="networkUnstructuredSsContext" localId="corr-1"
        virtualBridgeId="corr-1" adaptiveTimeoutMs="7000" asMode="BRIDGE"
        gateReason="GATE_EXPIRED" mapMessagesSize="1">
  <unstructuredSSNotify_Request dataCodingScheme="15" string="GATE_EXPIRED">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </unstructuredSSNotify_Request>
</dialog>'
# Expect Notify_Response ACK; /health shows lastGated
```

## Stub peer addressing

| Mode | How |
|------|-----|
| **Fixed hop (SP)** | GW CalledParty = `251971200201`/6 — sim/peer must own that GT (or GTT to sim). CLI `hop <text>` auto-answers UnstructuredSS-Request with hop text. |
| **FAKE HLR (no hop_dest)** | Set HLR Face FAKE + fake MSC GT = sim GT; hop still MSC SSN 8; redirect string can still be `*875#`. |
| **PROXY** | Live upper HLR; not needed for SP fixed-dest prove. |

Contract: [`docs/as-contract/map2map.md`](../../docs/as-contract/map2map.md).
