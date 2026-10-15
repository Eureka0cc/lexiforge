import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { resolve, relative, isAbsolute, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = Object.fromEntries(process.argv.slice(2).map(arg => arg.replace(/^--/, '').split('=')));
if (!args.base) throw new Error('Explicit --base required; use scripts/verify-translation-quality.ps1 for isolated fixtures');
const base = new URL(args.base);
if (!['127.0.0.1', 'localhost'].includes(base.hostname) || base.protocol !== 'http:') throw new Error('Local HTTP only');
const here = dirname(fileURLToPath(import.meta.url));
const tmp = resolve(here, '../../tmp');
const out = resolve(args.output ?? resolve(tmp, 'quality', randomUUID()));
const within = relative(tmp, out);
if (within.startsWith('..') || isAbsolute(within) || !within) throw new Error('Output must be inside workspace tmp');
mkdirSync(out, { recursive: true });
if (existsSync(resolve(out, 'metadata.json'))) throw new Error('Refusing to mix or overwrite earlier evaluation runs');
const corpusBytes = readFileSync(resolve(here, 'acceptance-v1.jsonl'));
const corpus = corpusBytes.toString('utf8').trim().split('\n').map(JSON.parse);
const manifest = JSON.parse(readFileSync(resolve(here, 'acceptance-v1.manifest.json'), 'utf8'));
if (createHash('sha256').update(corpusBytes).digest('hex') !== manifest.sha256) throw new Error('Corpus hash differs');
const mode = args.mode ?? 'quality';
if (!['quality', 'load', 'smoke'].includes(mode)) throw new Error('Unknown mode');
const runId = randomUUID();
const rows = [];
const users = [];
async function api(path, body, token, method = 'POST') {
  const response = await fetch(new URL(path, base), { method, signal: AbortSignal.timeout(100_000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  const value = await response.json();
  if (!response.ok || !value.success) throw new Error(`HTTP ${response.status}: ${value.code ?? 'UNKNOWN'}`);
  return value.data;
}
async function user(index) {
  if (users[index]) return users[index];
  const account = await api('/api/auth/register/v1', {
    email: `eval-${runId}-${index}@example.test`, password: randomUUID() + 'Aa1!', displayName: '固定验收测试',
  });
  const token = account.accessToken;
  const order = await api('/api/billing/orders/v1', { requestId: randomUUID(), cycle: 'MONTH', channel: 'ALIPAY_SIMULATOR' }, token);
  await api(`/api/billing/orders/${order.id}/sandbox-pay/v1`, {}, token);
  users[index] = { token, sceneId: null, revision: null };
  return users[index];
}
async function scene(row, account) {
  if (!row.scene) return { domainId: null };
  const started = performance.now();
  // Whitelist input: only authored description, never reference/review/mustPreserve.
  const draft = await api('/api/scenes/generate/v1', { requestId: randomUUID(), description: row.sceneDescription }, account.token);
  const body = { name: draft.name, description: row.sceneDescription, configuration: draft.configuration, expectedRevision: account.revision };
  const saved = await api(account.sceneId ? `/api/domains/${account.sceneId}/v1` : '/api/domains/v1', body, account.token, account.sceneId ? 'PATCH' : 'POST');
  account.sceneId = saved.id; account.revision = saved.revision;
  return { domainId: saved.id, generatedScene: draft, savedScene: saved, sceneMs: performance.now() - started };
}
async function translate(row, account, domainId, forceRefresh = true) {
  const started = performance.now();
  let firstDeltaMs = null, translation = null, buffer = '', visible = '', eventCount = 0;
  const response = await fetch(new URL('/api/translations/stream/v1', base), {
    method: 'POST', signal: AbortSignal.timeout(100_000),
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${account.token}`, Accept: 'text/event-stream' },
    body: JSON.stringify({ requestId: randomUUID(), sourceText: row.sourceText, sourceLanguage: row.sourceLanguage,
      targetLanguage: row.targetLanguage, domainId, context: '', includeTerms: false, forceRefresh }),
  });
  if (!response.ok) throw new Error(`SSE HTTP ${response.status}`);
  const reader = response.body.getReader(), decoder = new TextDecoder();
  const consume = () => {
    let index;
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, index); buffer = buffer.slice(index + 2);
      const event = block.split('\n').find(line => line.startsWith('event:'))?.slice(6).trim();
      const data = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
      if (!data) continue;
      const value = JSON.parse(data); eventCount++;
      if (event === 'delta' && value.text) { firstDeltaMs ??= performance.now() - started; visible += value.text; }
      if (event === 'completed') translation = value.translation;
      if (event === 'error') throw new Error(`SSE ${value.code}`);
    }
  };
  try {
    while (true) {
      const chunk = await reader.read();
      // Normalize only after decoding: preserve a CR split across chunks.
      buffer += decoder.decode(chunk.value ?? new Uint8Array(), { stream: !chunk.done });
      buffer = buffer.replace(/\r\n/g, '\n'); consume();
      if (chunk.done) break;
    }
    if (!translation || firstDeltaMs === null || visible !== translation.translatedText) throw new Error('Incomplete or mismatched stream');
    return { translation, firstDeltaMs, completedMs: performance.now() - started, eventCount,
      missingTokens: row.mustPreserve.filter(token => !translation.translatedText.includes(token)) };
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
const percentile = (values, p) => values.length ? [...values].sort((a, b) => a - b)[Math.ceil(values.length * p) - 1] : null;
const metadata = { runId, mode, startedAt: new Date().toISOString(), corpusSha256: manifest.sha256,
  requestOrigin: base.origin, timingScope: 'HTTP request -> visible SSE delta / completed stream; auth and scene generation measured separately',
  modelSelection: 'unchanged local application config', humanReview: 'PENDING',
  warning: 'No automatic semantic or human quality pass is asserted. Failures remain in the denominator.' };
writeFileSync(resolve(out, 'metadata.json'), JSON.stringify(metadata, null, 2));
async function run(row, index, useScene = true) {
  const began = performance.now();
  try {
    const account = await user(mode === 'load' ? index : Math.floor(index / 40));
    const context = useScene ? await scene(row, account) : { domainId: null };
    const result = { id: row.id, category: row.category, lengthBand: row.lengthBand, ...context,
      ...await translate(row, account, context.domainId), success: true };
    // Cache response timed separately, never blended into model generation percentiles.
    if (mode !== 'load' && index < 5) result.cache = await translate(row, account, context.domainId, false);
    return result;
  } catch (error) { return { id: row.id, category: row.category, lengthBand: row.lengthBand, success: false,
    failedAfterMs: performance.now() - began, error: error.message }; }
}
function record(row) { rows.push(row); appendFileSync(resolve(out, 'results.jsonl'), JSON.stringify(row) + '\n'); console.log(`${rows.length}: ${row.id} ${row.success ? `${Math.round(row.firstDeltaMs)}ms/${Math.round(row.completedMs)}ms` : row.error}`); }
if (mode === 'load') {
  // Pre-admit 20 independent fixture accounts before a shared barrier; all sources differ.
  await Promise.all(Array.from({ length: 20 }, (_, index) => user(index)));
  const start = performance.now();
  await Promise.all(corpus.slice(0, 20).map((row, index) => run(row, index, false).then(record)));
  metadata.batchMs = performance.now() - start; metadata.concurrency = 20;
} else {
  const selected = mode === 'smoke' ? [corpus[0], corpus[60], corpus[61]] : corpus;
  for (const [index, row] of selected.entries()) record(await run(row, index));
  metadata.concurrency = 1;
}
const successful = rows.filter(row => row.success);
const summary = { ...metadata, finishedAt: new Date().toISOString(), total: rows.length, successful: successful.length,
  errors: rows.length - successful.length, errorRate: (rows.length - successful.length) / rows.length,
  firstDeltaP95Ms: percentile(successful.map(row => row.firstDeltaMs), .95),
  tokenPreservationFailures: successful.filter(row => row.missingTokens.length).map(row => row.id),
  lengths: Object.fromEntries(['short', 'medium', 'long'].map(band => [band, {
    total: rows.filter(row => row.lengthBand === band).length,
    completedP95Ms: percentile(successful.filter(row => row.lengthBand === band).map(row => row.completedMs), .95),
    overBudget: successful.filter(row => row.lengthBand === band && row.completedMs > ({ short: 8000, medium: 20000, long: 60000 })[band]).length,
  }])), cache: rows.filter(row => row.cache).map(row => ({ id: row.id, completedMs: row.cache.completedMs })),
};
writeFileSync(resolve(out, 'summary.json'), JSON.stringify(summary, null, 2));
const quote = value => '"' + String(value ?? '').replaceAll('"', '""') + '"';
const header = ['id','category','pairId','variant','source','sceneDescription','reference','reviewCriteria','actual','status','accuracy_1_5','naturalness_1_5','sceneFollow_yes_no','criticalFacts_yes_no','injectionSafe_yes_no','reviewer','notes'];
writeFileSync(resolve(out, 'human-review.csv'), '\ufeff' + [header, ...rows.map(row => {
  const fixture = corpus.find(value => value.id === row.id);
  return [row.id,row.category,fixture.pairId,fixture.variant,fixture.sourceText,fixture.sceneDescription,fixture.reference,fixture.review,row.translation?.translatedText,row.success ? 'OK' : row.error,'','','','','','',''];
})].map(values => values.map(quote).join(',')).join('\r\n'));
console.log(`Report: ${out}; ${summary.successful}/${summary.total}; first delta P95=${summary.firstDeltaP95Ms}ms; HUMAN REVIEW PENDING`);
if (summary.errors) process.exitCode = 1;
