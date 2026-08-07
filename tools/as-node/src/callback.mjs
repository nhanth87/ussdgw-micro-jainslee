import { encodeResponse } from './dialog.mjs';

/**
 * POST late AS response to GW /as/callback (dual-mode wire).
 * Auth: optional X-USSD-Api-Key / X-API-Key (CallbackAuthService).
 */
export async function postCallback({
  gwCallback,
  wire,
  response,
  apiKey,
  requestIdHeader,
}) {
  const encoded = encodeResponse(wire, response);
  const headers = {
    'Content-Type': encoded.contentType,
    Accept: wire === 'json' ? 'application/json' : 'text/xml',
  };
  if (apiKey) {
    headers['X-USSD-Api-Key'] = apiKey;
  }
  if (requestIdHeader) {
    headers['X-Ussd-Request-Id'] = requestIdHeader;
  }

  const res = await fetch(gwCallback, {
    method: 'POST',
    headers,
    body: encoded.body,
  });
  const text = await res.text().catch(() => '');
  return { status: res.status, body: text };
}
