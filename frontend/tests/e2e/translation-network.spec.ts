import { test, expect, type Page, type Route } from '@playwright/test'

const user = { id: '7', email: 'ui@example.test', displayName: '验收用户', role: 'USER' }
const domains = [{ id: '2001', name: 'IT', code: 'IT', builtIn: true }]
async function session(page: Page) {
  await page.goto('/login')
  await page.evaluate(user => {
    localStorage.setItem('user', JSON.stringify(user)); localStorage.setItem('access_token', 'expired-token')
  }, user)
}
async function ok(route: Route, data: unknown) { await route.fulfill({ json: { success: true, data } }) }
async function complete(route: Route) {
  const body = route.request().postDataJSON()
  await route.fulfill({ contentType: 'text/event-stream', body: `event: delta\ndata: {"text":"续期后的结果"}\n\nevent: completed\ndata: ${JSON.stringify({ translation: { id: '11', ...body, translatedText: '续期后的结果', alternatives: [] } })}\n\n` })
}

test('多个初始化请求同时 401 只续期一次，续期后分别恢复', async ({ page }) => {
  await session(page)
  let failed = 0, restored = 0, refreshes = 0, refresh: Route | undefined
  await page.route('**/api/domains/v1', async route => {
    if (route.request().headers().authorization === 'Bearer expired-token') { failed++; await route.fulfill({ status: 401, json: {} }) }
    else { restored++; await ok(route, domains) }
  })
  await page.route('**/api/translations/v1*', async route => {
    if (route.request().headers().authorization === 'Bearer expired-token') { failed++; await route.fulfill({ status: 401, json: {} }) }
    else { restored++; await ok(route, { items: [] }) }
  })
  await page.route('**/api/auth/refresh/v1', route => { refreshes++; refresh = route })
  await page.route('**/api/translations/stream/v1', complete)
  await page.goto('/translate')
  await expect.poll(() => failed).toBe(2)
  await expect.poll(() => refreshes).toBe(1)
  await ok(refresh!, { accessToken: 'renewed-token', user })
  await expect.poll(() => restored).toBe(2)
  await page.getByRole('textbox', { name: '待翻译原文' }).fill('Test refresh')
  await page.getByRole('button', { name: /立即翻译/ }).click()
  await expect(page.locator('.translated-document')).toContainText('续期后的结果')
  expect(refreshes).toBe(1)
  expect(await page.evaluate(() => localStorage.getItem('access_token'))).toBe('renewed-token')
})

test('流式请求仅在生成开始前的 401 续期重发一次，保持原 requestId', async ({ page }) => {
  await session(page)
  let refreshes = 0
  const attempts: { id: string; authorization: string | undefined }[] = []
  await page.route('**/api/domains/v1', route => ok(route, domains))
  await page.route('**/api/translations/v1*', route => ok(route, { items: [] }))
  await page.route('**/api/auth/refresh/v1', route => { refreshes++; return ok(route, { accessToken: 'renewed-token', user }) })
  await page.route('**/api/translations/stream/v1', async route => {
    attempts.push({ id: route.request().postDataJSON().requestId, authorization: route.request().headers().authorization })
    if (attempts.length === 1) await route.fulfill({ status: 401, json: { message: '请续期' } })
    else await complete(route)
  })
  await page.goto('/translate')
  await page.getByRole('textbox', { name: '待翻译原文' }).fill('Test stream refresh')
  await page.getByRole('button', { name: /立即翻译/ }).click()
  await expect(page.locator('.translated-document')).toContainText('续期后的结果')
  expect(attempts).toHaveLength(2); expect(attempts[0]!.id).toBe(attempts[1]!.id)
  expect(attempts.map(value => value.authorization)).toEqual(['Bearer expired-token', 'Bearer renewed-token'])
  expect(refreshes).toBe(1)
})
