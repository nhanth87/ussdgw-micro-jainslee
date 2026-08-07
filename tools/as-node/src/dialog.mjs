/**
 * Classic XmlMAPDialog-compatible + greenfield JSON helpers.
 * Element names match ClassicDialogXmlCodec / docs/as-contract/classic-xml.md.
 */

const APP_CTX = 'networkUnstructuredSsContext';
const DCS = 15;

export function xmlEscapeAttr(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\r\n/g, '&#10;')
    .replace(/[\n\r]/g, '&#10;');
}

function attr(xml, name) {
  const re = new RegExp(`\\b${name}="([^"]*)"`, 'i');
  const m = xml.match(re);
  return m ? m[1] : null;
}

function namedString(xml, element) {
  const reAttr = new RegExp(
    `<${element}\\b[^>]*\\bstring="([^"]*)"`,
    'i',
  );
  const mAttr = xml.match(reAttr);
  if (mAttr) return decodeXmlEntities(mAttr[1]);
  const reChild = new RegExp(
    `<${element}\\b[^>]*>[\\s\\S]*?<ussdString>([\\s\\S]*?)</ussdString>`,
    'i',
  );
  const mChild = xml.match(reChild);
  return mChild ? decodeXmlEntities(mChild[1].trim()) : null;
}

function decodeXmlEntities(s) {
  return String(s)
    .replace(/&#10;/g, '\n')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&');
}

function msisdnFromXml(xml) {
  const m = xml.match(/<msisdn\b[^>]*\bnumber="([^"]*)"/i);
  return m ? m[1] : null;
}

/** Parse GW→AS pull body (XML dialog or JSON AsRequest). */
export function parsePullRequest(raw, contentType) {
  const body = (raw ?? '').toString();
  const ct = (contentType ?? '').toLowerCase();
  const looksJson =
    ct.includes('json') || (!ct.includes('xml') && body.trimStart().startsWith('{'));
  if (looksJson) {
    try {
      const j = JSON.parse(body || '{}');
      return {
        wire: 'json',
        correlationId: j.correlationId ?? j.requestId ?? '',
        requestId: j.requestId ?? j.correlationId ?? '',
        generation: Number(j.generation ?? 0),
        msisdn: j.msisdn ?? '',
        shortCode: j.shortCode ?? '',
        ussdString: j.ussdString ?? j.shortCode ?? '',
        networkId: Number(j.networkId ?? 0),
        sessionId: j.sessionId ?? '',
        virtualBridgeId: j.virtualBridgeId ?? '',
        adaptiveTimeoutMs:
          j.adaptiveTimeoutMs != null ? Number(j.adaptiveTimeoutMs) : null,
        asMode: j.asMode ?? '',
      };
    } catch {
      return {
        wire: 'json',
        correlationId: '',
        requestId: '',
        generation: 0,
        msisdn: '',
        shortCode: '',
        ussdString: '',
        networkId: 0,
        sessionId: '',
        virtualBridgeId: '',
        adaptiveTimeoutMs: null,
        asMode: '',
      };
    }
  }

  const corr = attr(body, 'localId') ?? attr(body, 'correlationId') ?? '';
  const networkId = Number(attr(body, 'networkId') ?? 0);
  const ussd =
    namedString(body, 'processUnstructuredSSRequest_Request') ??
    namedString(body, 'unstructuredSSRequest_Request') ??
    namedString(body, 'unstructuredSSRequest_Response') ??
    '';
  const isContinue =
    /unstructuredSSRequest_Response/i.test(body) ||
    (/unstructuredSSRequest_Request/i.test(body) &&
      !/processUnstructuredSSRequest_Request/i.test(body));
  const gateRaw = attr(body, 'adaptiveTimeoutMs');
  return {
    wire: 'xml',
    correlationId: corr,
    requestId: corr,
    generation: isContinue ? 1 : 0,
    msisdn: msisdnFromXml(body) ?? '',
    shortCode: '',
    ussdString: ussd,
    networkId,
    sessionId: attr(body, 'sessionId') ?? '',
    virtualBridgeId: attr(body, 'virtualBridgeId') ?? '',
    adaptiveTimeoutMs: gateRaw != null ? Number(gateRaw) : null,
    asMode: attr(body, 'asMode') ?? '',
  };
}

/**
 * Encode AS→GW pull / callback body.
 * @param {'xml'|'json'} wire
 * @param {{ correlationId, requestId, generation, text, action, async, sessionId, virtualBridgeId, adaptiveTimeoutMs }} resp
 */
