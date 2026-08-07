import { encodeNiEnd, encodeNiIngress, parsePullRequest, sleep } from './dialog.mjs';
import { clearMenuSession, nextMenuResponse, rootText, listMenus } from './menus.mjs';
import { CATALOG } from './menus.mjs';

/**
 * Classic NI sync client — AS POSTs to GW /ussd with JSESSIONID cookie jar.
 * Interactive mode: multi-turn menu until END (subscriber digits come back in GW body).
 * Uses undici/fetch (Node 20+); no extra cookie package.
 */

function pickJsession(setCookie) {
  if (!setCookie) return null;
  const list = Array.isArray(setCookie) ? setCookie : [setCookie];
  for (const c of list) {
    const m = String(c).match(/JSESSIONID=([^;]+)/i);
    if (m) return m[1];
  }
  return null;
}

function digitFromGwBody(body, contentType) {
  const parsed = parsePullRequest(body, contentType || 'text/xml');
  const s = String(parsed.ussdString || '').trim();
  if (/^[0-4]$/.test(s)) return s;
  const m = s.match(/([0-4])\s*$/);
  return m ? m[1] : s;
}

function pickForcedMenu(msisdn) {
  const pick = String(process.env.MENU_PICK || 'hash').toLowerCase();
  const forced = CATALOG.find((m) => m.id === pick);
  if (forced) return forced;
  // Mirror menus.mjs hash for first NI screen before session exists.
  const turn = nextMenuResponse(
    { msisdn, ussdString: '*ni#', generation: 0, correlationId: `ni-pick-${msisdn}` },
    { menuPick: pick },
  );
  clearMenuSession(turn.sessionKey);
  return CATALOG.find((m) => m.id === turn.menuId) || CATALOG[0];
}

