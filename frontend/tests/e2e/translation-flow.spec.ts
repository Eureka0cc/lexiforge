import { test, expect, type Page, type Route } from '@playwright/test'
import { startTranslationUiServer } from '../../../scripts/fixtures/translation-ui-server.mjs'

async function fixture(page: Page, history = false) {
  await page.addInitScript(restore => {
    localStorage.setItem('user', JSON.stringify({ id: '7', email: 'ui@example.test', displayName: '验收用户', role: 'USER' }))
    localStorage.setItem('access_token', 'fixture-token')
    if (restore) sessionStorage.setItem('lexiforge:last-translation', JSON.stringify({ ownerId: '7', translation: { id: '11', sourceText: 'Original source', translatedText: '上次完整译文', sourceLanguage: 'EN', targetLanguage: 'ZH', domainId: '2001', alternatives: [] } }))
  }, history)
  await page.route('**/api/domains/v1', route => route.fulfill({ json: { success: true, data: [{ id: '2001', name: 'IT', code: 'IT', builtIn: true }] } }))
  await page.route('**/api/translations/v1*', route => route.fulfill({ json: { success: true, data: { items: [] } } }))
  await page.goto('/translate')
  await expect(page.getByRole('button', { name: /立即翻译/ })).toBeVisible()
  const editor = page.getByRole('textbox', { name: '待翻译原文' })
  if (!await editor.isVisible()) await page.getByRole('button', { name: '原文', exact: true }).click()
  return editor
}
async function success(route: Route, text: string) {
  const body = route.request().postDataJSON()
  await route.fulfill({ contentType: 'text/event-stream', body:
    `event: delta\ndata: ${JSON.stringify({ text })}\n\nevent: completed\ndata: ${JSON.stringify({ translation: { id: body.requestId, ...body, translatedText: text, alternatives: [] } })}\n\n` })
}

test('输入法组词期间不发送，结束后防抖 900ms，手动提交取消待执行定时器', async ({ page }) => {
  const requests: Route[] = []
  await page.route('**/api/translations/stream/v1', route => { requests.push(route) })
  const editor = await fixture(page)
  await page.clock.install({ time: new Date('2026-10-15T00:00:00Z') })
  await page.clock.pauseAt(new Date('2026-10-15T00:00:01Z'))
  await editor.fill('Draft')
  await editor.dispatchEvent('compositionstart')
  await editor.evaluate(element => {
    const input = element as HTMLTextAreaElement
    input.value = '输入法测试'
    input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertCompositionText', isComposing: true }))
  })
  const prevented = await editor.evaluate(element => {
    const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: 'Enter', code: 'Enter', ctrlKey: true, isComposing: true })
    element.dispatchEvent(event)
    return event.defaultPrevented
  })
  expect(prevented).toBe(false)
  await page.clock.fastForward(2000)
  expect(requests).toHaveLength(0)
  await editor.dispatchEvent('compositionend')
  await page.clock.fastForward(899)
  expect(requests).toHaveLength(0)
  await page.clock.fastForward(1)
  await expect.poll(() => requests.length).toBe(1)
  expect(requests[0]!.request().postDataJSON().sourceText).toBe('输入法测试')
  await success(requests[0]!, '输入法译文')
  await expect(page.locator('.translated-document')).toContainText('输入法译文')
  if (!await editor.isVisible()) await page.getByRole('button', { name: '原文', exact: true }).click()
  await editor.fill('Manual request')
  await editor.press('Control+Enter')
  await expect.poll(() => requests.length).toBe(2)
  await page.clock.fastForward(1500)
  expect(requests).toHaveLength(2)
  expect(requests[1]!.request().postDataJSON().forceRefresh).toBe(false)
  await success(requests[1]!, '手动译文')
})

test('关闭中断提示仍不能把部分译文当成完整结果查词或复制，重试后恢复', async ({ page }) => {
  let attempts = 0
  await page.route('**/api/translations/stream/v1', async route => {
    attempts++
    if (attempts === 1) await route.fulfill({ contentType: 'text/event-stream', body: 'event: delta\ndata: {"text":"只收到半句"}\n\nevent: error\ndata: {"code":"AI_TIMEOUT","message":"AI 响应超时"}\n\n' })
    else await success(route, '新的完整译文')
  })
  const editor = await fixture(page, true)
  await editor.fill('New source')
  await page.getByRole('button', { name: /立即翻译/ }).click()
  await expect(page.locator('.workspace-alert')).toContainText('AI 响应超时')
  await expect(page.locator('.translated-document')).toContainText('只收到半句')
  await page.locator('.workspace-alert .ant-alert-close-icon').click()
  if (!await page.getByRole('button', { name: '复制译文' }).isVisible()) await page.getByRole('button', { name: '译文', exact: true }).click()
  await expect(page.getByRole('button', { name: '复制译文' })).toBeDisabled()
  await expect(page.getByRole('button', { name: /重新翻译/ })).toBeDisabled()
  await page.getByRole('button', { name: /立即翻译/ }).click()
  await expect(page.locator('.translated-document')).toContainText('新的完整译文')
  await expect(page.getByRole('button', { name: '复制译文' })).toBeEnabled()
})