export function encodeResponse(wire, resp) {
  const corr = resp.correlationId || 'unknown';
  const action = (resp.action || 'CONTINUE').toUpperCase();
  const text = resp.text ?? '';
  const asyncFlag = !!resp.async;
  const generation = Number(resp.generation ?? 1);
  const requestId = resp.requestId || corr;
  const sessionId = resp.sessionId || '';
  const virtualBridgeId = resp.virtualBridgeId || '';
  const adaptiveTimeoutMs =
    resp.adaptiveTimeoutMs != null && Number.isFinite(Number(resp.adaptiveTimeoutMs))
      ? Number(resp.adaptiveTimeoutMs)
      : null;

  if (wire === 'json') {
    const body = {
      correlationId: corr,
      requestId,
      generation,
      text,
      action,
      async: asyncFlag,
    };
    if (sessionId) body.sessionId = sessionId;
    if (virtualBridgeId) body.virtualBridgeId = virtualBridgeId;
    if (adaptiveTimeoutMs != null) body.adaptiveTimeoutMs = adaptiveTimeoutMs;
    return {
      contentType: 'application/json; charset=utf-8',
      body: JSON.stringify(body),
    };
  }

  // Classic XML + RestLink late-push attrs (classic AS ignores unknown attrs).
  const meta =
    (sessionId ? ` sessionId="${xmlEscapeAttr(sessionId)}"` : '') +
    (virtualBridgeId
      ? ` virtualBridgeId="${xmlEscapeAttr(virtualBridgeId)}"`
      : '') +
    (adaptiveTimeoutMs != null
      ? ` adaptiveTimeoutMs="${adaptiveTimeoutMs}"`
      : '') +
    (asyncFlag ? ' async="true"' : '');

  let dialog;
  if (action === 'ABORT') {
    dialog =
      `<dialog localId="${xmlEscapeAttr(corr)}"${meta} mapMessagesSize="0" ` +
      `mapUserAbortChoice="isUserSpecificReason"/>`;
  } else if (action === 'END') {
    dialog =
      `<dialog appCntx="${APP_CTX}" localId="${xmlEscapeAttr(corr)}"${meta}>` +
      `<processUnstructuredSSRequest_Response dataCodingScheme="${DCS}" ` +
      `string="${xmlEscapeAttr(text)}"/>` +
      `</dialog>`;
  } else {
    // CONTINUE menu
    dialog =
      `<dialog appCntx="${APP_CTX}" localId="${xmlEscapeAttr(corr)}"${meta}>` +
      `<unstructuredSSRequest_Request dataCodingScheme="${DCS}" ` +
      `string="${xmlEscapeAttr(text)}"/>` +
      `</dialog>`;
  }
  return {
    contentType: 'text/xml; charset=utf-8',
    body: `<?xml version="1.0" encoding="UTF-8"?>\n${dialog}`,
  };
}

/** Classic NI first-turn dialog (AS→GW /ussd). */
export function encodeNiIngress({
  msisdn,
  text,
  correlationId,
  emptyDialogHandshake = false,
  notify = false,
}) {
  const el = notify
    ? 'unstructuredSSNotify_Request'
    : 'unstructuredSSRequest_Request';
  const handshake = emptyDialogHandshake ? ' emptyDialogHandshake="true"' : '';
  const local = correlationId
    ? ` localId="${xmlEscapeAttr(correlationId)}"`
    : '';
  const ms =
    msisdn != null && String(msisdn).length > 0
      ? `<msisdn nai="international_number" npi="ISDN" number="${xmlEscapeAttr(msisdn)}"/>`
      : '';
  return (
    `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<dialog mapMessagesSize="1"${local}${handshake}>` +
    `<${el} dataCodingScheme="${DCS}" string="${xmlEscapeAttr(text)}">${ms}</${el}>` +
    `</dialog>`
  );
}

/** Empty / end NI turn (mapMessagesSize=0). */
export function encodeNiEnd(correlationId) {
  const local = correlationId
    ? ` localId="${xmlEscapeAttr(correlationId)}"`
    : '';
  return (
    `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<dialog mapMessagesSize="0"${local} prearrangedEnd="true"/>`
  );
}

export function sleep(ms) {
  const n = Math.max(0, Number(ms) || 0);
  if (n <= 0) return Promise.resolve();
  return new Promise((r) => setTimeout(r, n));
}
