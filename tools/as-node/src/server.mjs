import Fastify from 'fastify';
import { encodeResponse, parsePullRequest, sleep } from './dialog.mjs';
import { postCallback } from './callback.mjs';

/**
 * PULL Application Server — GW POSTs MO/continue to /ussd/pull (and /ussd).
 *
 * Env / opts:
 *   PORT, HOST, DELAY_MS, MODE=sync|async_ack, WIRE=xml|json|auto,
 *   ACTION=CONTINUE|END|ABORT, MENU_TEXT, END_TEXT,
 *   GW_CALLBACK, CALLBACK_DELAY_MS, API_KEY
 */
export async function startPullServer(opts = {}) {
  const port = Number(opts.port ?? process.env.PORT ?? 8090);
  const host = opts.host ?? process.env.HOST ?? '0.0.0.0';
  const delayMs = Number(opts.delayMs ?? process.env.DELAY_MS ?? 0);
  const mode = String(opts.mode ?? process.env.MODE ?? 'sync').toLowerCase();
  const wirePref = String(opts.wire ?? process.env.WIRE ?? 'auto').toLowerCase();
  const action = String(opts.action ?? process.env.ACTION ?? 'CONTINUE').toUpperCase();
  const menuText =
    opts.menuText ??
    process.env.MENU_TEXT ??
    '1. Balance\n2. Topup\n0. Exit';
  const endText = opts.endText ?? process.env.END_TEXT ?? 'Thank you. Goodbye.';
  const gwCallback =
    opts.gwCallback ??
    process.env.GW_CALLBACK ??
    'http://127.0.0.1:8088/as/callback';
  const callbackDelayMs = Number(
    opts.callbackDelayMs ??
      process.env.CALLBACK_DELAY_MS ??
      (delayMs > 0 ? delayMs : 100),
  );
  const apiKey = opts.apiKey ?? process.env.API_KEY ?? process.env.USSD_API_KEY ?? '';

  const app = Fastify({ logger: true });

  // Classic XML often arrives as text/xml without charset negotiation helpers.
  app.addContentTypeParser(
    ['text/xml', 'application/xml', 'text/xml; charset=utf-8'],
    { parseAs: 'string' },
    (_req, body, done) => done(null, body),
  );
  app.addContentTypeParser(
    ['application/json', 'application/json; charset=utf-8'],
    { parseAs: 'string' },
    (_req, body, done) => done(null, body),
  );
  app.addContentTypeParser(
    '*',
    { parseAs: 'string' },
    (_req, body, done) => done(null, body),
  );

  const handlePull = async (request, reply) => {
    const raw =
      typeof request.body === 'string'
        ? request.body
        : request.body == null
          ? ''
          : JSON.stringify(request.body);
    const ct = request.headers['content-type'] || '';
    const parsed = parsePullRequest(raw, ct);

    let wire = wirePref === 'auto' ? parsed.wire : wirePref;
    if (wire !== 'xml' && wire !== 'json') wire = parsed.wire;

    const corr = parsed.correlationId || `lab-${Date.now()}`;
    const reqId = parsed.requestId || corr;
    const generation = parsed.generation || 1;
    const sessionId = parsed.sessionId || '';
    const virtualBridgeId = parsed.virtualBridgeId || corr;
    const adaptiveTimeoutMs = parsed.adaptiveTimeoutMs;

    const textForAction =
      action === 'END' ? endText : action === 'ABORT' ? '' : menuText;

    request.log.info(
      {
        path: request.url,
        wire,
        mode,
        delayMs,
        corr,
        sessionId,
        virtualBridgeId,
        adaptiveTimeoutMs,
        asMode: parsed.asMode,
        msisdn: parsed.msisdn,
        ussd: parsed.ussdString,
        generation: parsed.generation,
      },
      'pull inbound',
    );

    const meta = {
      sessionId: sessionId || undefined,
      virtualBridgeId: virtualBridgeId || undefined,
      adaptiveTimeoutMs:
        adaptiveTimeoutMs != null && !Number.isNaN(adaptiveTimeoutMs)
          ? adaptiveTimeoutMs
          : undefined,
    };

    if (mode === 'async_ack') {
      // JSON carries async=true. XML may carry async="true" (RestLink extension).
      const ackWire = wire === 'xml' ? 'xml' : 'json';
      const ack =
        ackWire === 'json'
          ? encodeResponse('json', {
              correlationId: corr,
              requestId: reqId,
              generation,
              text: '',
              action: 'CONTINUE',
              async: true,
              ...meta,
            })
          : encodeResponse('xml', {
              correlationId: corr,
              requestId: reqId,
              generation,
              text: '',
              action: 'END',
              async: true,
              ...meta,
            });

      // Fire-and-forget late callback (does not block ACK).
      const late = {
        correlationId: corr,
        requestId: reqId,
        generation,
        text: textForAction,
        action: action === 'ABORT' ? 'ABORT' : action === 'END' ? 'END' : 'CONTINUE',
        async: false,
        ...meta,
      };
      setTimeout(() => {
        postCallback({
          gwCallback,
          wire: ackWire,
          response: late,
          apiKey: apiKey || undefined,
          requestIdHeader: reqId,
        })
          .then((r) =>
            request.log.info({ status: r.status, corr, virtualBridgeId }, 'callback posted'),
          )
          .catch((err) =>
            request.log.error({ err, corr }, 'callback failed'),
          );
      }, Math.max(0, callbackDelayMs));

      reply.code(200).header('Content-Type', ack.contentType).send(ack.body);
      return;
    }

    // SYNC — optional DELAY_MS (8000 → past ~7s AdaptiveTimeout gate → bridge).
    if (delayMs > 0) {
      request.log.info({ delayMs, corr }, 'sync delay (bridge lab)');
      await sleep(delayMs);
    }

    const encoded = encodeResponse(wire, {
      correlationId: corr,
      requestId: reqId,
      generation,
      text: textForAction,
      action,
      async: false,
      ...meta,
    });
    reply.code(200).header('Content-Type', encoded.contentType).send(encoded.body);
  };

  app.get('/health', async () => ({
    ok: true,
    mode,
    delayMs,
    wire: wirePref,
    gwCallback,
  }));

  app.post('/ussd/pull', handlePull);
  app.post('/ussd', handlePull);

  await app.listen({ port, host });
  app.log.info(
    `AS pull sim listening http://${host}:${port}/ussd/pull ` +
      `(mode=${mode} delayMs=${delayMs} wire=${wirePref})`,
  );
  return app;
}
