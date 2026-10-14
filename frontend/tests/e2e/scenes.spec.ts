import { test, expect, type Page } from '@playwright/test'

const configuration = { domain: '嵌入式', documentType: '数据手册', audience: '工程师', style: '简洁严谨', preserveRules: ['保留寄存器名称'], explanationPreference: '不额外解释' }
const builtIn = { id: '2001', name: 'IT / 软件开发', code: 'IT', description: '软件开发资料', builtIn: true, medicalDisclaimer: false, terms: [], revision: 0, configuration: null }
const custom = { id: '4001', name: '芯片文档', description: '面向工程师翻译芯片手册', builtIn: false, medicalDisclaimer: false, terms: [], revision: 2, configuration }

async function fixture(page: Page) {
  let domains = [builtIn, custom]
  let saved: Record<string, unknown> | null = null
  let previewCalls = 0
  await page.addInitScript(() => {
    localStorage.setItem('user', JSON.stringify({ id: '7', email: 'ui@example.test', displayName: '验收用户', role: 'USER' }))
    localStorage.setItem('access_token', 'fixture-token')
  })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let data: unknown = {}
    if (path === '/api/domains/v1' && method === 'GET') data = domains
    else if (path === '/api/scenes/generate/v1') data = { id: '99', name: '芯片场景', description: route.request().postDataJSON().description, configuration, generationVersion: 'fixture-v1' }
    else if (path === '/api/scenes/preview/v1') {
      previewCalls++
      data = { id: '100', translatedText: '保持 CTRL_REG 不变，并解决问题。' }
    } else if ((path === '/api/domains/v1' && method === 'POST') || method === 'PATCH') {
      saved = route.request().postDataJSON()
      const updated = { ...custom, ...saved, revision: 3 }
      domains = [builtIn, updated]
      data = updated
    } else if (path === '/api/translations/v1') data = { items: [] }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, code: 'OK', message: '', data }) })
  })
  return { saved: () => saved, previews: () => previewCalls }
}

test('生成场景、修改要求、试译并保存使用', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/domains')
  await page.getByRole('button', { name: '定制翻译场景', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('告诉 AI 你的翻译需求').fill('我翻译芯片数据手册，读者是工程师，请保留寄存器名称。')
  await dialog.getByRole('button', { name: 'AI 生成场景草稿' }).click()
  await expect(dialog.getByLabel('专业领域')).toHaveValue('嵌入式')
  await dialog.getByLabel('表达风格').fill('简洁严谨，避免生硬直译')
  await dialog.getByLabel('试译原文').fill('Preserve CTRL_REG and address the issue.')
  await dialog.getByRole('button', { name: '试译当前草稿' }).click()
  await expect(dialog.locator('.scene-preview-result')).toContainText('保持 CTRL_REG')
  expect(state.previews()).toBe(1)
  await expect(dialog.getByRole('button', { name: '保存并使用' })).toBeInViewport()
  await expect(dialog.getByText('定制 AI 翻译场景', { exact: true })).toBeInViewport()
  await expect(page.locator('.ant-message-notice')).toHaveCount(0)
  await page.screenshot({ path: test.info().outputPath('scene-editor.png'), fullPage: true })
  await dialog.getByRole('button', { name: '保存并使用' }).click()
  await expect(page).toHaveURL(/\/translate\?scene=4001/)
  expect(state.saved()).toMatchObject({ configuration: { style: '简洁严谨，避免生硬直译' } })
})

test('编辑场景携带当前版本，修改需求后不能误用旧配置', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/domains')
  await page.getByRole('button', { name: /芯片文档.*按描述自动适配/ }).click()
  await page.getByRole('button', { name: '编辑与试译' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('告诉 AI 你的翻译需求').fill('现在面向初学者，表达容易理解')
  await dialog.getByRole('button', { name: '保存并使用' }).click()
  expect(state.saved()).toBeNull()
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '重新整理场景' }).click()
  await dialog.getByRole('button', { name: '保存并使用' }).click()
  await expect(page).toHaveURL(/\/translate\?scene=4001/)
  expect(state.saved()).toMatchObject({ expectedRevision: 2 })
})

test('移动与桌面场景页没有水平溢出', async ({ page }) => {
  await fixture(page)
  await page.goto('/domains')
  await expect(page.getByRole('heading', { name: 'AI 翻译场景', exact: true })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
  expect(overflow).toBe(false)
})
