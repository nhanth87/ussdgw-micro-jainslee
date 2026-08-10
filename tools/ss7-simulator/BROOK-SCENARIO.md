# Brook scenario (locked) — Digicom / BPLUS

**Do not run until operator green light.** AS = **real BPLUS** (Digicom routing `as_url`), never as-node.

## Dual SCCP plane (CRITICAL — do not assume nwid=0 for ss7-sim)

| Plane | SCCP `networkId` | Face | Role |
|-------|------------------|------|------|
| Live BP / handset Brook `*804#` | **0** | L1/L2-BP + AS-BP | Real handset prove — keep Digicom `*804` / tenants at **0** |
| Co-hosted **ss7-sim** | **1** | **L3-LAB-SIM** / **AS-LAB** / sim PC **2** / GW listen **`:8023`** (sim client **`:8024`**) | Lab MO via map/load or JMX |
| MAP2MAP hop | uses `ussd.map.live-network-id` default **0** | live GTT | Sim MO on net **1** still hops on BP |

**Do not flip Digicom routing DB** (`*804` stays `network_id=0`). Simulator stack JSON: [`ss7-ussd-client-digicom-lab.json`](ss7-ussd-client-digicom-lab.json) (`networkId: 1`, dest PC **1470**, M3UA RC **101**).

Laptop-only pull-lab (`ss7-ussd-client-ussdgw-pull.json`) keeps **nwid=0** / PC **1** — not Digicom.

## Call flow (oracle — handset + CDR 2026-08-10)

```
ss7-sim (SCCP nwid=1) / handset (nwid=0)
  → Digicom GW  MAP processUnstructuredSS-Request  string=*804#
    → MAP2MAP Case 2 hop on live nwid=0  (redirect e.g. *875# → upper HLR/MSC GT)
  → hop USSD text (or empty/REJECT path)
  → AS pull BPLUS  BEGIN wire gen=0  ussdString=hop text  originatedUssd=*804#
  → AS CONTINUE  Amharic Balance Plus root  (gen stamp / session)
  → UE digit (Brook prove: digit=1)
  → AS pull CONTINUE  ussdString=1  originatedUssd=*804#  (no second hop)
  → AS CONTINUE  submenu (e.g. Balance Plus EN/AM)
  → optional further digits (2=Packages, 0=Exit) — multimenu
```

| Field | Value |
|-------|--------|
| Short code | `*804#` |
| ss7-sim SCCP | **networkId = 1** (L3-LAB) |
| Live handset SCCP | **networkId = 0** |
| SCTP | sim **:8024** → Digicom L3-LAB **:8023** |
| Point codes | sim **PC=2**, Digicom GW **PC=1470** |
| M3UA | AS-LAB **RC=101** |
| Hop | Digicom RE_ROUTE / `map2map_gt` (operator SoT — do not mutate DB); hop GTT = live nwid **0** |
| AS | BPLUS HTTPS/HTTP per Digicom `as_url` |
| Primary digit (Brook CDR) | `1` (Balance path) |
| Multimenu | Supported: digit claim + gen-stamp + hop-once `originatedUssd` |
| TPS meaning | Unique **MSISDN MO sessions**/s — not TCAP msgs |

## Pass criteria (when green-lit)

1. One `MS_DIGIT` per physical digit (dup → `dup-skip-continue`, no second PullHttp)
2. No `AS_DROP` genMismatch on that corr
3. CDR: `MAP2MAP_HOP_*` → AS CONTINUE → `MS_DIGIT` → CONTINUE gen=
4. Handset/sim: root then submenu after digit `1`
5. Locale stable (no Amharic→English dual-pull)
6. Sim dialog / SCCP plane = **nwid=1** (not live BP 0)

## How to invoke (after green light)

```bash
# Digicom host — UE via map/load against L3-LAB (nwid=1), AS=real BPLUS
cd /home/app/ota-push-services/ussdgw-micro-jainslee/tools/ss7-simulator
# Smoke 1 MSISDN session (not 100 TPS); --scenario brook selects digicom-lab JSON + destPc=1470:
$JAVA_HOME/bin/java -jar cli/ussd-load.jar --scenario brook
# or via helper (same defaults):
./run.sh load --scenario brook
./run.sh load-jmx --scenario brook
```

Deeper menu (optional): `--digits 1,2` (Balance then Packages) — only if BPLUS menu tree expects that.

**Never** `--tps 100` against Digicom without explicit approval.
**Never** point Digicom map/load at laptop `ss7-ussd-client-ussdgw-pull.json` (nwid=0 / destPc=1).
