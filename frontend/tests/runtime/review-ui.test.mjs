import test from 'node:test';
import assert from 'node:assert/strict';
import { chromium, expect } from '@playwright/test';
import path from 'node:path';
import { readFileSync } from 'node:fs';
import { reportFixture } from '../../../evaluation/fixtures/review-report.mjs';
import { startReviewServer } from '../../../evaluation/review-server.mjs';

test('review page keeps scores blank, renders hostile output as text and persists explicit human input', async () => {
  const directory = reportFixture({ warning: true }), server = await startReviewServer(directory), browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1366, height: 950 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(server.url);
    await expect(page.locator('#progress')).toHaveText('已完整评分 0 / 200');
    await expect(page.locator('#accuracy')).toHaveValue('');
    await expect(page.locator('#actual')).toContainText('<img src=x');
    await expect(page.locator('#actual img')).toHaveCount(0);
    assert.equal(await page.evaluate(() => window.reviewXss), undefined);
    await page.locator('#reviewer').fill('界面协议测试');
    await page.locator('#accuracy').selectOption('4');
    await page.locator('#naturalness').selectOption('5');
    await page.locator('#criticalFactsPreserved').selectOption('false');
    await expect(page.locator('#mechanical-field')).toBeVisible();
    await page.locator('#mechanicalFalsePositive').selectOption('false');
    await page.locator('#notes').fill('人工指出关键事实错误 🧪');
    await page.locator('#save').click();
    await expect(page.locator('#save-state')).toContainText('已保存');
    await expect(page.locator('#progress')).toHaveText('已完整评分 1 / 200');
    await page.reload();
    await expect(page.locator('#notes')).toHaveValue('人工指出关键事实错误 🧪');
    await expect(page.locator('#criticalFactsPreserved')).toHaveValue('false');
    const saved = JSON.parse(readFileSync(path.join(directory, 'human-review.json')));
    assert.equal(saved.rows[0].reviewer, '界面协议测试'); assert.equal(saved.rows[1].accuracy, null);
    assert.equal(saved.rows[0].mechanicalFalsePositive, false);
    await page.locator('#case').selectOption('A061');
    await expect(page.locator('#scene-field')).toBeVisible();
    await expect(page.locator('#pair-details')).toBeVisible();
    await page.locator('#category').selectOption('injection');
    await expect(page.locator('#case option')).toHaveCount(20);
    await expect(page.locator('#injection-field')).toBeVisible();
    assert.equal(errors.length, 0);
    await page.screenshot({ path: path.join(directory, 'review-ui.png'), fullPage: true });
  } finally { await browser.close(); await server.close(); }
});
