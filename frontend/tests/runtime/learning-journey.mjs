// Opt-in real local acceptance: no route interception, seeded business rows or model substitutions.
import { chromium, expect } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { realpathSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const [baseURL, output] = process.argv.slice(2)
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseURL)) throw new Error('local origin required')
const directory = realpathSync(output)
const tmp = realpathSync(path.resolve(import.meta.dirname, '../../../../tmp'))
if (!directory.startsWith(tmp + path.sep)) throw new Error('reports must stay in workspace tmp')
const browser = await chromium.launch()
const cohorts = []
try {
  for (const [device, viewport] of [['desktop', { width: 1366, height: 900 }], ['mobile', { width: 390, height: 844 }]]) {
    const context = await browser.newContext({ viewport })
    const page = await context.newPage()
    page.setDefaultTimeout(15000)
    const report = { device, passed: false, mockedRoutes: false, accountPlan: 'normal Free', operations: [], errors: [] }
    cohorts.push(report)
    page.on('pageerror', error => report.errors.push(error.message))
    page.on('response', response => {
      const url = new URL(response.url())
      if (url.origin === baseURL && url.pathname.startsWith('/api/')) report.operations.push({ method: response.request().method(), path: url.pathname, status: response.status() })
    })
    const apiResponse = (suffix, method = 'POST') => page.waitForResponse(r => new URL(r.url()).pathname.endsWith(suffix) && r.request().method() === method, { timeout: 100000 })
    const successful = async pending => {
      const response = await pending
      if (!response.ok()) throw new Error(`HTTP ${response.status()} ${new URL(response.url()).pathname}`)
      const envelope = await response.json()
      if (!envelope.success) throw new Error('business response failed')
      return envelope.data
    }
    try {
      await page.goto(baseURL + '/login')
      await page.getByRole('button', { name: '创建账户', exact: true }).first().click()
      await page.getByLabel('昵称', { exact: true }).fill('本地闭环验收')
      await page.getByLabel('邮箱', { exact: true }).fill(`journey-${randomUUID()}@example.test`)
      await page.getByLabel('密码', { exact: true }).fill(`Local-${randomUUID()}!`)
      await page.locator('button[type=submit]').click()
      await page.waitForURL('**/translate')
      await page.goto(baseURL + '/domains')
      await page.getByRole('button', { name: '定制翻译场景', exact: true }).click()
      const modal = page.getByRole('dialog')
      const sceneName = `本地并发文档-${device}`
      await modal.getByLabel('场景名称', { exact: true }).fill(sceneName)
      await modal.getByLabel('告诉 AI 你的翻译需求', { exact: true }).fill('面向后端工程师翻译并发控制说明，中文简洁、准确，保留数字和代码，不添加原文没有的建议。')
      const draftResponse = apiResponse('/api/scenes/generate/v1')
      await modal.getByRole('button', { name: 'AI 生成场景草稿', exact: true }).click()
      const draft = await successful(draftResponse)
      await expect(modal.getByLabel('专业领域', { exact: true })).toHaveValue(draft.configuration.domain, { timeout: 10000 })
      // Exercise editing and persistence instead of silently accepting a generated draft.
      const style = '简洁的中文工程说明，保持原文事实。'
      await modal.getByLabel('表达风格', { exact: true }).fill(style)
      const saveResponse = apiResponse('/api/domains/v1')
      await modal.getByRole('button', { name: '保存并使用', exact: true }).click()
      const scene = await successful(saveResponse)
      await page.waitForURL('**/translate?scene=*')
      await page.reload()
      await expect(page.locator('.domain-control .ant-select')).toContainText(sceneName)
      report.scene = { id: scene.id, name: sceneName, editedStyle: style }
      const source = 'The concurrency limit is 20.'
      await page.getByLabel('待翻译原文').fill(source)
      await expect(page.getByRole('button', { name: '复制译文', exact: true })).toBeEnabled({ timeout: 100000 })
      report.translation = await page.locator('.translated-document > p').innerText()
      if (!report.translation.includes('20')) throw new Error('numeric fact lost')
      if (device === 'mobile') await page.getByRole('button', { name: '原文', exact: true }).click()
      const lookupResponse = apiResponse('/lookups/v1')
      await page.locator('.source-editor').evaluate(element => {
        element.setSelectionRange(4, 15)
        element.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: 100, clientY: 250 }))
      })
      const lookup = await successful(lookupResponse)
      const dialog = page.getByRole('dialog', { name: '语境解释' })
      await expect(dialog.locator('.meaning')).toHaveText(lookup.meaning)
      if (!lookup.collectable) throw new Error('English selection unexpectedly not collectable')
      const detailsResponse = apiResponse('/details/v1')
      await dialog.getByRole('button', { name: '展开详情', exact: true }).click()
      const details = await successful(detailsResponse)
      if (!details.exampleSentence?.trim()) throw new Error('missing contextual example')
      await expect(dialog.locator('.details')).toContainText(details.exampleSentence)
      const collectResponse = apiResponse(`/api/vocabulary/lookup/${lookup.lookupId}/v1`)
      await dialog.getByRole('button', { name: /^收\s*藏$/ }).click()
      await successful(collectResponse)
      await page.goto(baseURL + '/vocabulary')
      await expect(page.getByText('concurrency', { exact: true }).first()).toBeVisible()
      await page.screenshot({ path: path.join(directory, `${device}-vocabulary.png`), fullPage: true })
      await page.goto(baseURL + '/review')
      const sessionResponse = apiResponse('/api/review-sessions/v1')
      await page.getByRole('button', { name: '开始复习', exact: true }).click()
      const session = await successful(sessionResponse)
      if (session.items.length !== 1 || session.items[0].prompt.toLowerCase() !== 'concurrency') throw new Error('collected item not in real review session')
      await page.getByLabel('你的答案', { exact: true }).fill('并发')
      const answerResponse = apiResponse('/answers/v1')
      await page.getByRole('button', { name: '提交答案', exact: true }).click()
      const answer = await successful(answerResponse)
      if (!['CORRECT', 'PARTIAL', 'WRONG'].includes(answer.result)) throw new Error('missing real grading result')
      await expect(page.getByRole('heading', { name: /回答正确|部分正确|回答错误/ })).toBeVisible()
      report.review = { sessionId: session.id, result: answer.result, feedback: answer.feedback, expectedAnswer: answer.expectedAnswer }
      await page.screenshot({ path: path.join(directory, `${device}-review.png`), fullPage: true })
      const finishResponse = apiResponse('/finish/v1')
      await page.getByRole('button', { name: '完成复习', exact: true }).click()
      await successful(finishResponse)
      await expect(page.getByRole('heading', { name: '开始一轮主动回忆', exact: true })).toBeVisible()
      await page.reload()
      await page.getByRole('button', { name: '开始复习', exact: true }).click()
      await expect(page.getByText('目前没有到期单词，可以先阅读文档或整理词库', { exact: true })).toBeVisible()
      if (report.errors.length || report.operations.some(item => item.status >= 400)) throw new Error('runtime or API errors recorded')
      report.passed = true
      console.log(`PASS ${device}: real scene → translation → lookup → details → collection → review → persisted schedule`)
    } catch (error) {
      report.failure = error.message
      await page.screenshot({ path: path.join(directory, `${device}-failure.png`), fullPage: true }).catch(() => {})
      writeFileSync(path.join(directory, `${device}-failure.txt`), await page.locator('body').innerText().catch(() => ''))
      throw error
    } finally {
      writeFileSync(path.join(directory, 'browser-result.json'), JSON.stringify({ passed: cohorts.length === 2 && cohorts.every(item => item.passed), languageQualityAcceptance: false, cohorts }, null, 2))
      await context.close()
    }
  }
} finally { await browser.close() }
