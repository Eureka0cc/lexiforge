import { test, expect, type Page, type Route } from '@playwright/test'

async function fixture(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('user', JSON.stringify({ id: '7', email: 'ui@example.test', displayName: '验收用户', role: 'USER' }))
    localStorage.setItem('access_token', 'fixture-token')
    sessionStorage.setItem('lexiforge:last-translation', JSON.stringify({ id: '11', sourceText: 'address issue', translatedText: '解决问题', sourceLanguage: 'EN', targetLanguage: 'ZH', domainId: '2001', alternatives: [] }))
  })
  await page.route('**/api/domains/v1', r => r.fulfill({ json: { success: true, data: [{ id: '2001', name: 'IT', code: 'IT', builtIn: true }] } }))
  await page.route('**/api/translations/v1*', r => r.fulfill({ json: { success: true, data: { items: [] } } }))
  await page.goto('/translate')
  await expect(page.locator('.translated-document')).toContainText('解决问题')
}
async function select(page: Page, start: number, end: number) {
  await page.locator('.source-editor').evaluate((element, range) => {
    const editor = element as HTMLTextAreaElement
    editor.setSelectionRange(range[0]!, range[1]!)
    editor.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: 24, clientY: 130 }))
  }, [start, end])
}
const explanation = (id: string, selectedText: string) => ({ lookupId: id, selectedText, meaning: selectedText + '语境解释', explanation: '当前句子含义', counterpartText: '对应表达', collectable: true })

test('切换选区后迟到解释与详情不会覆盖新选区', async ({ page }) => {
  let first: Route | undefined
  let detail: Route | undefined
  await page.route('**/api/translations/11/lookups/v1', async route => {
    if (route.request().postDataJSON().start === 0 && !first) first = route
    else await route.fulfill({ json: { success: true, data: explanation('22', 'issue') } })
  })
  await page.route('**/api/lookups/22/details/v1', route => { detail = route })
  await fixture(page)
  await select(page, 0, 7)
  await expect.poll(() => !!first).toBe(true)
  await select(page, 8, 13)
  const dialog = page.getByRole('dialog', { name: '语境解释' })
  await expect(dialog).toContainText('issue语境解释')
  await first!.fulfill({ json: { success: true, data: explanation('21', 'address') } }).catch(() => {})
  await expect(dialog).not.toContainText('address语境解释')
  await dialog.getByRole('button', { name: '展开详情' }).click()
  await expect.poll(() => !!detail).toBe(true)
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await detail!.fulfill({ json: { success: true, data: { exampleSentence: '迟到的详情' } } }).catch(() => {})
  await expect(dialog).toHaveCount(0)
  await select(page, 8, 13)
  await expect(dialog).toBeVisible()
  await expect(dialog).not.toContainText('迟到的详情')
  await page.getByRole('heading', { name: 'AI 翻译', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.locator('.ant-message-notice')).toHaveCount(0)
})
