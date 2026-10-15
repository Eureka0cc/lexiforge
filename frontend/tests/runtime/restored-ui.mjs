import { chromium, expect } from '@playwright/test'
import path from 'node:path'
import { writeFileSync } from 'node:fs'
const [baseURL, directory, label] = process.argv.slice(2)
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseURL) || !/^[a-z-]+$/.test(label)) throw new Error('invalid local fixture parameters')
const browser = await chromium.launch()
let page
const runtimeErrors = []
try {
  page = await browser.newPage({ viewport: { width: 1366, height: 900 } })
  page.on('pageerror', error => runtimeErrors.push(error.message))
  await page.addInitScript(({ user, token }) => {
    localStorage.setItem('user', JSON.stringify(user)); localStorage.setItem('access_token', token)
  }, { user: JSON.parse(process.env.LEXIFORGE_FIXTURE_USER), token: process.env.LEXIFORGE_FIXTURE_ACCESS })
  await page.goto(baseURL + '/domains')
  await expect(page.getByText('恢复验收场景', { exact: true }).first()).toBeVisible()
  await page.goto(baseURL + '/vocabulary')
  await expect(page.getByText('recovery', { exact: true }).first()).toBeVisible()
  await page.goto(baseURL + '/translate')
  await page.getByRole('button', { name: /最近翻译/ }).click()
  await expect(page.getByText('database restore fixture', { exact: true })).toBeVisible()
  await page.goto(baseURL + '/plans')
  await expect(page.getByText('当前：Pro', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sandbox 续费 Pro', exact: true })).toBeVisible()
  await page.screenshot({ path: path.join(directory, `ui-${label}.png`), fullPage: true })
  writeFileSync(path.join(directory, `ui-${label}.json`), JSON.stringify({ passed: true, label, pages: ['domains', 'vocabulary', 'translate history', 'plans'], mockedBrowserRoutes: false }, null, 2))
  console.log(`PASS: ${label} production assets with real restored backend`)
} catch (error) {
  if (page) {
    await page.screenshot({ path: path.join(directory, `ui-${label}-failure.png`), fullPage: true }).catch(() => {})
    writeFileSync(path.join(directory, `ui-${label}-failure.json`), JSON.stringify({ url: page.url(), runtimeErrors, body: await page.locator('body').innerText().catch(() => '') }, null, 2))
  }
  throw error
} finally { await browser.close() }
