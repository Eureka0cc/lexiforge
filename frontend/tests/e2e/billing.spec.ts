import { test, expect, type Page } from '@playwright/test'

async function fixture(page: Page, lostNotification = false) {
  const orders: Record<string, unknown>[] = []
  const paid = new Set<string>()
  let created: Record<string, unknown>[] = []
  await page.addInitScript(() => {
    localStorage.setItem('user', JSON.stringify({ id: '7', email: 'ui@example.test', displayName: '验收用户', role: 'USER' }))
    localStorage.setItem('access_token', 'fixture-token')
  })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let data: unknown = {}
    if (path === '/api/plans/v1') data = [
      { id: '1', code: 'FREE', name: 'Free', monthlyPrice: 0, annualPrice: 0, translationLimit: 20 },
      { id: '2', code: 'PRO', name: 'Pro', monthlyPrice: 29, annualPrice: 299, translationLimit: 200 },
    ]
    else if (path === '/api/subscriptions/v1') data = { planCode: paid.size ? 'PRO' : 'FREE', status: paid.size ? 'ACTIVE' : 'FREE', expiresAt: paid.size ? '2099-01-01T00:00:00Z' : undefined }
    else if (path === '/api/billing/config/v1') data = { enabled: true, mode: 'LOCAL_SANDBOX', officialChannelConnected: false }
    else if (path === '/api/billing/orders/v1' && method === 'POST') {
      const body = route.request().postDataJSON(); created.push(body)
      data = { id: String(100 + orders.length), planName: 'Pro', cycle: body.cycle, channel: body.channel, amountMinor: body.cycle === 'ANNUAL' ? 29900 : 2900, currency: 'CNY', status: 'PENDING', createdAt: new Date().toISOString(), expiresAt: '2099-01-01T00:00:00Z' }
      orders.unshift(data as Record<string, unknown>)
    } else if (path === '/api/billing/orders/v1') data = orders
    else if (path.startsWith('/api/billing/orders/')) {
      const id = path.split('/')[4]!
      const order = orders.find(order => order.id === id)!
      if (path.includes('/sandbox-pay/')) {
        if (lostNotification) {
          await route.fulfill({ status: 503, json: { success: false, message: '通知暂时中断，请查询状态' } }); return
        }
        order.status = 'PAID'; paid.add(id)
      } else if (path.includes('/sandbox-refund/')) { order.status = 'REFUNDED'; paid.delete(id) }
      else if (path.includes('/cancel/')) order.status = 'CANCELLED'
      else if (lostNotification) { order.status = 'PAID'; paid.add(id) }
      data = order
    }
    await route.fulfill({ json: { success: true, data } })
  })
  return { created: () => created }
}
async function createOrder(page: Page, annual = false) {
  await page.getByRole('button', { name: /Sandbox (订阅|续费) Pro/ }).click()
  const modal = page.getByRole('dialog')
  if (annual) { await modal.getByLabel('年付（365 天）').check(); await modal.getByLabel('微信本地模拟').check() }
  else { await modal.getByLabel('月付（30 天）').check(); await modal.getByLabel('支付宝本地模拟').check() }
  await modal.getByRole('button', { name: '创建订单', exact: true }).click()
  await expect(modal).toBeHidden()
}

test('创建年付、模拟支付、续费、退款及取消订单', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/plans')
  await expect(page.getByText('本地 Sandbox：不会发生真实扣款', { exact: true })).toBeVisible()
  await createOrder(page, true)
  const first = page.getByRole('article', { name: '订单 100', exact: true })
  await expect(first).toContainText('微信本地模拟 · ¥299.00')
  expect(state.created()[0]).toMatchObject({ cycle: 'ANNUAL', channel: 'WECHAT_SIMULATOR' })
  await first.getByRole('button', { name: '模拟支付', exact: true }).click()
  await expect(first).toContainText('已支付')
  await createOrder(page)
  const second = page.getByRole('article', { name: '订单 101', exact: true })
  await second.getByRole('button', { name: '模拟支付', exact: true }).click()
  await expect(second).toContainText('已支付')
  await first.getByRole('button', { name: '模拟退款', exact: true }).click()
  await page.getByRole('button', { name: '确认退款', exact: true }).click()
  await expect(first).toContainText('已退款')
  await expect(page.getByText('当前：Pro', { exact: true })).toBeVisible()
  await createOrder(page)
  const third = page.getByRole('article', { name: '订单 102', exact: true })
  await third.getByRole('button', { name: '取消订单' }).click()
  await expect(third).toContainText('已取消')
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false)
})

test('支付通知异常时保留待支付状态，查询服务端确认后才显示 Pro', async ({ page }) => {
  await fixture(page, true)
  await page.goto('/plans'); await createOrder(page)
  const order = page.getByRole('article', { name: '订单 100', exact: true })
  await order.getByRole('button', { name: '模拟支付', exact: true }).click()
  await expect(order.getByRole('button', { name: '查询状态' })).toBeEnabled()
  await expect(order).toContainText('待支付')
  await expect(page.getByText('当前：免费版', { exact: true })).toBeVisible()
  await order.getByRole('button', { name: '查询状态' }).click()
  await expect(order).toContainText('已支付')
  await expect(page.getByText('当前：Pro', { exact: true })).toBeVisible()
})
