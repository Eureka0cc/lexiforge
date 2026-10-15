import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export function reportFixture({ warning = false } = {}) {
  const here = fileURLToPath(new URL('../', import.meta.url));
  const corpus = readFileSync(path.join(here, 'acceptance-v1.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
  const manifest = JSON.parse(readFileSync(path.join(here, 'acceptance-v1.manifest.json')));
  const directory = path.resolve(here, '../../tmp/review-protocol-tests', randomUUID()); mkdirSync(directory, { recursive: true });
  const runId = randomUUID(), corpusSha256 = manifest.sha256;
  const summary = { runId, corpusSha256, mode: 'quality', total: 200, successful: 200, sampleKind: 'PROTOCOL_FIXTURE_ONLY_NOT_MODEL_ACCEPTANCE' };
  writeFileSync(path.join(directory, 'summary.json'), JSON.stringify(summary));
  writeFileSync(path.join(directory, 'results.jsonl'), corpus.map((row, index) => JSON.stringify({ id: row.id, success: true,
    translation: { translatedText: index ? '仅用于界面协议检查的合成内容' : '<img src=x onerror="window.reviewXss=true">' }, missingTokens: warning && index === 0 ? ['fixture-literal'] : [] })).join('\n'));
  writeFileSync(path.join(directory, 'human-review.json'), JSON.stringify({ runId, corpusSha256, rows: corpus.map(row => ({ id: row.id, accuracy: null, naturalness: null,
    sceneFollow: null, criticalFactsPreserved: null, injectionSafe: null, reviewer: '', notes: '' })) }));
  return directory;
}
