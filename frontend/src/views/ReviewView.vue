<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  SoundOutlined,
  WarningOutlined,
} from '@ant-design/icons-vue'
import { api } from '../api'

interface ReviewItem {
  vocabularyId: string
  direction: 'EN_TO_ZH' | 'ZH_TO_EN'
  prompt: string
  partOfSpeech?: string
  ipa?: string
  context?: string
}

interface ReviewSession {
  id: string
  items: ReviewItem[]
}

interface AnswerResult {
  result: 'CORRECT' | 'PARTIAL' | 'WRONG'
  feedback: string
  expectedAnswer: string
  confusedWith?: string
}

const direction = ref('EN_TO_ZH')
const limit = ref(10)
const sessionId = ref('')
const items = ref<ReviewItem[]>([])
const index = ref(0)
const answer = ref('')
const result = ref<AnswerResult | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const answerInput = ref<{ focus: () => void }>()

const current = computed(() => items.value[index.value])
const remaining = computed(() => Math.max(0, items.value.length - index.value - 1))
const progress = computed(() => items.value.length ? Math.round(((index.value + 1) / items.value.length) * 100) : 0)
const resultMeta = computed(() => {
  if (!result.value) return null
  if (result.value.result === 'CORRECT') return { label: '回答正确', className: 'correct', icon: CheckCircleOutlined }
  if (result.value.result === 'PARTIAL') return { label: '部分正确', className: 'partial', icon: WarningOutlined }
  return { label: '回答错误', className: 'wrong', icon: CloseCircleOutlined }
})

async function start() {
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await api.post<ReviewSession>('/api/review-sessions/v1', { direction: direction.value, limit: limit.value })
    if (!data.items.length) {
      message.info('目前没有到期单词，可以先阅读文档或整理词库')
      return
    }
    sessionId.value = data.id
    items.value = data.items
    index.value = 0
    result.value = null
    await nextTick()
    answerInput.value?.focus()
  } catch {
    errorMessage.value = '复习任务创建失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!answer.value.trim() || !current.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    result.value = (
      await api.post<AnswerResult>(`/api/review-sessions/${sessionId.value}/answers/v1`, {
        vocabularyId: current.value.vocabularyId,
        direction: current.value.direction,
        answer: answer.value,
      })
    ).data
  } catch {
    errorMessage.value = '答案提交失败，你的当前输入已保留'
  } finally {
    loading.value = false
  }
}

async function next() {
  result.value = null
  answer.value = ''
  index.value += 1
  if (index.value >= items.value.length) {
    loading.value = true
    try {
      await api.post(`/api/review-sessions/${sessionId.value}/finish/v1`)
      message.success('本轮复习完成')
      items.value = []
      sessionId.value = ''
      index.value = 0
    } finally {
      loading.value = false
    }
    return
  }
  await nextTick()
  answerInput.value?.focus()
}

function handleEnter() {
  if (loading.value) return
  if (result.value) next()
  else submit()
}

function speak() {
  if (!current.value) return
  const utterance = new SpeechSynthesisUtterance(current.value.prompt)
  utterance.lang = 'en-US'
  speechSynthesis.speak(utterance)
}
</script>

<template>
  <div class="page-header review-page-header">
    <div><h1>开始复习</h1><p>先主动回忆，再查看判分和标准答案。</p></div>
  </div>

  <main class="review-workspace">
    <section v-if="!sessionId" class="review-setup workspace-surface" aria-labelledby="review-setup-title">
      <div class="setup-copy">
        <span class="mono">REVIEW SESSION</span>
        <h2 id="review-setup-title">开始一轮主动回忆</h2>
        <p>默认英译中，更贴近阅读英文开发文档的目标。系统会根据回答结果安排下次复习。</p>
      </div>
      <a-alert v-if="errorMessage" type="error" show-icon :message="errorMessage" />
      <div class="setup-controls">
        <label>复习方向<a-select v-model:value="direction"><a-select-option value="EN_TO_ZH">英文 → 中文</a-select-option><a-select-option value="ZH_TO_EN">中文 → 英文</a-select-option><a-select-option value="MIXED">双向混合</a-select-option></a-select></label>
        <label>题目数量<a-input-number v-model:value="limit" :min="1" :max="50" addon-after="题" /></label>
        <a-button type="primary" size="large" :loading="loading" @click="start">开始复习</a-button>
      </div>
      <p class="keyboard-hint">键盘提示：输入答案后按 Enter 提交；查看解析后按 Enter 进入下一题。</p>
    </section>

    <section v-else-if="current" class="review-session workspace-surface" aria-labelledby="review-question-title">
      <header class="session-progress">
        <div><strong>第 {{ index + 1 }} 题</strong><span>剩余 {{ remaining }} 题</span></div>
        <a-progress :percent="progress" :show-info="false" size="small" />
      </header>

      <div class="question-area">
        <p id="review-question-title" class="question-label">
          {{ current.direction === 'EN_TO_ZH' ? '这个词在技术文档中是什么意思？' : '写出对应的英文单词' }}
        </p>
        <div class="review-prompt" :class="{ mono: current.direction === 'EN_TO_ZH' }">
          {{ current.prompt }}
          <a-button v-if="current.direction === 'EN_TO_ZH'" type="text" aria-label="朗读当前单词" @click="speak"><SoundOutlined /></a-button>
        </div>
        <div class="prompt-meta"><span>{{ current.partOfSpeech || '未标注词性' }}</span><span v-if="current.ipa" class="mono">{{ current.ipa }}</span></div>
        <blockquote v-if="current.context">{{ current.context }}</blockquote>
      </div>

      <div class="answer-area">
        <label for="review-answer">你的答案</label>
        <a-input
          id="review-answer"
          ref="answerInput"
          v-model:value="answer"
          size="large"
          :disabled="Boolean(result)"
          placeholder="输入你真正想到的答案"
          @press-enter="handleEnter"
        />
        <a-alert v-if="errorMessage" type="error" show-icon :message="errorMessage" />
        <a-button v-if="!result" type="primary" size="large" :disabled="!answer.trim()" :loading="loading" @click="submit">提交答案</a-button>
      </div>

      <section v-if="result && resultMeta" class="answer-result" :class="resultMeta.className" aria-live="polite">
        <header><component :is="resultMeta.icon" /><h2>{{ resultMeta.label }}</h2></header>
        <div class="result-details">
          <div><span>你的答案</span><p>{{ answer }}</p></div>
          <div><span>标准答案</span><p><b>{{ result.expectedAnswer }}</b></p></div>
          <div class="feedback-row"><span>解析</span><p>{{ result.feedback }}</p></div>
        </div>
        <a-alert v-if="result.confusedWith" type="warning" show-icon :message="`可能与 ${result.confusedWith} 混淆`" description="完成本轮后可加入易混词，集中比较拼写和使用语境。" />
        <a-button type="primary" size="large" @click="next">{{ index === items.length - 1 ? '完成复习' : '下一题' }}</a-button>
      </section>
    </section>
  </main>
