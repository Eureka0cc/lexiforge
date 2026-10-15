import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';

// Protocol fixture only: proves runner behavior, never model quality/performance.
async function fixture(callback, expectedConcurrency = 1, failFirstStream = false) {
  const calls = []; let scenes = 0, active = 0, peak = 0;
  let streamCount = 0;
  const waiting = [];
  const server = createServer(async (request, response) => {
    let raw = ''; for await (const chunk of request) raw += chunk;
    const body = raw ? JSON.parse(raw) : undefined;
    calls.push({ path: request.url, method: request.method, body });
    const ok = data => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ success: true, data })); };
    if (request.url === '/api/auth/register/v1') return ok({ accessToken: 'fixture-token' });
    if (request.url === '/api/billing/orders/v1') return ok({ id: '1' });
    if (request.url.includes('/sandbox-pay/')) return ok({ status: 'PAID' });
    if (request.url === '/api/scenes/generate/v1') return ok({ name: 'FIXTURE', configuration: { domain: 'fixture', style: body.description } });
    if (request.url.startsWith('/api/domains/')) {
      assert.ok(['POST', 'PATCH'].includes(request.method));
      return ok({ ...body, id: '10', revision: ++scenes });
    }
    if (request.url === '/api/translations/stream/v1') {
      assert.deepEqual(Object.keys(body).sort(), ['requestId','sourceText','sourceLanguage','targetLanguage','domainId','context','forceRefresh'].sort());
      if (failFirstStream && streamCount++ === 0) {
        response.setHeader('Content-Type', 'text/event-stream');
        response.end('event: error\ndata: {"code":"AI_TIMEOUT"}\n\n');
        return;
      }
      active++; peak = Math.max(peak, active);
      response.setHeader('Content-Type', 'text/event-stream');
      const actual = `测试🙂${body.sourceText}`;
      const wire = Buffer.from(`event: started\r\ndata: {}\r\n\r\nevent: delta\r\ndata: ${JSON.stringify({ text: actual })}\r\n\r\nevent: completed\r\ndata: ${JSON.stringify({ translation: { translatedText: actual } })}\r\n\r\n`);
      // Split UTF-8 and CRLF across transport chunks deliberately.
      for (let index = 0; index < wire.length; index += 7) response.write(wire.subarray(index, index + 7));
      if (expectedConcurrency > 1) {
        waiting.push(() => { active--; response.end(); });
        if (waiting.length === expectedConcurrency) for (const finish of waiting) finish();
      } else setTimeout(() => { active--; response.end(); }, 10);
      return;
    }
    response.writeHead(404); response.end('{}');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try { await callback({ base: `http://127.0.0.1:${server.address().port}`, calls, get peak() { return peak; } }); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}
async function run(base, mode, expectedCode = 0) {
  const output = resolve('../tmp/evaluation-protocol-tests', randomUUID()); mkdirSync(output, { recursive: true });
  const child = spawn(process.execPath, ['evaluation/run-http.mjs', `--base=${base}`, `--mode=${mode}`, `--output=${output}`], { stdio: ['ignore', 'pipe', 'pipe'] });
  let log = ''; child.stdout.on('data', data => log += data); child.stderr.on('data', data => log += data);
  const code = await new Promise((resolve, reject) => { child.on('error', reject); child.on('exit', resolve); });
  assert.equal(code, expectedCode, log);
  return { output, summary: JSON.parse(readFileSync(resolve(output, 'summary.json'))) };
}
test('complete quality protocol preserves all rows and keeps references out of requests', async () => {
  await fixture(async ({ base, calls }) => {
    const { output, summary } = await run(base, 'quality');
    assert.equal(summary.total, 200); assert.equal(summary.successful, 200);
    assert.equal(summary.humanReview, 'PENDING'); assert.equal(summary.cache.length, 5);
    assert.equal(calls.filter(call => call.path === '/api/scenes/generate/v1').length, 120);
    assert.equal(calls.filter(call => call.path.startsWith('/api/domains/') && call.method === 'PATCH').length, 116);
    for (const call of calls.filter(call => call.path === '/api/scenes/generate/v1')) assert.deepEqual(Object.keys(call.body).sort(), ['description','requestId']);
    const reviews = JSON.parse(readFileSync(resolve(output, 'human-review.json')));
    assert.equal(reviews.rows.length, 200); assert.ok(reviews.rows.every(row => row.accuracy === null && row.reviewer === ''));
    const scorer = spawn(process.execPath, ['evaluation/score-human-review.mjs', output], { stdio: ['ignore', 'pipe', 'pipe'] });
    let text = ''; scorer.stdout.on('data', value => text += value); scorer.stderr.on('data', value => text += value);
    assert.equal(await new Promise(resolve => scorer.on('exit', resolve)), 2);
    assert.equal(JSON.parse(text).status, 'PENDING');
  });
});
test('load mode really overlaps 20 distinct HTTP streams after fixture admission', async () => {
  await fixture(async state => {
    const { summary } = await run(state.base, 'load');
    assert.equal(summary.total, 20); assert.equal(summary.successful, 20); assert.equal(state.peak, 20);
    const requests = state.calls.filter(call => call.path === '/api/translations/stream/v1');
    assert.equal(new Set(requests.map(call => call.body.sourceText)).size, 20);
    assert.ok(requests.every(call => call.body.forceRefresh));
  }, 20);
});
test('a failed stream stays in the report denominator and does not stop later cases', async () => {
  await fixture(async ({ base }) => {
    const { output, summary } = await run(base, 'smoke', 1);
    assert.equal(summary.total, 3); assert.equal(summary.successful, 2); assert.equal(summary.errors, 1);
    const results = readFileSync(resolve(output, 'results.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
    assert.equal(results[0].error, 'SSE AI_TIMEOUT');
    assert.deepEqual(results.map(row => row.id), ['DEV1','DEV2','DEV3']);
  }, 1, true);
});
