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
if (!['quality', 'load', 'smoke', 'performance'].includes(mode)) throw new Error('Unknown mode');
let sceneSnapshots = null, sceneSourceRun = null, sceneSnapshotSha256 = null;
if (mode === 'performance') {
  if (!args['scene-results']) throw new Error('Performance replay requires a complete quality run scene-results file');
  const source = resolve(args['scene-results']), within = relative(tmp, source);
  if (within.startsWith('..') || isAbsolute(within)) throw new Error('Scene snapshots must be inside tmp');
  const summary = JSON.parse(readFileSync(resolve(dirname(source), 'summary.json'), 'utf8'));
  const bytes = readFileSync(source), results = bytes.toString('utf8').trim().split('\n').map(JSON.parse);
  if (summary.mode !== 'quality' || summary.corpusSha256 !== manifest.sha256 || results.length !== 200 || new Set(results.map(row => row.id)).size !== 200) throw new Error('Scene replay source is not a complete matching quality run');
  sceneSnapshots = new Map(results.map(row => [row.id, row.savedScene]));
  if (corpus.some(row => row.scene && !sceneSnapshots.get(row.id)?.configuration)) throw new Error('A frozen scene configuration is missing');
  sceneSourceRun = summary.runId; sceneSnapshotSha256 = createHash('sha256').update(bytes).digest('hex');
} else if (args['scene-results']) throw new Error('Scene snapshots may only be reused for timing-only performance mode');
const usersFile = args['users-file'] ? resolve(args['users-file']) : null;
if (usersFile) {
  const within = relative(tmp, usersFile);
  if (mode !== 'load' || within.startsWith('..') || isAbsolute(within)) throw new Error('Persistent fixture users are only allowed for load mode inside tmp');
}
const savedUsers = usersFile && existsSync(usersFile) ? JSON.parse(readFileSync(usersFile, 'utf8')) : null;
if (savedUsers && (savedUsers.kind !== 'lexiforge-load-fixture-users-v1' || savedUsers.origin !== base.origin || savedUsers.users?.length !== 20
  || savedUsers.users.some(row => !/^eval-[a-f0-9-]+-\d+@example\.test$/.test(row.email) || typeof row.password !== 'string' || row.password.length < 8))) throw new Error('Fixture users file does not belong to this local load target');
const credentials = savedUsers?.users ?? [];
const developmentCases = [
  { sourceText: 'We need to address this issue.', sourceLanguage: 'EN', targetLanguage: 'ZH' },
  { sourceText: 'Do not write to CTRL_REG unless bit 3 is set. The reset value is 0x10.', sourceLanguage: 'EN', targetLanguage: 'ZH' },
  { sourceText: 'The worker pool is exhausted. Reduce the concurrency limit before retrying the request.', sourceLanguage: 'EN', targetLanguage: 'ZH' },
].map((row, index) => ({ ...row, id: `DEV${index + 1}`, category: 'development', lengthBand: 'short',
  mustPreserve: index === 1 ? ['CTRL_REG', '0x10'] : [], reference: '开发接入样例，不计入独立验收集。', review: '仅验证接入、计时和场景保存流程。',
  ...(index ? { scene: true, sceneDescription: '用于芯片数据手册，面向嵌入式工程师，语言简洁严谨，保留寄存器名称和代码，不添加教学解释。' } : {}) }));
