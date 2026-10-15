import { test, expect, type Route } from '@playwright/test'

test('重复点击只提交一次，完成状态失败后保留答案允许重试', async ({ page }) => {
  let grading: Route | undefined
  let answerCalls = 0
  let finishCalls = 0
  await page.addInitScript(() => {
    localStorage.setItem('user', JSON.stringify({ id: '7', email: 'ui@example.test', displayName: '验收用户', role: 'USER' }))
    localStorage.setItem('access_token', 'fixture-token')
  })
  await page.route('**/api/review-sessions/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/answers/v1')) { answerCalls++; grading = route; return }
    if (path.endsWith('/finish/v1')) {
      finishCalls++
      await route.fulfill(finishCalls === 1
        ? { status: 503, json: { success: false, message: '暂时无法保存' } }
        : { json: { success: true, data: null } })
      return
    }
    await route.fulfill({ json: { success: true, data: { id: '88', items: [{ vocabularyId: '22', direction: 'EN_TO_ZH', prompt: 'address', partOfSpeech: 'v.' }] } } })
  })
  await page.goto('/review')
  await page.getByRole('button', { name: '开始复习', exact: true }).click()
  await page.getByLabel('你的答案').fill('解决')
  const submit = page.getByRole('button', { name: '提交答案', exact: true })
  // DOM 双击模拟同一事件循环中重复触发，避免加载动画屏蔽第二次输入掩盖处理逻辑。
  await submit.evaluate(element => { (element as HTMLButtonElement).click(); (element as HTMLButtonElement).click() })
  await expect.poll(() => answerCalls).toBe(1)
  await grading!.fulfill({ json: { success: true, data: { result: 'CORRECT', feedback: '正确', expectedAnswer: '解决' } } })
  await expect(page.getByRole('heading', { name: '回答正确' })).toBeVisible()
  await page.getByRole('button', { name: '完成复习', exact: true }).click()
  await expect(page.getByText('完成状态保存失败，当前结果已保留，请重试', { exact: true })).toBeVisible()
  await expect(page.getByLabel('你的答案')).toHaveValue('解决')
  await expect(page.getByRole('heading', { name: '回答正确' })).toBeVisible()
  await page.getByRole('button', { name: '完成复习', exact: true }).click()
  await expect(page.getByRole('heading', { name: '开始一轮主动回忆' })).toBeVisible()
  expect(finishCalls).toBe(2)
  expect(answerCalls).toBe(1)
})
