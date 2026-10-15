import { chromium, devices, expect } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import os from 'node:os';
import { startTranslationUiServer } from '../../../scripts/fixtures/translation-ui-server.mjs';

const repo = fileURLToPath(new URL('../../../', import.meta.url));
const tmp = path.resolve(repo, '../tmp');
const root = path.join(tmp, 'frontend-dist');
const output = path.join(tmp, 'ui-feedback', randomUUID());
mkdirSync(output, { recursive: true });
const hash = data => createHash('sha256').update(data).digest('hex');
const points = [];
const environment = {
  measuredAt: new Date().toISOString(), browser: null, node: process.version,
  platform: os.platform(), architecture: os.arch(), logicalCPUs: os.cpus().length,
  cpuModel: os.cpus()[0]?.model, memoryBytes: os.totalmem(), headless: true,
  indexSha256: hash(readFileSync(path.join(root, 'index.html'))),
  sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim(),
  sourceDiffSha256: hash(execFileSync('git', ['diff', 'HEAD', '--', 'frontend/src'], { cwd: repo })),
  workingTreeDirty: !!execFileSync('git', ['status', '--porcelain'], { cwd: repo, encoding: 'utf8' }).trim(),
  scriptSha256: hash(readFileSync(fileURLToPath(import.meta.url))),
  scope: 'Production assets; trusted browser click event -> second animation-frame boundary after visible action-button update. Includes a rendering opportunity; excludes OS input latency, initial page loading and model generation.',
  network: 'loopback HTTP fixture (pending translation SSE / scene JSON), no AI; no browser routes or mocked clocks',
  budgetMs: 100, samplesPerDevicePerAction: 30,
};

async function arm(page, expectedText, loading = false) {
  await page.evaluate(expected => {
    window.__feedbackResult = new Promise((resolve, reject) => {
      let clickedAt = null, observed = false, timeout;
      const visible = () => [...document.querySelectorAll('button')].some(button => {
        const rect = button.getBoundingClientRect();
        return button.textContent.replace(/\s/g, '') === expected.text && (!expected.loading || button.classList.contains('ant-btn-loading'))
          && rect.width > 0 && rect.height > 0 && getComputedStyle(button).visibility !== 'hidden';
      });
      const capture = () => {
        if (clickedAt === null || observed || !visible()) return;
        observed = true; observer.disconnect();
        requestAnimationFrame(() => requestAnimationFrame(() => {
          clearTimeout(timeout); resolve(performance.now() - clickedAt);
        }));
      };
      const observer = new MutationObserver(capture);
      observer.observe(document.body, { childList: true, subtree: true, attributes: true, characterData: true });
      document.addEventListener('click', () => {
        clickedAt = performance.now();
        timeout = setTimeout(() => { observer.disconnect(); reject(new Error('Visible action feedback did not arrive')); }, 5000);
        capture();
      }, { once: true, capture: true });
    });
  }, { text: expectedText.replace(/\s/g, ''), loading });
}

const server = await startTranslationUiServer({ root });
let browser;
let completed = false;
try {
  browser = await chromium.launch(); environment.browser = browser.version();
  for (const [device, options] of [
    ['desktop', { viewport: { width: 1366, height: 900 } }],
    ['mobile', devices['Pixel 7']],
  ]) {
    const context = await browser.newContext(options);
    try {
      const page = await context.newPage();
      const pageErrors = []; page.on('pageerror', error => pageErrors.push(error.message));
      await page.addInitScript(() => {
        localStorage.setItem('user', JSON.stringify({ id: '7', email: 'feedback@example.test', displayName: '计时测试', role: 'USER' }));
        localStorage.setItem('access_token', 'local-ui-fixture');
      });
      await page.goto(server.url + '/translate');
      const editor = page.getByRole('textbox', { name: '待翻译原文' });
      await expect(editor).toBeVisible();
      await expect(page.getByRole('button', { name: /立即翻译/ })).toBeVisible();
      await page.evaluate(() => document.fonts.ready);
      for (let sample = 1; sample <= 30; sample++) {
        await editor.fill(`UI feedback sample ${sample} for ${device}.`);
        const requestCount = server.state.requests.length;
        await arm(page, '停止');
        await page.getByRole('button', { name: /立即翻译/ }).click();
        const startMs = await page.evaluate(() => window.__feedbackResult);
        points.push({ device, sample, action: 'start', ms: startMs });
        await expect.poll(() => server.state.requests.length).toBe(requestCount + 1);
        const closed = server.state.connectionsClosed;
        await arm(page, '立即翻译');
        await page.getByRole('button', { name: /^停\s*止$/ }).click();
        const stopMs = await page.evaluate(() => window.__feedbackResult);
        points.push({ device, sample, action: 'stop', ms: stopMs });
        await expect.poll(() => server.state.connectionsClosed).toBe(closed + 1);
      }
      if (pageErrors.length) throw new Error(`Browser errors during measurement: ${pageErrors.length}`);
      await page.screenshot({ path: path.join(output, `${device}.png`), fullPage: true });
      for (let sample = 1; sample <= 30; sample++) {
        await page.goto(server.url + '/domains');
        await page.getByRole('button', { name: '定制翻译场景', exact: true }).click();
        const dialog = page.getByRole('dialog');
        await dialog.getByLabel('告诉 AI 你的翻译需求').fill('用于技术文档，面向开发者，保留代码，表达自然简洁。');
        const sceneCount = server.state.sceneRequests.length;
        await arm(page, 'AI 生成场景草稿', true);
        await dialog.getByRole('button', { name: 'AI 生成场景草稿', exact: true }).click();
        const ms = await page.evaluate(() => window.__feedbackResult);
        points.push({ device, sample, action: 'scene-generate', ms });
        await expect.poll(() => server.state.sceneRequests.length).toBe(sceneCount + 1);
      }
      await page.screenshot({ path: path.join(output, `${device}-scene.png`), fullPage: true });
      if (pageErrors.length) throw new Error(`Browser errors during measurement: ${pageErrors.length}`);
    } finally { await context.close(); }
  }
  completed = true;
} finally {
  await browser?.close(); await server.close();
  const groups = [];
  for (const device of ['desktop', 'mobile']) for (const action of ['start', 'stop', 'scene-generate']) {
    const values = points.filter(row => row.device === device && row.action === action).map(row => row.ms).sort((a, b) => a - b);
    groups.push({ device, action, samples: values.length, p95Ms: values[Math.ceil(values.length * .95) - 1] ?? null,
      maxMs: values.at(-1) ?? null, overBudget: values.filter(ms => ms > 100).length });
  }
  const passed = completed && points.length === 180 && points.every(row => row.ms <= 100);
  writeFileSync(path.join(output, 'result.json'), JSON.stringify({ ...environment, passed, groups, points,
    fixtureRequests: server.state.requests.length, fixtureConnectionsClosed: server.state.connectionsClosed,
    sceneRequests: server.state.sceneRequests.length, sceneConnectionsClosed: server.state.sceneConnectionsClosed }, null, 2));
  console.log(JSON.stringify({ passed, output, groups }, null, 2));
  if (!passed) process.exitCode = 1;
}
