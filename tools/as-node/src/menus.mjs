/**
 * Digicom lab interactive USSD menus (PULL + NI PUSH).
 *
 * Menu pick (document choice):
 *   MENU_PICK=hash   — stable per MSISDN (default): hash(msisdn) % N
 *   MENU_PICK=random — Math.random per new session
 *   MENU_PICK=rotate — atomic counter
 *   MENU_PICK=main|lang|promo|help — force one catalog entry
 *
 * Labels are intentional so GW / as-node logs show which menu/branch fired.
 */

const CATALOG = [
  {
    id: 'main',
    title: 'Welcome Digicom lab',
    options: {
      '1': { label: 'Balance', leaf: '[main/1] Balance: ETB 42.50. 0=menu 4=exit' },
      '2': { label: 'Bundle', leaf: '[main/2] Bundle: 1GB left. 0=menu 4=exit' },
      '3': { label: 'Help', leaf: '[main/3] Help: dial *100#. 0=menu 4=exit' },
      '4': { label: 'Exit', end: '[main/4] Goodbye Digicom lab.' },
    },
  },
  {
    id: 'lang',
    title: 'Digicom language lab',
    options: {
      '1': { label: 'English', leaf: '[lang/1] Language=EN. 0=menu 4=exit' },
      '2': { label: 'Amharic', leaf: '[lang/2] Language=AM. 0=menu 4=exit' },
      '3': { label: 'Oromo', leaf: '[lang/3] Language=OM. 0=menu 4=exit' },
      '4': { label: 'Exit', end: '[lang/4] Language menu closed.' },
    },
  },
  {
    id: 'promo',
    title: 'Digicom promo lab',
    options: {
      '1': { label: 'Daily', leaf: '[promo/1] Daily 50MB OK. 0=menu 4=exit' },
      '2': { label: 'Weekly', leaf: '[promo/2] Weekly 500MB OK. 0=menu 4=exit' },
      '3': { label: 'Monthly', leaf: '[promo/3] Monthly 5GB OK. 0=menu 4=exit' },
      '4': { label: 'Exit', end: '[promo/4] Promo ended.' },
    },
  },
  {
    id: 'help',
    title: 'Digicom support lab',
    options: {
      '1': { label: 'FAQ', leaf: '[help/1] FAQ: USSD *100#. 0=menu 4=exit' },
      '2': { label: 'Agent', leaf: '[help/2] Agent queued. 0=menu 4=exit' },
      '3': { label: 'Status', leaf: '[help/3] Status=OK. 0=menu 4=exit' },
      '4': { label: 'End', end: '[help/4] Support session end.' },
    },
  },
];

/** @type {Map<string, { menuId: string, screen: 'root'|'leaf' }>} */
const sessions = new Map();
let rotateIdx = 0;

export function listMenus() {
  return CATALOG.map((m) => ({
    id: m.id,
    title: m.title,
    options: Object.entries(m.options).map(([k, v]) => `${k}.${v.label}`),
  }));
}

export function rootText(menu) {
  const lines = [menu.title];
  for (const [k, v] of Object.entries(menu.options)) {
    lines.push(`${k}. ${v.label}`);
  }
  lines.push('0. Back/End');
  return lines.join('\n');
}

function findMenu(id) {
  return CATALOG.find((m) => m.id === id) || CATALOG[0];
}

function hashPick(msisdn) {
  const s = String(msisdn || '0');
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return CATALOG[h % CATALOG.length];
}

function pickMenu(msisdn, pickMode) {
  const mode = String(pickMode || process.env.MENU_PICK || 'hash').toLowerCase();
  const forced = CATALOG.find((m) => m.id === mode);
  if (forced) return forced;
  if (mode === 'random') {
    return CATALOG[Math.floor(Math.random() * CATALOG.length)];
  }
  if (mode === 'rotate') {
    const m = CATALOG[rotateIdx % CATALOG.length];
    rotateIdx += 1;
    return m;
  }
  return hashPick(msisdn);
}

function sessionKey(parsed) {
  return (
    parsed.correlationId ||
    parsed.sessionId ||
    parsed.virtualBridgeId ||
    parsed.msisdn ||
    `anon-${Date.now()}`
  );
}

function normalizeDigit(ussd) {
  const raw = String(ussd ?? '').trim();
  if (!raw) return '';
  // Handset often sends a single digit; tolerate "*1#" / "1#" lab shapes.
  const m = raw.match(/([0-4])\s*$/);
  if (m) return m[1];
  if (/^[0-4]$/.test(raw)) return raw;
  return raw;
}

/**
 * Advance (or start) an interactive session from a GW→AS pull / NI digit.
 * @returns {{ text: string, action: 'CONTINUE'|'END'|'ABORT', menuId: string, screen: string }}
 */
export function nextMenuResponse(parsed, opts = {}) {
  const pickMode = opts.menuPick ?? process.env.MENU_PICK ?? 'hash';
  const key = sessionKey(parsed);
  const digit = normalizeDigit(parsed.ussdString);
  const isInitial =
    Number(parsed.generation ?? 0) === 0 ||
    (!digit && !sessions.has(key)) ||
    (String(parsed.ussdString || '').includes('*') &&
      String(parsed.ussdString || '').includes('#'));

  let state = sessions.get(key);
  if (!state || isInitial) {
    const menu = pickMenu(parsed.msisdn, pickMode);
    state = { menuId: menu.id, screen: 'root' };
    sessions.set(key, state);
    return {
      text: rootText(menu),
      action: 'CONTINUE',
      menuId: menu.id,
      screen: 'root',
      sessionKey: key,
    };
  }

  const menu = findMenu(state.menuId);
  const d = digit;

  if (d === '0') {
    if (state.screen === 'leaf') {
      state.screen = 'root';
      sessions.set(key, state);
      return {
        text: rootText(menu),
        action: 'CONTINUE',
        menuId: menu.id,
        screen: 'root',
        sessionKey: key,
      };
    }
    sessions.delete(key);
    return {
      text: `[${menu.id}/0] Session ended.`,
      action: 'END',
      menuId: menu.id,
      screen: 'end',
      sessionKey: key,
    };
  }

  const opt = menu.options[d];
  if (!opt) {
    return {
      text: `[${menu.id}] Invalid. Choose 1-4 or 0.\n${rootText(menu)}`,
      action: 'CONTINUE',
      menuId: menu.id,
      screen: 'root',
      sessionKey: key,
    };
  }

  if (opt.end) {
    sessions.delete(key);
    return {
      text: opt.end,
      action: 'END',
      menuId: menu.id,
      screen: 'end',
      sessionKey: key,
    };
  }

  state.screen = 'leaf';
  sessions.set(key, state);
  return {
    text: opt.leaf,
    action: 'CONTINUE',
    menuId: menu.id,
    screen: 'leaf',
    sessionKey: key,
  };
}

/** Drop session (tests / NI finish). */
export function clearMenuSession(key) {
  if (key) sessions.delete(key);
}

export function clearAllMenuSessions() {
  sessions.clear();
}

export { CATALOG };
