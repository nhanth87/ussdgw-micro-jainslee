## TPS definition (locked)

**100 TPS = 100 unique MSISDN MO sessions / second** (each starts one `processUnstructuredSS-Request`
with its own subscriber id). It is **not** 100 TCAP messages/s (CONTINUE/END digits do not
inflate the TPS counter). `ss7.load.rateLimit` / `--tps` gate **MO starts** only.

## Question

Can `TestUssdClientMan` (jSS7 `USSD_TEST_CLIENT` over JMX) drive **10 / 25 / 50** concurrent MO dialogs for a 100 TPS Brook-like load?

## Finding

| Mode | Concurrent dialogs | Notes |
|------|-------------------|--------|
| **Manual JMX** `performProcessUnstructuredRequest` | **1** | Rejects if `currentDialog != null` (“Finish it previousely”). `dt` / `waitNetworkText` bind to that single dialog. |
| **Auto MessageSender** (`UssdClientAction=AUTO_SendProcessUnstructuredSSRequest`) | up to `maxConcurrentDialogs` | Built-in sender thread; not driven by our CLI REPL. |
| **map/load `Client`** | many (rate-limited) | Own stack + `RateLimiter` + per-dialog digit index — **chosen path for 100 TPS**. |

Code (coral-valley `TestUssdClientMan.java`):

```text
if (curDialog != null)
    return "The current dialog exists. Finish it previousely";
```

## Pivot (locked for this plan)

1. **Functional / smoke (≤~2 TPS):** JMX CLI / `run.sh load-jmx` — one dialog at a time, random MSISDN, `*804#` + digit `1`.
2. **Lab 100 TPS:** `run.sh load` → jSS7 **map/load** Client against pull-lab JSON (`8024→8023`), `ss7.load.rateLimit=100`, random MSISDN prefix `25191`, digits `1`, short-code `*804#`.
3. Do **not** claim multi-dialog JMX without patching coral-valley `TestUssdClientMan`.

## Prove ceiling (optional re-run)

With sim+GW up: attempt parallel `dial` from two CLI processes → second should fail or serialize on the same MBean. map/load at `--tps 10` should show concurrent TCAP dialogs in sim/GW logs.
