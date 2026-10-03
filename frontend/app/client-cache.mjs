export function cachedApi(request, now = Date.now) {
  const entries = new Map();
  let epoch = 0;
  const clear = () => { epoch++; entries.clear(); };
  const eligible = path => /^\/api\/(?:my\/(?:summary|problems|growth)|problems|integrations)(?:\?.*)?$/.test(path);
  const api = async (path, options = {}) => {
    const {fresh, ...networkOptions} = options;
    const method = (options.method || 'GET').toUpperCase();
    const cacheable = method === 'GET' && eligible(path) && !options.signal && !options.headers;
    if (cacheable && !fresh) {
      const saved = entries.get(path);
      if (saved && saved.until > now()) return structuredClone(await saved.promise);
    }
    if (method !== 'GET' || fresh) clear();
    const generation = epoch;
    const promise = request(path, networkOptions);
    const entry = {promise, until: now() + 30000};
    if (cacheable) entries.set(path, entry);
    try {
      const result = await promise;
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