test('编辑保留并标记旧译文，过期请求不能覆盖新结果', async ({ page }) => {
  const requests: Route[] = []
  await page.route('**/api/translations/stream/v1', route => { requests.push(route) })
  const editor = await fixture(page, true)
  await editor.fill('First request')
  await expect(page.locator('.translated-document')).toContainText('上次完整译文')
  await expect(page.locator('.translated-document')).toHaveClass(/stale/)
  await page.getByRole('button', { name: /立即翻译/ }).click()
  await expect.poll(() => requests.length).toBe(1)
  await editor.fill('Latest request')
  await page.getByRole('button', { name: /立即翻译/ }).click()
  await expect.poll(() => requests.length).toBe(2)
  await success(requests[1]!, '最新结果')
  await expect(page.locator('.translated-document')).toContainText('最新结果')
  await success(requests[0]!, '过期结果').catch(() => {})
  await expect(page.locator('.translated-document')).not.toContainText('过期结果')
  await expect(page.locator('.translated-document')).not.toHaveClass(/stale/)
})

for (const partial of ['', '已收到的半句']) {
  test(`停止${partial ? '生成中' : '等待中'}的请求会关闭真实 HTTP 连接，保持未完成并允许重试`, async ({ page }) => {
    const server = await startTranslationUiServer({ partial })
    try {
      await page.route('**/api/translations/stream/v1', route => route.continue({ url: server.url + '/api/translations/stream/v1' }))
      const editor = await fixture(page)
      await editor.fill('Stop this request')
      await page.getByRole('button', { name: /立即翻译/ }).click()
      await expect.poll(() => server.state.requests.length).toBe(1)
      if (partial) await expect(page.locator('.translated-document')).toContainText(partial)
      await page.getByRole('button', { name: /^停\s*止$/ }).click()
      await expect.poll(() => server.state.connectionsClosed).toBe(1)
      await expect(page.locator('.result-document .document-pane-footer')).toContainText('已停止')
      if (!await page.getByRole('button', { name: '复制译文' }).isVisible()) await page.getByRole('button', { name: '译文', exact: true }).click()
      await expect(page.getByRole('button', { name: '复制译文' })).toBeDisabled()
      await page.getByRole('button', { name: /立即翻译/ }).click()
      await expect.poll(() => server.state.requests.length).toBe(2)
      server.complete(1, '重试完成')
      await expect(page.locator('.translated-document')).toContainText('重试完成')
      await expect(page.getByRole('button', { name: '复制译文' })).toBeEnabled()
    } finally { await server.close() }
  })
}

test('真实 HTTP 流断线保留部分内容，禁用复制，手动重试恢复', async ({ page }) => {
  const server = await startTranslationUiServer({ partial: '断线前收到的内容' })
  try {
    await page.route('**/api/translations/stream/v1', route => route.continue({ url: server.url + '/api/translations/stream/v1' }))
    const editor = await fixture(page)
    await editor.fill('Connection failure')
    await page.getByRole('button', { name: /立即翻译/ }).click()
    await expect(page.locator('.translated-document')).toContainText('断线前收到的内容')
    server.disconnect(0)
    await expect(page.locator('.workspace-alert')).toContainText('翻译连接已中断')
    if (!await page.getByRole('button', { name: '复制译文' }).isVisible()) await page.getByRole('button', { name: '译文', exact: true }).click()
    await expect(page.getByRole('button', { name: '复制译文' })).toBeDisabled()
    await page.getByRole('button', { name: /立即翻译/ }).click()
    await expect.poll(() => server.state.requests.length).toBe(2)
    server.complete(1, '重试已完成')
    await expect(page.locator('.translated-document')).toContainText('重试已完成')
    await expect(page.getByRole('button', { name: '复制译文' })).toBeEnabled()
  } finally { await server.close() }
})

for (const failure of ['quota', 'offline', 'eof'] as const) {
  test(`${failure} 不清空原文或伪造完成，不后台重试，手动重试可恢复`, async ({ page }) => {
    let attempts = 0
    await page.route('**/api/translations/stream/v1', async route => {
      attempts++
      if (attempts > 1) return success(route, '恢复后的完整译文')
      if (failure === 'quota') await route.fulfill({ status: 429, json: { code: 'DAILY_QUOTA_EXCEEDED', message: '今日额度已用完' } })
      else if (failure === 'offline') await route.abort('failed')
      else await route.fulfill({ contentType: 'text/event-stream', body: 'event: delta\ndata: {"text":"未完成的内容"}\n\n' })
    })
    const editor = await fixture(page, true)
    await editor.fill('Preserve my input')
    await page.getByRole('button', { name: /立即翻译/ }).click()
    await expect(page.locator('.workspace-alert')).toContainText(failure === 'quota' ? '今日额度已用完' : failure === 'offline' ? '无法连接翻译服务' : '翻译未完成')
    await expect(editor).toHaveValue('Preserve my input')
    expect(attempts).toBe(1)
    await page.getByRole('button', { name: /立即翻译/ }).click()
    await expect(page.locator('.translated-document')).toContainText('恢复后的完整译文')
    expect(attempts).toBe(2)
  })
}
