#!/usr/bin/env node
/**
 * HTTP-level multi-user digit loop against as-node (no MAP).
 * Use to smoke-test menus before / without jSS7. Real MAP path: see README.md.
 *
 *   AS_URL=http://127.0.0.1:8090/ussd/pull node mo-http-digit-loop.mjs
 *   CONFIG=./config.example.json node mo-http-digit-loop.mjs
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function loadConfig() {
  const path = resolve(process.env.CONFIG || new URL('./config.example.json', import.meta.url).pathname);
  const cfg = JSON.parse(readFileSync(path, 'utf8'));
  return {
    asUrl: process.env.AS_URL || cfg.asUrl || 'http://127.0.0.1:8090/ussd/pull',
    shortCode: process.env.SHORT_CODE || cfg.shortCode || '*100#',
    digits: String(process.env.USSD_SIM_AUTO_DIGITS || cfg.digits || '1,2,3,4')
      .split(/[,|;]/)
      .map((s) => s.trim())
      .filter(Boolean),
    digitDelayMs: Number(process.env.DIGIT_DELAY_MS || cfg.digitDelayMs || 400),
    userGapMs: Number(process.env.USER_GAP_MS || cfg.userGapMs || 500),
    mode: process.env.MODE || cfg.mode || 'sequential',
    msisdns: (process.env.USSD_SIM_MSISDNS || (cfg.msisdns || []).join(','))
      .split(/[,;]/)
      .map((s) => s.trim())
      .filter(Boolean),
  };
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, Math.max(0, ms)));
}

function xmlPull({ corr, msisdn, ussd, continueTurn }) {
  if (continueTurn) {
    return `<?xml version="1.0" encoding="UTF-8"?>
<dialog type="Continue" appCntx="networkUnstructuredSsContext_version2"
        networkId="0" localId="${corr}" mapMessagesSize="1">
  <unstructuredSSRequest_Response dataCodingScheme="15" string="${ussd}">
    <msisdn nai="international_number" npi="ISDN" number="${msisdn}"/>
  </unstructuredSSRequest_Response>
</dialog>`;
  }
  return `<?xml version="1.0" encoding="UTF-8"?>
<dialog type="Begin" appCntx="networkUnstructuredSsContext_version2"
        networkId="0" localId="${corr}" mapMessagesSize="1">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="${ussd}">
    <msisdn nai="international_number" npi="ISDN" number="${msisdn}"/>
  </processUnstructuredSSRequest_Request>
</dialog>`;
}

async function post(asUrl, xml) {
  const res = await fetch(asUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'text/xml; charset=utf-8', Accept: 'text/xml' },
    body: xml,
  });
  const body = await res.text();
  return { status: res.status, body };
}

async function runUser(cfg, msisdn, idx) {
  const corr = `http-mo-${msisdn}-${Date.now()}-${idx}`;
  console.error(`[user ${msisdn}] MO ${cfg.shortCode} corr=${corr}`);
  let r = await post(cfg.asUrl, xmlPull({ corr, msisdn, ussd: cfg.shortCode, continueTurn: false }));
  console.error(`[user ${msisdn}] menu status=${r.status}`);
  console.log(r.body);

  for (const digit of cfg.digits) {
    await sleep(cfg.digitDelayMs);
    console.error(`[user ${msisdn}] digit=${digit}`);
    r = await post(cfg.asUrl, xmlPull({ corr, msisdn, ussd: digit, continueTurn: true }));
    console.error(`[user ${msisdn}] status=${r.status}`);
    console.log(r.body);
    if (/processUnstructuredSSRequest_Response|mapUserAbortChoice/i.test(r.body)) {
      console.error(`[user ${msisdn}] AS ended/aborted`);
      break;
    }
  }
}

async function main() {
  const cfg = loadConfig();
  if (!cfg.msisdns.length) cfg.msisdns = ['251911000001'];
  console.error(
    `[mo-http] as=${cfg.asUrl} users=${cfg.msisdns.length} digits=${cfg.digits.join(',')} mode=${cfg.mode}`,
  );

  if (cfg.mode === 'parallel') {
    await Promise.all(cfg.msisdns.map((m, i) => runUser(cfg, m, i)));
  } else {
    for (let i = 0; i < cfg.msisdns.length; i++) {
      await runUser(cfg, cfg.msisdns[i], i);
      await sleep(cfg.userGapMs);
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
