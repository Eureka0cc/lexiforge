import http from 'node:http';
import { createReadStream, existsSync, realpathSync, statSync } from 'node:fs';
import path from 'node:path';

// Local UI transport fixture. No AI, database, authentication or billing claims.
export async function startTranslationUiServer({ root, partial = '' } = {}) {
  const assetRoot = root ? realpathSync(root) : null;
  const state = { requests: [], connectionsClosed: 0, sceneRequests: [], sceneConnectionsClosed: 0 };
  const responses = [];
  const event = (response, name, data) => response.write(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`);
  const server = http.createServer(async (request, response) => {
    response.setHeader('Access-Control-Allow-Origin', request.headers.origin || 'http://127.0.0.1');
    response.setHeader('Access-Control-Allow-Credentials', 'true');
    response.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type,Accept');
    response.setHeader('Access-Control-Allow-Methods', 'POST,GET,OPTIONS');
    if (request.method === 'OPTIONS') { response.writeHead(204); response.end(); return; }
    const url = new URL(request.url, 'http://127.0.0.1');
    const ok = data => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ success: true, data })); };
    if (url.pathname === '/api/domains/v1') return ok([{ id: '2001', name: 'IT', code: 'IT', builtIn: true }]);
    if (url.pathname === '/api/translations/v1') return ok({ items: [] });
    if (url.pathname === '/api/scenes/generate/v1' && request.method === 'POST') {
      let body = ''; for await (const chunk of request) body += chunk;
      try { state.sceneRequests.push(JSON.parse(body)); } catch { response.writeHead(400); response.end(); return; }
      response.on('close', () => state.sceneConnectionsClosed++);
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.flushHeaders(); // Deliberately pending: measure loading feedback, not completion.
      return;
    }
    if (url.pathname === '/api/translations/stream/v1' && request.method === 'POST') {
      let body = ''; for await (const chunk of request) body += chunk;
      let payload;
      try { payload = JSON.parse(body); } catch { response.writeHead(400); response.end(); return; }
      state.requests.push(payload); responses.push(response);
      response.on('close', () => state.connectionsClosed++);
      response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      event(response, 'started', { requestId: payload.requestId });
      if (partial) event(response, 'delta', { text: partial });
      return;
    }
    if (!assetRoot || url.pathname.startsWith('/api/')) { response.writeHead(404); response.end(); return; }
    let pathname;
    try { pathname = decodeURIComponent(url.pathname); } catch { response.writeHead(400); response.end(); return; }
    const candidate = path.resolve(assetRoot, '.' + pathname);
    if (candidate !== assetRoot && !candidate.startsWith(assetRoot + path.sep)) { response.writeHead(403); response.end(); return; }
    const file = existsSync(candidate) && statSync(candidate).isFile() ? candidate : path.join(assetRoot, 'index.html');
    const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };
    response.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    createReadStream(file).pipe(response);
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return {
    url: `http://127.0.0.1:${server.address().port}`, state,
    complete(index, text) {
      const response = responses[index], payload = state.requests[index];
      event(response, 'delta', { text });
      event(response, 'completed', { translation: { id: payload.requestId, ...payload, translatedText: partial + text, alternatives: [] } });
      response.end();
    },
    disconnect(index) { responses[index].destroy(); },
    async close() { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); },
  };
}
