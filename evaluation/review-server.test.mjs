import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { startReviewServer, completeReview } from './review-server.mjs';
import { reportFixture } from './fixtures/review-report.mjs';

test('partial human review saves UTF-8 atomically, rejects stale/invalid/cross-origin writes and reloads external edits', async () => {
  const directory = reportFixture(), server = await startReviewServer(directory);
  try {
    const report = await (await fetch(server.url + '/api/report')).json();
    assert.equal(report.cases.length, 200);
    assert.ok(report.cases.every(item => item.review.accuracy === null));
    const review = { runId: report.runId, corpusSha256: report.corpusSha256, rows: report.cases.map(item => item.review) };
    Object.assign(review.rows[0], { accuracy: 4, naturalness: 5, criticalFactsPreserved: false, reviewer: '测🧪试', notes: '数字/否定需要人工核对，不是自动通过。' });
    const bytes = Buffer.from(JSON.stringify(review)), split = bytes.indexOf(Buffer.from('测')) + 1;
    async function* chunks() { yield bytes.subarray(0, split); yield bytes.subarray(split); }
    const saved = await fetch(server.url + '/api/review', { method: 'PUT', duplex: 'half', headers: { 'Content-Type': 'application/json', 'If-Match': report.etag }, body: chunks() });
    assert.equal(saved.status, 200); const value = await saved.json(); assert.equal(value.reviewed, 1);
    const file = JSON.parse(readFileSync(path.join(directory, 'human-review.json')));
    assert.equal(file.rows[0].reviewer, '测🧪试'); assert.equal(file.rows[0].criticalFactsPreserved, false);
    const put = (body, etag = value.etag, origin) => fetch(server.url + '/api/review', { method: 'PUT', headers: { 'Content-Type': 'application/json', 'If-Match': etag, ...(origin ? { Origin: origin } : {}) }, body: JSON.stringify(body) });
    assert.equal((await put(review, report.etag)).status, 409);
    review.rows[0].accuracy = 0; assert.equal((await put(review)).status, 400); review.rows[0].accuracy = 4;
    assert.equal((await put(review, value.etag, 'https://unrelated.example')).status, 403);
    assert.equal(JSON.parse(readFileSync(path.join(directory, 'human-review.json'))).rows[0].accuracy, 4);
    file.rows[1].notes = '外部编辑器中的新修改'; writeFileSync(path.join(directory, 'human-review.json'), JSON.stringify(file));
    const reloaded = await (await fetch(server.url + '/api/report')).json();
    assert.notEqual(reloaded.etag, value.etag); assert.equal(reloaded.cases[1].review.notes, file.rows[1].notes);
  } finally { await server.close(); }
});
test('mechanical warnings require an explicit human decision and a reason for false positives', () => {
  const row = { reviewer: '协议测试', accuracy: 4, naturalness: 4, criticalFactsPreserved: true, mechanicalFalsePositive: null, notes: '' };
  const fixture = { category: 'general' }, actual = { missingTokens: ['fixture-literal'] };
  assert.equal(completeReview(row, fixture, actual), false);
  row.mechanicalFalsePositive = true;
  assert.equal(completeReview(row, fixture, actual), false);
  row.notes = '提取器把应翻译的文本片段误识别为代码';
  assert.equal(completeReview(row, fixture, actual), true);
  row.mechanicalFalsePositive = false; row.notes = '';
  assert.equal(completeReview(row, fixture, actual), true); // Complete assessment can still fail acceptance.
});
