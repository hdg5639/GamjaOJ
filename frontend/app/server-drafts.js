/**
 * Server copy of in-progress code. The browser copy is written on every edit (instant, survives reloads);
 * the server copy follows 1.5 s after typing stops so another browser or device can continue.
 * Scopes: "p:<problem version>" for practice, "d:<diagnostic item id>" for diagnostics.
 */
const timers = new Map();
export function scheduleServerDraft(api, scope, language, source, onDone) {
  const key = `${scope}|${language}`;
  clearTimeout(timers.get(key));
  timers.set(key, setTimeout(async () => {
    timers.delete(key);
    try {
      await api('/api/drafts', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ scope, language, source }) });
      onDone?.(true);
    } catch { onDone?.(false); }
  }, 1500));
}
export async function loadServerDraft(api, scope, language) {
  try {
    const draft = await api(`/api/drafts?scope=${encodeURIComponent(scope)}&language=${encodeURIComponent(language)}`);
    return draft && typeof draft.source === 'string' && typeof draft.updatedAt === 'string' ? draft : null;
  } catch { return null; }
}
/** Browser copy: { source, savedAt }; older copies were a bare string or { source } without a time. */
export function readLocalDraft(key) {
  const raw = localStorage.getItem(key);
  if (raw == null) return null;
  try { const value = JSON.parse(raw); if (value && typeof value.source === 'string') return { source: value.source, savedAt: value.savedAt || 0 }; } catch { /* Plain text. */ }
  return { source: raw, savedAt: 0 };
}
export function writeLocalDraft(key, source, savedAt = Date.now()) {
  localStorage.setItem(key, JSON.stringify({ source, savedAt }));
}
/** The server copy wins when the browser has none or an older one. */
export function newer(local, server) {
  return server && (!local || Date.parse(server.updatedAt) > (local.savedAt || 0)) && server.source !== local?.source;
}