export async function runNiClient(opts = {}) {
  const gwNi =
    opts.gwNi ?? process.env.GW_NI ?? 'http://127.0.0.1:8088/ussd';
  const msisdn = opts.msisdn ?? process.env.MSISDN ?? '251911000001';
  const interactive =
    opts.interactive != null
      ? !!opts.interactive
      : String(process.env.INTERACTIVE ?? 'true').toLowerCase() !== 'false';
  const staticText =
    opts.text ?? process.env.NI_TEXT ?? null;
  const corr =
    opts.correlationId ?? process.env.CORR ?? `ni-${Date.now()}`;
  const handshake =
    String(opts.emptyHandshake ?? process.env.EMPTY_HANDSHAKE ?? 'false')
      .toLowerCase() === 'true';
  const finish =
    String(opts.finish ?? process.env.NI_FINISH ?? 'true').toLowerCase() !==
    'false';
  const apiKey = opts.apiKey ?? process.env.API_KEY ?? process.env.USSD_API_KEY ?? '';
  const maxTurns = Number(opts.maxTurns ?? process.env.NI_MAX_TURNS ?? 8);
  const turnDelayMs = Number(opts.turnDelayMs ?? process.env.NI_TURN_DELAY_MS ?? 200);

  let firstText;
  if (!interactive && staticText) {
    firstText = staticText;
  } else if (!interactive) {
    firstText = 'Press 1 to confirm';
  } else {
    const menu = pickForcedMenu(msisdn);
    firstText = rootText(menu);
    console.error(`[ni] interactive menu=${menu.id} menus=${listMenus().map((m) => m.id).join(',')}`);
  }

  const headers = {
    'Content-Type': 'text/xml; charset=utf-8',
    Accept: 'text/xml',
  };
  if (apiKey) headers['X-USSD-Api-Key'] = apiKey;

  console.error(`[ni] POST ${gwNi} msisdn=${msisdn} corr=${corr}`);
  const body = encodeNiIngress({
    msisdn,
    text: firstText,
    correlationId: corr,
    emptyDialogHandshake: handshake,
  });
  const res1 = await fetch(gwNi, { method: 'POST', headers, body });
  const setCookie =
    typeof res1.headers.getSetCookie === 'function'
      ? res1.headers.getSetCookie()
      : res1.headers.get('set-cookie');
  let jsession = pickJsession(setCookie);
  let body1 = await res1.text();
  console.error(`[ni] status=${res1.status} jsession=${jsession || '(none)'}`);
  console.log(body1);

  if (!interactive) {
    if (!finish) {
      return { status: res1.status, jsession, body: body1 };
    }
    const endBody = encodeNiEnd(corr);
    const headers2 = { ...headers };
    if (jsession) headers2.Cookie = `JSESSIONID=${jsession}`;
    console.error(`[ni] POST end turn (prearrangedEnd)`);
    const res2 = await fetch(gwNi, {
      method: 'POST',
      headers: headers2,
      body: endBody,
    });
    const body2 = await res2.text();
    console.error(`[ni] end status=${res2.status}`);
    console.log(body2);
    return { status: res2.status, jsession, body: body2 };
  }

  // Interactive: seed menu session, then drive continues from GW digit replies.
  let turn = nextMenuResponse({
    msisdn,
    ussdString: '*ni#',
    generation: 0,
    correlationId: corr,
  });
  let lastBody = body1;
  let lastStatus = res1.status;

  for (let i = 0; i < maxTurns; i++) {
    const digit = digitFromGwBody(lastBody, 'text/xml');
    if (!digit) {
      console.error(`[ni] turn=${i} no digit in GW body — waiting/ending`);
      break;
    }
    console.error(`[ni] turn=${i} digit=${digit} menu=${turn.menuId}`);
    turn = nextMenuResponse({
      msisdn,
      ussdString: digit,
      generation: 1,
      correlationId: corr,
    });

    const headersN = { ...headers };
    if (jsession) headersN.Cookie = `JSESSIONID=${jsession}`;

    if (turn.action === 'END' || turn.action === 'ABORT') {
      // Final notify/end via empty dialog after optional last text ingress.
      if (turn.text) {
        const mid = encodeNiIngress({
          msisdn,
          text: turn.text,
          correlationId: corr,
          notify: true,
        });
        const resMid = await fetch(gwNi, { method: 'POST', headers: headersN, body: mid });
        const sc =
          typeof resMid.headers.getSetCookie === 'function'
            ? resMid.headers.getSetCookie()
            : resMid.headers.get('set-cookie');
        jsession = pickJsession(sc) || jsession;
        lastBody = await resMid.text();
        lastStatus = resMid.status;
        console.error(`[ni] final notify status=${lastStatus}`);
        console.log(lastBody);
      }
      break;
    }

    await sleep(turnDelayMs);
    const cont = encodeNiIngress({
      msisdn,
      text: turn.text,
      correlationId: corr,
    });
    const resN = await fetch(gwNi, { method: 'POST', headers: headersN, body: cont });
    const sc2 =
      typeof resN.headers.getSetCookie === 'function'
        ? resN.headers.getSetCookie()
        : resN.headers.get('set-cookie');
    jsession = pickJsession(sc2) || jsession;
    lastBody = await resN.text();
    lastStatus = resN.status;
    console.error(`[ni] continue status=${lastStatus} screen=${turn.screen}`);
    console.log(lastBody);
  }

  if (finish) {
    const headers2 = { ...headers };
    if (jsession) headers2.Cookie = `JSESSIONID=${jsession}`;
    console.error(`[ni] POST end turn (prearrangedEnd)`);
    const res2 = await fetch(gwNi, {
      method: 'POST',
      headers: headers2,
      body: encodeNiEnd(corr),
    });
    const body2 = await res2.text();
    console.error(`[ni] end status=${res2.status}`);
    console.log(body2);
    clearMenuSession(corr);
    return { status: res2.status, jsession, body: body2 };
  }

  clearMenuSession(corr);
  return { status: lastStatus, jsession, body: lastBody };
}
