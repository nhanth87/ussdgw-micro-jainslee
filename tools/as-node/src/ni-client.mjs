import { encodeNiEnd, encodeNiIngress } from './dialog.mjs';

/**
 * Classic NI sync client — AS POSTs to GW /ussd with JSESSIONID cookie jar.
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

export async function runNiClient(opts = {}) {
  const gwNi =
    opts.gwNi ?? process.env.GW_NI ?? 'http://127.0.0.1:8088/ussd';
  const msisdn = opts.msisdn ?? process.env.MSISDN ?? '251911000001';
  const text =
    opts.text ?? process.env.NI_TEXT ?? 'Press 1 to confirm';
  const corr =
    opts.correlationId ?? process.env.CORR ?? `ni-${Date.now()}`;
  const handshake =
    String(opts.emptyHandshake ?? process.env.EMPTY_HANDSHAKE ?? 'false')
      .toLowerCase() === 'true';
  const finish =
    String(opts.finish ?? process.env.NI_FINISH ?? 'true').toLowerCase() !==
    'false';
  const apiKey = opts.apiKey ?? process.env.API_KEY ?? process.env.USSD_API_KEY ?? '';

  const body = encodeNiIngress({
    msisdn,
    text,
    correlationId: corr,
    emptyDialogHandshake: handshake,
  });

  const headers = {
    'Content-Type': 'text/xml; charset=utf-8',
    Accept: 'text/xml',
  };
  if (apiKey) headers['X-USSD-Api-Key'] = apiKey;

  console.error(`[ni] POST ${gwNi} msisdn=${msisdn} corr=${corr}`);
  const res1 = await fetch(gwNi, { method: 'POST', headers, body });
  const setCookie =
    typeof res1.headers.getSetCookie === 'function'
      ? res1.headers.getSetCookie()
      : res1.headers.get('set-cookie');
  const jsession = pickJsession(setCookie);
  const body1 = await res1.text();
  console.error(`[ni] status=${res1.status} jsession=${jsession || '(none)'}`);
  console.log(body1);

  if (!finish) {
    return { status: res1.status, jsession, body: body1 };
  }

  // Classic close: empty dialog with cookie.
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
