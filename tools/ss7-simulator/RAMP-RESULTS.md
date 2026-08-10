# Lab load ramp results (Brook-like)

**TPS = unique MSISDN MO sessions / second** (not TCAP message count).

**Digicom Brook oracle:** [`BROOK-SCENARIO.md`](BROOK-SCENARIO.md) — real BPLUS, MAP2MAP hop,
digit `1`, **ss7-sim SCCP `networkId=1`** (L3-LAB `:8023`/`:8024`, PC 2→1470), live handset `*804`
stays **nwid=0**, wait green light, **never as-node**, **never 100 TPS Digicom without approval**.
Do **not** flip Digicom routing DB for lab.

| Step | Target | Date | Result | Notes |
|------|--------|------|--------|-------|
| Spike JMX concurrency | 10/25/50 | 2026-08-10 | **CEILING=1** | Manual JMX single `currentDialog`. Pivot → map/load. See SPIKE-JMX-CONCURRENCY.md |
| Driver wiring | `--help` + mapload exec | 2026-08-10 | **OK** | `ussd-load.jar` launches Client; rateLimit=MSISDN/s; prefix 25191 in jar |
| Digicom Brook smoke | `--scenario brook` (1×30s) | _wait green light_ | — | nwid=**1** digicom-lab JSON; `*804#` digit 1 → real BPLUS |
| Functional 1 MSISDN/s | 1 × 30s | _pending lab_ | — | Lab laptop: GW `:8023` + as-node `pull:brook804` + reseed-brook (**nwid=0** pull JSON) |
| Ramp 10 | 10 × 30s | _pending lab_ | — | Lab as-node only. Watch `/admin/status.json` adaptive + gateTicks |
| Ramp 50 | 50 × 60s | _pending lab_ | — | Lab only |
| Ramp **100** | 100 × 60s | _pending lab_ | — | Lab only. Pass: live SS7, error &lt;1%, achieved≈100 MSISDN/s |

## How to fill after lab run

```bash
# Lab as-node ramp (NOT Digicom; laptop pull JSON nwid=0):
./tools/ss7-simulator/run.sh load --tps 100 --duration 60 --short-code '*804#' --digits 1 \
  | tee /tmp/ussd-load-100.log
# paste summary lines + status[after] adaptive/ss7 into this table

# Digicom (after green light only — real BPLUS, ss7-sim nwid=1, never as-node):
./tools/ss7-simulator/run.sh load --scenario brook | tee /tmp/ussd-load-brook.log
```

Do **not** run 100 MSISDN/s against Digicom / live BPLUS without explicit approval.
