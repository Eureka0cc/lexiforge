import { test, expect, type Page } from '@playwright/test'

const user = { id: '8', email: 'new@example.test', displayName: '新账户', role: 'USER' }
const previous = { id: '11', sourceText: 'Previous account private source', translatedText: '上个账户的私有译文', sourceLanguage: 'EN', targetLanguage: 'ZH', domainId: '2001', alternatives: [], terms: [], createdAt: '2026-10-14T00:00:00Z' }
async function fresh(page: Page) {
  await page.goto('/login')
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); localStorage.setItem('unrelated-preference', 'preserve') })
}
async function api(page: Page) {
  await page.route('**/api/domains/v1', route => route.fulfill({ json: { success: true, data: [{ id: '2001', name: 'IT', code: 'IT', builtIn: true }] } }))
  await page.route('**/api/translations/v1*', route => route.fulfill({ json: { success: true, data: { items: [] } } }))
}

test('登录新账户时不恢复上一账户遗留的继续翻译内容', async ({ page }) => {
  await fresh(page); await api(page)
  await page.evaluate(value => sessionStorage.setItem('lexiforge:last-translation', JSON.stringify(value)), previous)
  await page.route('**/api/auth/login/v1', route => route.fulfill({ json: { success: true, data: { accessToken: 'new-token', user } } }))
  await page.getByPlaceholder('you@example.com').fill(user.email)
  await page.getByPlaceholder('至少 8 位').fill('Password123!')
  await page.locator('.auth-submit').click()
  await expect(page).toHaveURL(/\/translate$/)
  await expect(page.locator('.source-editor')).toHaveValue('')
  await expect(page.locator('.translated-document')).toHaveCount(0)
  expect(await page.evaluate(() => sessionStorage.getItem('lexiforge:last-translation'))).toBeNull()
  expect(await page.evaluate(() => localStorage.getItem('unrelated-preference'))).toBe('preserve')
})

test('续期失败清除登录和继续翻译记录，保留无关偏好', async ({ page }) => {
  await fresh(page)
  await page.evaluate(({ user, previous }) => {
    localStorage.setItem('user', JSON.stringify(user)); localStorage.setItem('access_token', 'expired-token')
    sessionStorage.setItem('lexiforge:last-translation', JSON.stringify(previous))
  }, { user, previous })
  await page.route('**/api/account/v1', route => route.fulfill({ status: 401, json: { message: '会话过期' } }))
  await page.route('**/api/auth/refresh/v1', route => route.fulfill({ status: 401, json: { message: '无法续期' } }))
  await page.goto('/account')
  await expect(page.getByRole('heading', { name: '登录 LexiForge' })).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.getItem('lexiforge:last-translation'))).toBeNull()
  expect(await page.evaluate(() => localStorage.getItem('access_token'))).toBeNull()
  expect(await page.evaluate(() => localStorage.getItem('unrelated-preference'))).toBe('preserve')
})

test('其他账户的带归属记录和旧无归属记录都不能恢复', async ({ page }) => {
  await fresh(page); await api(page)
  for (const pending of [{ ownerId: '7', translation: previous }, previous]) {
    await page.evaluate(({ user, pending }) => {
      localStorage.setItem('user', JSON.stringify(user)); localStorage.setItem('access_token', 'new-token')
      sessionStorage.setItem('lexiforge:last-translation', JSON.stringify(pending))
    }, { user, pending })
    await page.goto('/translate')
    await expect(page.locator('.source-editor')).toHaveValue('')
    await expect(page.locator('.translated-document')).toHaveCount(0)
    expect(await page.evaluate(() => sessionStorage.getItem('lexiforge:last-translation'))).toBeNull()
  }
})

test('同一账户从学习概览继续翻译，使用一次后清除临时记录', async ({ page }) => {
  await fresh(page); await api(page)
  await page.evaluate(user => { localStorage.setItem('user', JSON.stringify(user)); localStorage.setItem('access_token', 'new-token') }, user)
  await page.route('**/api/translations/v1?size=1', route => route.fulfill({ json: { success: true, data: { items: [previous] } } }))
  await page.route('**/api/dashboard/v1', route => route.fulfill({ json: { success: true, data: { vocabularyCount: 1, dueCount: 0, reviewedToday: 0, accuracy7d: 0, streakDays: 0 } } }))
  await page.route('**/api/usage/v1', route => route.fulfill({ json: { success: true, data: { plan: { name: 'Free', translationLimit: 10, gradingLimit: 10 }, translationsUsed: 0, gradingsUsed: 0 } } }))
  await page.goto('/learning')
  await page.getByRole('button', { name: '继续翻译', exact: true }).click()
  await expect(page.locator('.source-editor')).toHaveValue(previous.sourceText)
  await expect(page.locator('.translated-document')).toContainText(previous.translatedText)
  expect(await page.evaluate(() => sessionStorage.getItem('lexiforge:last-translation'))).toBeNull()
})

test('另一标签页切换账户后刷新已挂载页面，不保留旧账户内存中的译文', async ({ page, context }) => {
  await fresh(page); await api(page)
  await page.evaluate(({ user, previous }) => {
    localStorage.setItem('user', JSON.stringify(user)); localStorage.setItem('access_token', 'old-token')
    sessionStorage.setItem('lexiforge:last-translation', JSON.stringify({ ownerId: user.id, translation: previous }))
  }, { user: { ...user, id: '7' }, previous })
  await page.goto('/translate')
  await expect(page.locator('.source-editor')).toHaveValue(previous.sourceText)
  const other = await context.newPage()
  await api(other)
  await other.goto('/translate')
  await other.evaluate(user => { localStorage.setItem('access_token', 'new-token'); localStorage.setItem('user', JSON.stringify(user)) }, user)
  await expect(page.locator('.source-editor')).toHaveValue('')
  await expect(page.locator('.translated-document')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'AI 翻译', exact: true })).toBeVisible()
  await other.close()
})
