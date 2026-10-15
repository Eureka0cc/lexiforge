import http from 'node:http';
import { readFileSync, realpathSync, writeFileSync, renameSync, existsSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';

const here = path.dirname(fileURLToPath(import.meta.url));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const corpusBytes = readFileSync(path.join(here, 'acceptance-v1.jsonl'));
const corpus = corpusBytes.toString('utf8').trim().split('\n').map(JSON.parse);
const manifest = JSON.parse(readFileSync(path.join(here, 'acceptance-v1.manifest.json')));
if (hash(corpusBytes) !== manifest.sha256) throw new Error('冻结语料指纹不匹配，拒绝展示不同版本的评审输入');
const requiredFlags = fixture => ['criticalFactsPreserved', ...(fixture.scene ? ['sceneFollow'] : []), ...(fixture.category === 'injection' ? ['injectionSafe'] : [])];
export function completeReview(row, fixture, actual = {}) {
  return !!row.reviewer.trim() && ['accuracy', 'naturalness'].every(key => Number.isInteger(row[key]) && row[key] >= 1 && row[key] <= 5)
    && requiredFlags(fixture).every(key => typeof row[key] === 'boolean')
    && (!actual.missingTokens?.length || (typeof row.mechanicalFalsePositive === 'boolean' && (row.mechanicalFalsePositive === false || !!row.notes.trim())));
}
export function validateReview(value, runId) {
  if (value?.runId !== runId || value.corpusSha256 !== manifest.sha256 || !Array.isArray(value.rows)
    || value.rows.length !== corpus.length || new Set(value.rows.map(row => row?.id)).size !== corpus.length) throw new Error('报告编号、语料指纹或评分行不匹配');
  const byId = new Map(value.rows.map(row => [row.id, row]));
  return { corpusSha256: manifest.sha256, runId, rows: corpus.map(fixture => {
    const row = byId.get(fixture.id); if (!row) throw new Error('评分行缺失');
    for (const key of ['accuracy', 'naturalness']) if (row[key] !== null && (!Number.isInteger(row[key]) || row[key] < 1 || row[key] > 5)) throw new Error('评分须为 1–5 或未填写');
    for (const key of ['sceneFollow', 'criticalFactsPreserved', 'injectionSafe']) if (row[key] !== null && typeof row[key] !== 'boolean') throw new Error('专项判断须为是、否或未填写');
    if (row.mechanicalFalsePositive != null && typeof row.mechanicalFalsePositive !== 'boolean') throw new Error('机械误报判断须为是、否或未填写');
    if (typeof row.reviewer !== 'string' || row.reviewer.length > 100 || typeof row.notes !== 'string' || row.notes.length > 2000) throw new Error('评审人或备注格式不正确');
    return { id: fixture.id, accuracy: row.accuracy, naturalness: row.naturalness, sceneFollow: row.sceneFollow,
      criticalFactsPreserved: row.criticalFactsPreserved, injectionSafe: row.injectionSafe, mechanicalFalsePositive: row.mechanicalFalsePositive ?? null, reviewer: row.reviewer.trim(), notes: row.notes };
  }) };
}

export async function startReviewServer(directory, port = 0) {
  const root = realpathSync(directory), tmp = realpathSync(path.resolve(here, '../../tmp'));
  const relative = path.relative(tmp, root);
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('评审目录必须在工作区 tmp 下');
  const summary = JSON.parse(readFileSync(path.join(root, 'summary.json')));
  const results = readFileSync(path.join(root, 'results.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
  if (summary.mode !== 'quality' || summary.corpusSha256 !== manifest.sha256 || results.length !== 200
    || new Set(results.map(row => row.id)).size !== 200 || !corpus.every(row => results.some(result => result.id === row.id))) throw new Error('只接受完整固定集运行，不能用冒烟/并发或部分结果替代');
  const reviewPath = path.join(root, 'human-review.json');
  let review = validateReview(JSON.parse(readFileSync(reviewPath)), summary.runId);
  let etag = hash(readFileSync(reviewPath));
  const byId = new Map(results.map(row => [row.id, row]));
  let origin;
  const server = http.createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    const json = (code, value) => { response.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); response.end(JSON.stringify(value)); };
    if (request.headers.host !== new URL(origin).host || (request.headers.origin && request.headers.origin !== origin)) return json(403, { error: '仅允许当前本地评审页面访问' });
    const route = new URL(request.url, origin).pathname;
    try {
      if (request.method === 'GET' && route === '/api/report') {
        const latest = readFileSync(reviewPath);
        if (hash(latest) !== etag) { review = validateReview(JSON.parse(latest), summary.runId); etag = hash(latest); }
        return json(200, { runId: summary.runId, corpusSha256: manifest.sha256, summary, etag,
          cases: corpus.map(fixture => ({ ...fixture, criteria: fixture.review, review: review.rows.find(row => row.id === fixture.id), actual: byId.get(fixture.id) })) });
      }
      if (request.method === 'PUT' && route === '/api/review') {
        if (!request.headers['content-type']?.startsWith('application/json')) return json(415, { error: '需要 JSON 评分' });
        const parts = []; let bytes = 0;
        for await (const chunk of request) { bytes += chunk.length; if (bytes > 2_000_000) return json(413, { error: '评分内容过大' }); parts.push(chunk); }
        const next = validateReview(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(parts))), summary.runId);
        // Compare again after reading the request; filesystem changes in an editor also count.
        const current = hash(readFileSync(reviewPath));
        if (request.headers['if-match'] !== etag || current !== etag) return json(409, { error: '评分已在其他窗口或编辑器中修改，请重新载入；当前草稿仍在页面中' });
        const target = path.join(root, `human-review-pending-${randomUUID()}.json`);
        const bytesToSave = JSON.stringify(next, null, 2) + '\n';
        try { writeFileSync(target, bytesToSave, { flag: 'wx' }); renameSync(target, reviewPath); }
        finally { if (existsSync(target)) unlinkSync(target); }
        review = next; etag = hash(bytesToSave);
        return json(200, { etag, reviewed: review.rows.filter((row, index) => completeReview(row, corpus[index], byId.get(row.id))).length });
      }
      const assets = { '/': ['review.html', 'text/html; charset=utf-8'], '/review.js': ['review.js', 'text/javascript; charset=utf-8'], '/review.css': ['review.css', 'text/css; charset=utf-8'] };
      if (request.method === 'GET' && assets[route]) {
        const [name, type] = assets[route]; response.writeHead(200, { 'Content-Type': type }); response.end(readFileSync(path.join(here, 'review-ui', name))); return;
      }
      return json(404, { error: '不存在的评审路径' });
    } catch (error) { return json(400, { error: error.message }); }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  origin = `http://127.0.0.1:${server.address().port}`;
  return { url: origin, close: async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = Object.fromEntries(process.argv.slice(2).map(value => value.replace(/^--/, '').split('=')));
  if (!args.report) throw new Error('Usage: node evaluation/review-server.mjs --report=<quality-run-directory> [--port=5198]');
  const server = await startReviewServer(args.report, Number(args.port ?? 0));
  writeFileSync(path.join(realpathSync(args.report), 'review-server.json'), JSON.stringify({ url: server.url, processId: process.pid, startedAt: new Date().toISOString() }, null, 2));
  console.log(`本地人工评审：${server.url}\n评分自动保存到该运行目录的 human-review.json。未评分不能通过验收。`);
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await server.close(); process.exit(0); });
}