</template>

<style scoped>
.review-page-header { max-width: 860px; margin-inline: auto; }
.review-workspace { max-width: 860px; margin: 0 auto; }
.review-setup { padding: clamp(24px, 5vw, 44px); }
.setup-copy { max-width: 620px; }
.setup-copy > span { color: var(--color-text-muted); font-size: 11px; font-weight: 600; letter-spacing: .08em; }
.setup-copy h2 { margin: 10px 0 8px; font-size: 20px; }
.setup-copy p { margin: 0; color: var(--color-text-secondary); line-height: 1.7; }
.review-setup > .ant-alert { margin-top: 20px; }
.setup-controls { display: grid; grid-template-columns: 1fr 150px auto; align-items: end; gap: 12px; margin-top: 30px; padding-top: 24px; border-top: 1px solid var(--color-border); }
.setup-controls label { display: grid; gap: 7px; color: var(--color-text-secondary); font-size: 12px; font-weight: 600; }
.setup-controls :deep(.ant-select), .setup-controls :deep(.ant-input-number) { width: 100%; }
.keyboard-hint { margin: 18px 0 0; color: var(--color-text-muted); font-size: 11px; }
.review-session { overflow: hidden; }
.session-progress { display: grid; grid-template-columns: 150px 1fr; align-items: center; gap: 24px; padding: 13px 20px; background: #eceef2; border-bottom: 1px solid var(--color-border); }
.session-progress strong, .session-progress span { display: inline-block; }
.session-progress span { margin-left: 8px; color: var(--color-text-secondary); font-size: 12px; }
.question-area { padding: 34px clamp(20px, 6vw, 54px) 28px; text-align: center; }
.question-label { margin: 0; color: var(--color-text-secondary); font-size: 13px; }
.review-prompt { margin: 20px 0 10px; color: #202a55; font-size: clamp(32px, 6vw, 48px); font-weight: 650; line-height: 1.25; }
.prompt-meta { display: flex; justify-content: center; gap: 10px; color: var(--color-text-secondary); font-size: 12px; }
.question-area blockquote { max-width: 650px; margin: 22px auto 0; padding: 10px 14px; color: var(--color-text-secondary); background: var(--color-bg-editor); border-left: 3px solid var(--color-border-strong); font-family: var(--font-mono); font-size: 12px; line-height: 1.65; text-align: left; }
.answer-area { display: grid; gap: 10px; padding: 22px clamp(20px, 6vw, 54px); border-top: 1px solid var(--color-border); }
.answer-area label { color: var(--color-text-secondary); font-size: 12px; font-weight: 600; }
.answer-area > .ant-btn { justify-self: end; min-width: 140px; }
.answer-result { padding: 22px clamp(20px, 6vw, 54px) 28px; border-top: 1px solid; }
.answer-result.correct { background: var(--color-success-soft); border-color: #bddbca; }
.answer-result.partial { background: var(--color-warning-soft); border-color: #e7ce9f; }
.answer-result.wrong { background: var(--color-error-soft); border-color: #ebc1c1; }
.answer-result header { display: flex; align-items: center; gap: 9px; }
.answer-result header .anticon { font-size: 20px; }
.answer-result.correct header { color: var(--color-success); }
.answer-result.partial header { color: var(--color-warning); }
.answer-result.wrong header { color: var(--color-error); }
.answer-result h2 { margin: 0; color: inherit; font-size: 17px; }
.result-details { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 18px 0; }
.result-details > div { padding: 12px; background: rgb(255 255 255 / 55%); border: 1px solid rgb(80 90 105 / 15%); border-radius: var(--radius-md); }
.result-details .feedback-row { grid-column: 1 / -1; }
.result-details span { color: var(--color-text-secondary); font-size: 11px; }
.result-details p { margin: 4px 0 0; }
.answer-result > .ant-btn { margin-top: 18px; }
@media (max-width: 640px) {
  .setup-controls { grid-template-columns: 1fr; }
  .session-progress { grid-template-columns: 1fr; gap: 7px; }
  .result-details { grid-template-columns: 1fr; }
  .result-details .feedback-row { grid-column: auto; }
  .answer-area > .ant-btn, .answer-result > .ant-btn { width: 100%; }
}
</style>
