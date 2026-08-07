#!/usr/bin/env node
/**
 * RestLink USSD GW — Node AS lab sim
 *
 *   node src/index.mjs pull     # PULL server (:8090 /ussd/pull)
 *   node src/index.mjs ni       # classic NI client → GW /ussd
 *
 * See README.md for AdaptiveTimeout / VirtualSessionBridge presets.
 */

import { startPullServer } from './server.mjs';
import { runNiClient } from './ni-client.mjs';

const cmd = (process.argv[2] || 'pull').toLowerCase();

async function main() {
  if (cmd === 'pull' || cmd === 'serve' || cmd === 'server') {
    await startPullServer();
    return;
  }
  if (cmd === 'ni' || cmd === 'push' || cmd === 'push:ni') {
    await runNiClient();
    return;
  }
  console.error(`Usage: node src/index.mjs <pull|ni>`);
  console.error(`  pull  — listen :8090 /ussd/pull (DELAY_MS, MODE, WIRE, …)`);
  console.error(`  ni    — POST classic NI dialog to GW /ussd (GW_NI, MSISDN, …)`);
  process.exit(1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