const selectedCases = mode === 'smoke' ? developmentCases : mode === 'load' ? corpus.slice(0, 20) : corpus;
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
  const credential = credentials[index] ?? { email: `eval-${runId}-${index}@example.test`, password: randomUUID() + 'Aa1!' };
  const account = await api(savedUsers ? '/api/auth/login/v1' : '/api/auth/register/v1', savedUsers ? credential : { ...credential, displayName: '固定验收测试' });
  const token = account.accessToken;
  if (!savedUsers) {
    const order = await api('/api/billing/orders/v1', { requestId: randomUUID(), cycle: 'MONTH', channel: 'ALIPAY_SIMULATOR' }, token);
    await api(`/api/billing/orders/${order.id}/sandbox-pay/v1`, {}, token);
  }
  credentials[index] = credential;
  users[index] = { token, sceneId: null, revision: null };
  return users[index];
}
async function scene(row, account) {
  if (!row.scene) return { domainId: null };
  const started = performance.now();
  // Whitelist input: only authored description, never reference/review/mustPreserve.
  const snapshot = sceneSnapshots?.get(row.id);
  const draft = snapshot ? { name: snapshot.name, configuration: snapshot.configuration } : await api('/api/scenes/generate/v1', { requestId: randomUUID(), description: row.sceneDescription }, account.token);
  const body = { name: draft.name, description: snapshot?.description ?? row.sceneDescription, configuration: draft.configuration, expectedRevision: account.revision };
  const saved = await api(account.sceneId ? `/api/domains/${account.sceneId}/v1` : '/api/domains/v1', body, account.token, account.sceneId ? 'PATCH' : 'POST');
  account.sceneId = saved.id; account.revision = saved.revision;
  return { domainId: saved.id, ...(snapshot ? { sceneSourceRun } : { generatedScene: draft }), savedScene: saved,
    sceneMode: snapshot ? 'frozen-snapshot' : 'new-generation', sceneMs: performance.now() - started };
}
async function translate(row, account, domainId, forceRefresh = true) {
  const started = performance.now();
  let firstDeltaMs = null, completedEventMs = null, translation = null, buffer = '', visible = '', eventCount = 0;
  const response = await fetch(new URL('/api/translations/stream/v1', base), {
    method: 'POST', signal: AbortSignal.timeout(100_000),
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${account.token}`, Accept: 'text/event-stream' },
    body: JSON.stringify({ requestId: randomUUID(), sourceText: row.sourceText, sourceLanguage: row.sourceLanguage,
      targetLanguage: row.targetLanguage, domainId, context: '', forceRefresh }),
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
      if (event === 'completed') { translation = value.translation; completedEventMs = performance.now() - started; }
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
    if (!translation || completedEventMs === null || typeof translation.translatedText !== 'string' || !translation.translatedText
      || (firstDeltaMs !== null && visible !== translation.translatedText)) throw new Error('Incomplete or mismatched stream');
    return { translation, firstDeltaMs, firstVisibleMs: firstDeltaMs ?? completedEventMs,
      completedMs: completedEventMs, streamClosedMs: performance.now() - started,
      delivery: firstDeltaMs === null ? 'completed-only' : 'streamed', eventCount,
      missingTokens: row.mustPreserve.filter(token => !translation.translatedText.includes(token)) };
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
const percentile = (values, p) => values.length ? [...values].sort((a, b) => a - b)[Math.ceil(values.length * p) - 1] : null;
const metadata = { runId, mode, startedAt: new Date().toISOString(), corpusSha256: manifest.sha256,
  requestOrigin: base.origin, timingVersion: 'http-timing-v2', timingScope: 'HTTP request -> first visible text (delta or completed) / completed event; stream closure, auth and scene generation separate',
  modelSelection: 'unchanged local application config', sceneSourceRun, sceneSnapshotSha256,
  fixtureAccounts: usersFile ? (savedUsers ? 'existing 20 fixtures, fresh login' : 'create 20 fixtures once') : 'new fixtures for this run',
  humanReview: mode === 'performance' ? 'TIMING_ONLY_NO_SCORES_REUSED' : 'PENDING',
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
    if (mode !== 'load' && index < 5) {
      try { result.cache = { success: true, ...await translate(row, account, context.domainId, false) }; }
      catch (error) { result.cache = { success: false, error: error.message }; }
    }
    return result;
  } catch (error) { return { id: row.id, category: row.category, lengthBand: row.lengthBand, success: false,
    failedAfterMs: performance.now() - began, error: error.message }; }
}
function record(row) { rows.push(row); appendFileSync(resolve(out, 'results.jsonl'), JSON.stringify(row) + '\n'); console.log(`${rows.length}: ${row.id} ${row.success ? `${Math.round(row.firstVisibleMs)}ms/${Math.round(row.completedMs)}ms` : row.error}`); }
if (mode === 'load') {
  // Pre-admit 20 independent fixture accounts before a shared barrier; all sources differ.
  await Promise.all(Array.from({ length: 20 }, (_, index) => user(index)));
  if (usersFile && !savedUsers) writeFileSync(usersFile, JSON.stringify({ kind: 'lexiforge-load-fixture-users-v1', origin: base.origin, users: credentials }, null, 2), { flag: 'wx', mode: 0o600 });
  const start = performance.now();
  await Promise.all(corpus.slice(0, 20).map((row, index) => run(row, index, false).then(record)));
  metadata.batchMs = performance.now() - start; metadata.concurrency = 20;
} else {
  for (const [index, row] of selectedCases.entries()) record(await run(row, index));
  metadata.concurrency = 1;
}
const successful = rows.filter(row => row.success);
const summary = { ...metadata, finishedAt: new Date().toISOString(), total: rows.length, successful: successful.length,
  errors: rows.length - successful.length, errorRate: (rows.length - successful.length) / rows.length,
  firstVisibleP95Ms: percentile(successful.map(row => row.firstVisibleMs), .95),
  firstDeltaSamples: successful.filter(row => row.firstDeltaMs !== null).length,
  firstDeltaP95Ms: percentile(successful.map(row => row.firstDeltaMs).filter(value => value !== null), .95),
  tokenPreservationFailures: successful.filter(row => row.missingTokens.length).map(row => row.id),
  lengths: Object.fromEntries(['short', 'medium', 'long'].map(band => [band, {
    total: rows.filter(row => row.lengthBand === band).length,
    completedP95Ms: percentile(successful.filter(row => row.lengthBand === band).map(row => row.completedMs), .95),
    overBudget: successful.filter(row => row.lengthBand === band && row.completedMs > ({ short: 8000, medium: 20000, long: 60000 })[band]).length,
  }])), cache: rows.filter(row => row.cache).map(row => ({ id: row.id, success: row.cache.success, delivery: row.cache.delivery,
    firstVisibleMs: row.cache.firstVisibleMs, completedMs: row.cache.completedMs, streamClosedMs: row.cache.streamClosedMs, error: row.cache.error })),
};
writeFileSync(resolve(out, 'summary.json'), JSON.stringify(summary, null, 2));
const quote = value => '"' + String(value ?? '').replaceAll('"', '""') + '"';
const header = ['id','category','pairId','variant','source','sceneDescription','reference','reviewCriteria','actual','status','accuracy_1_5','naturalness_1_5','sceneFollow_yes_no','criticalFacts_yes_no','injectionSafe_yes_no','reviewer','notes'];
if (mode !== 'performance') {
writeFileSync(resolve(out, 'human-review.csv'), '\ufeff' + [header, ...rows.map(row => {
  const fixture = selectedCases.find(value => value.id === row.id);
  return [row.id,row.category,fixture.pairId,fixture.variant,fixture.sourceText,fixture.sceneDescription,fixture.reference,fixture.review,row.translation?.translatedText,row.success ? 'OK' : row.error,'','','','','','',''];
})].map(values => values.map(quote).join(',')).join('\r\n'));
writeFileSync(resolve(out, 'human-review.json'), JSON.stringify({ corpusSha256: manifest.sha256, runId,
  rows: rows.map(row => ({ id: row.id, accuracy: null, naturalness: null, sceneFollow: null,
    criticalFactsPreserved: null, injectionSafe: null, reviewer: '', notes: '' })) }, null, 2));
}
console.log(`Report: ${out}; ${summary.successful}/${summary.total}; first visible P95=${summary.firstVisibleP95Ms}ms; ${mode === 'performance' ? 'TIMING ONLY, NO QUALITY PASS ASSERTED' : 'HUMAN REVIEW PENDING'}`);
if (summary.errors) process.exitCode = 1;
