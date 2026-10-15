import { test, expect, type Page } from '@playwright/test'

const user = { id: '7', email: 'ui@example.test', displayName: '验收用户', role: 'USER', emailVerified: false }
async function session(page: Page, malformed = false) {
  await page.addInitScript(({ user, malformed }) => {
    localStorage.setItem('user', malformed ? '{broken' : JSON.stringify(user))
    localStorage.setItem('access_token', 'fixture-token')
    localStorage.setItem('unrelated-preference', 'preserve')
  }, { user, malformed })
}
async function respond(page: Page) {
  const requests: { path: string; body: unknown }[] = []
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    requests.push({ path, body: route.request().postDataJSON() })
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, code: 'OK', data: path === '/api/account/v1' ? user : {} }) })
  })
  return requests
}

test('损坏的账户缓存恢复到登录页', async ({ page }) => {
  await session(page, true)
  await page.goto('/account')
  await expect(page.getByRole('heading', { name: '登录 LexiForge' })).toBeVisible()
  expect(await page.evaluate(() => localStorage.getItem('access_token'))).toBeNull()
  expect(await page.evaluate(() => localStorage.getItem('unrelated-preference'))).toBe('preserve')
})

test('密码重置保留一次性凭据在内存并清除原会话', async ({ page }) => {
  await session(page)
  const requests = await respond(page)
  await page.goto('/account-action#type=reset&token=fixture-one-time-token')
  await expect(page.getByRole('heading', { name: '设置新密码' })).toBeVisible()
  await expect(page).toHaveURL(/\/account-action$/)
  await page.getByLabel('新密码', { exact: true }).fill('Updated123!')
  await page.getByLabel('确认新密码').fill('Updated123!')
  await page.getByRole('button', { name: '更新密码', exact: true }).click()
  await expect(page.getByText('密码已更新，原有会话已失效', { exact: true })).toBeVisible()
  expect(requests.find(r => r.path.endsWith('/password-reset/confirm/v1'))?.body).toEqual({ token: 'fixture-one-time-token', password: 'Updated123!' })
  expect(await page.evaluate(() => localStorage.getItem('access_token'))).toBeNull()
  expect(await page.evaluate(() => localStorage.getItem('unrelated-preference'))).toBe('preserve')
  await page.getByRole('link', { name: '返回登录' }).click()
  await expect(page.getByRole('heading', { name: '登录 LexiForge' })).toBeVisible()
})

test('邮箱验证必须明确确认，刷新页面不会重放凭据', async ({ page }) => {
  const requests = await respond(page)
  await page.goto('/account-action#type=verify&token=fixture-verification-token')
  await expect(page.getByRole('button', { name: '确认邮箱' })).toBeEnabled()
  expect(requests).toHaveLength(0)
  await page.getByRole('button', { name: '确认邮箱' }).click()
  await expect(page.getByText('邮箱已验证', { exact: true })).toBeVisible()
  expect(requests[0]?.body).toEqual({ token: 'fixture-verification-token' })
  await page.reload()
  await expect(page.getByText('缺少验证链接，请重新申请', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '更新密码', exact: true })).toBeDisabled()
  expect(requests).toHaveLength(1)
})

test('账户页读取服务端状态并申请验证', async ({ page }) => {
  await session(page)
  const requests = await respond(page)
  await page.goto('/account')
  await expect(page.getByText('尚未验证', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '申请验证邮件' }).click()
  await expect(page.getByText('如果邮箱尚未验证，验证邮件已进入发送队列。', { exact: true })).toBeVisible()
  expect(requests.find(r => r.path.endsWith('/email-verification/request/v1'))?.body).toEqual({ email: user.email })
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false)
})
