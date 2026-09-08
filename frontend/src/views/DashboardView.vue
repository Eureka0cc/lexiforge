<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRightOutlined,
  BookOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  FileTextOutlined,
  ReloadOutlined,
  TranslationOutlined,
} from '@ant-design/icons-vue'
import { api } from '../api'
import type { Translation } from '../types'

interface DashboardStats {
  vocabularyCount: number
  dueCount: number
  reviewedToday: number
  accuracy7d: number
  streakDays: number
}

interface UsagePlan {
  name: string
  translationLimit: number
  gradingLimit: number
}

interface UsageSummary {
  plan: UsagePlan
  translationsUsed: number
  gradingsUsed: number
}

const router = useRouter()
const stats = ref<DashboardStats | null>(null)
const usage = ref<UsageSummary | null>(null)
const latestTranslation = ref<Translation | null>(null)
const loading = ref(true)
const statsError = ref(false)
const usageError = ref(false)
const readingError = ref(false)

const today = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'short',
}).format(new Date())

const translationPercent = computed(() => percentage(usage.value?.translationsUsed, usage.value?.plan.translationLimit))
const gradingPercent = computed(() => percentage(usage.value?.gradingsUsed, usage.value?.plan.gradingLimit))

function percentage(used = 0, limit = 0) {
  if (limit <= 0) return 0
  return Math.min(100, Math.round((used / limit) * 100))
}

function sourceExcerpt(text: string) {
  const normalized = text.replace(/\s+/g, ' ').trim()
  return normalized.length > 110 ? `${normalized.slice(0, 110)}…` : normalized
}

async function loadDashboard() {
  loading.value = true
  statsError.value = false
  usageError.value = false
  readingError.value = false

  const [statsResult, usageResult, readingResult] = await Promise.allSettled([
    api.get<DashboardStats>('/api/dashboard/v1'),
    api.get<UsageSummary>('/api/usage/v1'),
    api.get<{ items: Translation[] }>('/api/translations/v1?size=1'),
  ])

  if (statsResult.status === 'fulfilled') stats.value = statsResult.value.data
  else statsError.value = true

  if (usageResult.status === 'fulfilled') usage.value = usageResult.value.data
  else usageError.value = true

  if (readingResult.status === 'fulfilled') latestTranslation.value = readingResult.value.data.items[0] ?? null
  else readingError.value = true

  loading.value = false
}

function continueTranslation() {
  if (latestTranslation.value) {
    sessionStorage.setItem('lexiforge:last-translation', JSON.stringify(latestTranslation.value))
  }
  router.push('/translate')
}

onMounted(loadDashboard)
</script>

