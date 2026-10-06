export function cachedApi(request, now = Date.now) {
  const entries = new Map();
  let epoch = 0;
  const clear = () => { epoch++; entries.clear(); };
  const ttl = path => {
    if (/^\/api\/(?:my\/(?:summary|problems|growth)|problems|integrations|training-courses)(?:\?.*)?$/.test(path)) return 30000;
    if (path === '/api/training-courses/enrollments') return 5000;
    if (path === '/api/training-sessions' || path === '/api/learning-curricula') return 0; // Share only requests already in flight.
    return null;
  };
  const api = async (path, options = {}) => {
    const {fresh, ...networkOptions} = options;
    const method = (options.method || 'GET').toUpperCase();
    const cacheable = method === 'GET' && ttl(path) !== null && !options.signal && !options.headers;
    if (cacheable && !fresh) {
      const saved = entries.get(path);
      if (saved && (saved.pending || saved.until > now())) return structuredClone(await saved.promise);
    }
    if (method !== 'GET' || fresh) clear();
    const generation = epoch;
    const promise = request(path, networkOptions);
    const entry = {promise, pending: true, until: 0};
    if (cacheable) entries.set(path, entry);
    try {
      const result = await promise;
      entry.pending = false; entry.until = now() + (ttl(path) || 0);
      if (method !== 'GET') clear();
      return cacheable ? structuredClone(result) : result;
    } catch (error) {
      if (error.status === 401) clear();
      else if (generation === epoch && entries.get(path) === entry) entries.delete(path);
      throw error;
    }
  };
  api.clear = clear;
  return api;
}