<template>
  <div class="page-header">
    <div>
      <h1>学习概览</h1>
      <p>查看词汇积累和复习进度，继续今天的单词学习。</p>
    </div>
    <time class="today-label">{{ today }}</time>
  </div>

  <a-skeleton v-if="loading" active :paragraph="{ rows: 9 }" />

  <template v-else>
    <section class="primary-tasks" aria-label="今日主要任务">
      <article class="task-panel continue-panel">
        <header class="task-panel-header">
          <div>
            <span class="task-eyebrow"><FileTextOutlined /> 继续阅读</span>
            <h2>最近的技术文档</h2>
          </div>
          <span v-if="latestTranslation" class="language-code">
            {{ latestTranslation.detectedLanguage }} → {{ latestTranslation.targetLanguage }}
          </span>
        </header>

        <a-result v-if="readingError" status="error" title="暂时无法读取翻译记录" sub-title="其他学习数据不受影响">
          <template #extra><a-button @click="loadDashboard"><ReloadOutlined />重试</a-button></template>
        </a-result>

        <template v-else-if="latestTranslation">
          <p class="document-excerpt mono">{{ sourceExcerpt(latestTranslation.sourceText) }}</p>
          <div class="document-meta">
            <span><ClockCircleOutlined /> {{ new Date(latestTranslation.createdAt).toLocaleDateString('zh-CN') }}</span>
            <span>{{ latestTranslation.terms.length }} 个重点术语</span>
          </div>
          <div class="task-actions">
            <a-button type="primary" @click="continueTranslation">继续翻译</a-button>
            <a-button @click="router.push('/translate')">新建翻译</a-button>
          </div>
        </template>

        <div v-else class="task-empty">
          <TranslationOutlined />
          <div>
            <strong>还没有阅读记录</strong>
            <p>粘贴一段 API 文档、README 或错误说明，开始第一篇翻译。</p>
          </div>
          <a-button type="primary" @click="router.push('/translate')">开始翻译</a-button>
        </div>
      </article>

      <article class="task-panel review-panel">
        <header class="task-panel-header">
          <div>
            <span class="task-eyebrow"><BookOutlined /> 主动复习</span>
            <h2>今日待复习</h2>
          </div>
        </header>

        <a-result v-if="statsError" status="error" title="学习数据加载失败">
          <template #extra><a-button @click="loadDashboard"><ReloadOutlined />重试</a-button></template>
        </a-result>

        <template v-else-if="stats">
          <div v-if="stats.dueCount > 0" class="due-count">
            <strong>{{ stats.dueCount }}</strong>
            <span>个词等待回忆</span>
          </div>
          <div v-else class="review-complete">
            <CheckCircleOutlined />
            <div><strong>今日复习已完成</strong><span>可以继续阅读，积累新的专业词汇。</span></div>
          </div>
          <p v-if="stats.dueCount > 0" class="due-hint">先回忆答案，再查看系统判分和术语解析。</p>
          <div class="task-actions">
            <a-button v-if="stats.dueCount > 0" type="primary" @click="router.push('/review')">开始复习</a-button>
            <a-button @click="router.push('/vocabulary')">查看个人词库</a-button>
          </div>
        </template>
      </article>
    </section>

    <section class="summary-section" aria-labelledby="learning-summary-title">
      <div class="section-title-row">
        <h2 id="learning-summary-title" class="section-heading">学习状态</h2>
        <a-button type="link" @click="router.push('/vocabulary')">查看词库 <ArrowRightOutlined /></a-button>
      </div>
      <div v-if="stats" class="summary-strip">
        <div><span>连续学习</span><strong>{{ stats.streakDays }} 天</strong></div>
        <div><span>今日已复习</span><strong>{{ stats.reviewedToday }} 题</strong></div>
        <div><span>7 日正确率</span><strong>{{ stats.accuracy7d }}%</strong></div>
        <div><span>个人词库</span><strong>{{ stats.vocabularyCount }} 词</strong></div>
      </div>
      <a-alert v-else type="error" show-icon message="学习状态暂不可用" />
    </section>

    <section class="secondary-grid">
      <div class="usage-section workspace-surface section-block" aria-labelledby="usage-title">
        <div class="section-title-row">
          <div><h2 id="usage-title" class="section-heading">套餐与用量</h2><span v-if="usage" class="plan-label">{{ usage.plan.name }}</span></div>
          <a-button type="link" @click="router.push('/plans')">查看详情 <ArrowRightOutlined /></a-button>
        </div>
        <a-alert v-if="usageError" type="error" show-icon message="用量信息加载失败" />
        <div v-else-if="usage" class="usage-list">
          <div class="usage-item">
            <div><span>专业翻译</span><b>{{ usage.translationsUsed }} / {{ usage.plan.translationLimit }}</b></div>
            <a-progress :percent="translationPercent" :show-info="false" size="small" />
          </div>
          <div class="usage-item">
            <div><span>AI 判分</span><b>{{ usage.gradingsUsed }} / {{ usage.plan.gradingLimit }}</b></div>
            <a-progress :percent="gradingPercent" :show-info="false" size="small" />
          </div>
        </div>
      </div>

      <nav class="next-actions workspace-surface section-block" aria-labelledby="next-actions-title">
        <h2 id="next-actions-title" class="section-heading">下一步</h2>
        <button type="button" @click="router.push('/translate')"><TranslationOutlined /><span><b>翻译一段开发文档</b><small>保留术语并生成阅读批注</small></span><ArrowRightOutlined /></button>
        <button type="button" @click="router.push('/domains')"><BookOutlined /><span><b>定制 AI 翻译场景</b><small>告诉 AI 资料类型、读者和表达偏好</small></span><ArrowRightOutlined /></button>
        <button type="button" @click="router.push('/confusions')"><FileTextOutlined /><span><b>对比易混词</b><small>整理拼写差异和使用语境</small></span><ArrowRightOutlined /></button>
      </nav>
    </section>
  </template>
</template>

<style scoped>
.today-label { padding-top: 4px; color: var(--color-text-secondary); font-size: 13px; }
.primary-tasks { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(320px, .85fr); overflow: hidden; background: var(--color-bg-surface); border: 1px solid var(--color-border); border-radius: var(--radius-lg); }
.task-panel { min-height: 290px; padding: 22px 24px; }
.task-panel + .task-panel { border-left: 1px solid var(--color-border); }
.task-panel-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.task-panel-header h2 { margin: 5px 0 0; font-size: 17px; }
.task-eyebrow { display: inline-flex; align-items: center; gap: 7px; color: var(--color-text-secondary); font-size: 12px; font-weight: 600; }
.language-code { padding: 3px 7px; color: var(--color-text-secondary); background: var(--color-bg-subtle); border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: 11px; }
.document-excerpt { min-height: 73px; margin: 28px 0 12px; color: #343b49; font-size: 14px; line-height: 1.75; }
.document-meta { display: flex; flex-wrap: wrap; gap: 16px; color: var(--color-text-muted); font-size: 12px; }
.document-meta span { display: inline-flex; align-items: center; gap: 5px; }
.task-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 24px; }
.task-empty { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 14px; min-height: 154px; margin-top: 18px; padding: 18px; background: var(--color-bg-editor); border: 1px dashed var(--color-border-strong); border-radius: var(--radius-md); }
.task-empty > .anticon { color: var(--color-text-muted); font-size: 22px; }
.task-empty strong { display: block; margin-bottom: 3px; }
.task-empty p { margin: 0; color: var(--color-text-secondary); font-size: 13px; }
.due-count { display: flex; align-items: baseline; gap: 10px; margin-top: 26px; }
.due-count strong { color: var(--color-primary); font-family: var(--font-mono); font-size: 48px; font-weight: 650; line-height: 1; }
.due-count span { color: var(--color-text-secondary); }
.due-hint { margin: 16px 0 0; color: var(--color-text-secondary); }
.review-complete { display: flex; align-items: center; gap: 12px; margin-top: 28px; padding: 16px; color: var(--color-success); background: var(--color-success-soft); border: 1px solid #bddbca; border-radius: var(--radius-md); }
.review-complete > .anticon { font-size: 22px; }
.review-complete strong, .review-complete span { display: block; }
.review-complete span { margin-top: 2px; color: #4b6e5d; font-size: 12px; }
.summary-section { margin-top: 24px; }
.section-title-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.section-title-row .section-heading { margin-bottom: 0; }
.summary-strip { display: grid; grid-template-columns: repeat(4, 1fr); margin-top: 10px; overflow: hidden; background: var(--color-bg-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.summary-strip > div { padding: 14px 18px; }
.summary-strip > div + div { border-left: 1px solid var(--color-border); }
.summary-strip span, .summary-strip strong { display: block; }
.summary-strip span { color: var(--color-text-secondary); font-size: 12px; }
.summary-strip strong { margin-top: 4px; font-size: 16px; }
.secondary-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(320px, .8fr); gap: 18px; margin-top: 24px; }
.plan-label { display: inline-block; margin-top: 5px; color: var(--color-text-secondary); font-size: 12px; }
.usage-list { display: grid; gap: 17px; margin-top: 20px; }
.usage-item > div { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 7px; }
.usage-item b { font-family: var(--font-mono); font-size: 12px; }
.next-actions { display: flex; flex-direction: column; }
.next-actions button { display: grid; grid-template-columns: 24px 1fr auto; align-items: center; gap: 10px; width: 100%; padding: 11px 0; color: var(--color-text); text-align: left; background: transparent; border: 0; border-top: 1px solid var(--color-border); cursor: pointer; }
.next-actions button:hover b { color: var(--color-primary); }
.next-actions button > .anticon:first-child { color: var(--color-text-secondary); font-size: 16px; }
.next-actions button > .anticon:last-child { color: var(--color-text-muted); }
.next-actions b, .next-actions small { display: block; }
.next-actions b { font-size: 13px; }
.next-actions small { margin-top: 2px; color: var(--color-text-secondary); font-size: 11px; }
:deep(.ant-result) { padding: 28px 12px 8px; }
:deep(.ant-result-icon) { margin-bottom: 10px; }
:deep(.ant-result-icon .anticon) { font-size: 36px; }
:deep(.ant-result-title) { font-size: 15px; }

@media (max-width: 900px) {
  .primary-tasks, .secondary-grid { grid-template-columns: 1fr; }
  .task-panel + .task-panel { border-top: 1px solid var(--color-border); border-left: 0; }
}

@media (max-width: 640px) {
  .today-label { display: none; }
  .task-panel { min-height: auto; padding: 18px 16px; }
  .task-empty { grid-template-columns: auto 1fr; }
  .task-empty .ant-btn { grid-column: 1 / -1; width: 100%; }
  .summary-strip { grid-template-columns: 1fr 1fr; }
  .summary-strip > div:nth-child(3) { border-top: 1px solid var(--color-border); border-left: 0; }
  .summary-strip > div:nth-child(4) { border-top: 1px solid var(--color-border); }
}
</style>
