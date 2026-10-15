<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { CopyOutlined, HistoryOutlined, ReloadOutlined, SettingOutlined, SwapOutlined, TranslationOutlined } from '@ant-design/icons-vue'
import { api, postSse } from '../api'
import type { Domain, Lookup, LookupDetails, Translation } from '../types'
import DocumentPane from '../components/translation/DocumentPane.vue'
import { createFrameText } from '../lib/frameText'

type Side = 'SOURCE' | 'TARGET'
type MobileTab = 'source' | 'result'

const router = useRouter()
const route = useRoute()
const domains = ref<Domain[]>([])
const domainId = ref('')
const source = ref('')
const sourceLanguage = ref('AUTO')
const targetLanguage = ref('ZH')
const result = ref<Translation | null>(null)
const streamedText = ref('')
const stale = ref(false)
const translating = ref(false)
const stopped = ref(false)
const composing = ref(false)
const translationError = ref('')
const translationComplete = ref(false)
const history = ref<Translation[]>([])
const historyOpen = ref(false)
const historyLoading = ref(false)
const domainsLoading = ref(false)
const domainsError = ref(false)
const mobileTab = ref<MobileTab>('source')
const sourceEditor = ref<HTMLTextAreaElement>()
const targetArticle = ref<HTMLElement>()
const lookup = ref<Lookup | null>(null)
const lookupDetails = ref<LookupDetails | null>(null)
const lookupLoading = ref(false)
const detailsLoading = ref(false)
const collecting = ref(false)
const popover = ref({ left: 24, top: 24, maxHeight: innerHeight - 36 })

const AUTO_TRANSLATE_DELAY = 900
let timer: ReturnType<typeof setTimeout> | null = null
let controller: AbortController | null = null
let version = 0
let suppressWatch = false
let lookupVersion = 0
let lookupController: AbortController | null = null
let detailsController: AbortController | null = null
function closeLookup() {
  lookupVersion += 1
  lookupController?.abort(); lookupController = null
  detailsController?.abort(); detailsController = null
  lookup.value = null; lookupDetails.value = null
  lookupLoading.value = false; detailsLoading.value = false; collecting.value = false
}
function outsideLookup(event: Event) {
  const target = event.target
  if (target instanceof Element && (target.closest('.lookup-popover') || target.closest('.source-editor') || target.closest('.translated-document'))) return
  closeLookup()
}
function lookupKey(event: KeyboardEvent) { if (event.key === 'Escape') closeLookup() }
const displayBuffer = createFrameText(text => {
  stale.value = false
  streamedText.value += text
})

const chars = computed(() => source.value.length)
const visibleTranslation = computed(() => streamedText.value || result.value?.translatedText || '')
const canLookup = computed(() => translationComplete.value && !!result.value && !stale.value && !translating.value && !translationError.value)

onMounted(async () => {
  document.addEventListener('pointerdown', outsideLookup)
  document.addEventListener('keydown', lookupKey)
  window.addEventListener('resize', closeLookup)
  hydrateContinuation()
  await Promise.all([loadDomains(), loadHistory()])
})
onBeforeUnmount(() => {
  invalidate()
  document.removeEventListener('pointerdown', outsideLookup)
  document.removeEventListener('keydown', lookupKey)
  window.removeEventListener('resize', closeLookup)
})

watch([source, sourceLanguage, targetLanguage, domainId], () => {
  if (suppressWatch) return
  version += 1
  displayBuffer.clear()
  closeLookup()
  controller?.abort()
  controller = null
  translating.value = false
  translationComplete.value = false
  streamedText.value = ''
  stale.value = !!result.value
  stopped.value = false
  lookup.value = null
  lookupDetails.value = null
  translationError.value = ''
  schedule()
}, { flush: 'sync' })

function requestId() {
  return crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}
function hydrateContinuation() {
  const saved = sessionStorage.getItem('lexiforge:last-translation')
  if (!saved) return
  try { selectHistory(JSON.parse(saved) as Translation) }
  catch { /* 损坏的临时记录不影响翻译页启动。 */ }
  finally { sessionStorage.removeItem('lexiforge:last-translation') }
}
async function loadDomains() {
  domainsLoading.value = true
  domainsError.value = false
  try {
    domains.value = (await api.get<Domain[]>('/api/domains/v1')).data
    if (!domainId.value) {
      const requested = typeof route.query.scene === 'string' ? route.query.scene : ''
      domainId.value = domains.value.some(item => item.id === requested)
        ? requested : (domains.value.find(item => item.code === 'IT')?.id ?? domains.value[0]?.id ?? '')
    }
  } catch { domainsError.value = true } finally { domainsLoading.value = false }
}
async function loadHistory() {
  historyLoading.value = true
  try { history.value = (await api.get<{ items: Translation[] }>('/api/translations/v1?size=12')).data.items }
  finally { historyLoading.value = false }
}
function validInput() {
  if (!source.value.trim() || !domainId.value) return false
  if (domainId.value === 'ARCHIVED') { translationError.value = '请选择一个可用场景后再翻译'; return false }
  if (source.value.length > 5000) { translationError.value = '原文不能超过 5,000 个字符'; return false }
  if (sourceLanguage.value === targetLanguage.value) { translationError.value = '源语言与目标语言不能相同'; return false }
  return true
}
function schedule() {
  clearTimer()
  if (!composing.value && !stopped.value && validInput()) timer = setTimeout(() => void translate(), AUTO_TRANSLATE_DELAY)
}
function clearTimer() { if (timer) clearTimeout(timer); timer = null }
async function translate(forceRefresh = false) {
  clearTimer()
  if (!validInput()) return
  controller?.abort()
  const ownVersion = ++version
  displayBuffer.clear()
  closeLookup()
  const aborter = new AbortController()
  controller = aborter
  lookup.value = null
  lookupDetails.value = null
  const fallback = forceRefresh && translationComplete.value && !stale.value ? result.value : null
  translating.value = true
  translationComplete.value = false
  stopped.value = false
  streamedText.value = ''
  translationError.value = ''
  const payload = { requestId: requestId(), sourceText: source.value, sourceLanguage: sourceLanguage.value, targetLanguage: targetLanguage.value, domainId: domainId.value, forceRefresh }
  let completed = false
  try {
    await postSse('/api/translations/stream/v1', payload, aborter.signal, ({ event, data }) => {
      if (ownVersion !== version) return
      if (event === 'delta') displayBuffer.append((data as { text: string }).text)
      if (event === 'completed') {
        displayBuffer.flush()
        const translated = (data as { translation: Translation }).translation
        result.value = translated
        translationComplete.value = true
        streamedText.value = translated.translatedText
        stale.value = false
        completed = true
        mobileTab.value = 'result'
        history.value = [translated, ...history.value.filter(item => item.id !== translated.id)].slice(0, 12)
      }
      if (event === 'error') throw new Error((data as { message: string }).message)
    })
    if (!completed && ownVersion === version) throw new Error('连接已中断，翻译未完成')
  } catch (error) {
    if (ownVersion !== version || aborter.signal.aborted) return
    if (fallback) {
      displayBuffer.clear()
      streamedText.value = fallback.translatedText
      translationComplete.value = true
    }
    translationError.value = error instanceof Error ? error.message : '翻译未完成，请稍后重试'
  } finally { if (ownVersion === version) { displayBuffer.flush(); translating.value = false } }
}
function stop() {
  clearTimer()
  displayBuffer.flush()
  stopped.value = true
  version += 1
  controller?.abort()
  controller = null
  translating.value = false
  translationComplete.value = false
  if (streamedText.value) translationError.value = '翻译已停止，当前内容未完成，不能查词或收藏'
}
function invalidate() { clearTimer(); displayBuffer.clear(); closeLookup(); version += 1; controller?.abort(); controller = null }
function compositionStart() { composing.value = true; clearTimer(); closeLookup() }
function compositionEnd() { composing.value = false; schedule() }
function swapLanguages() {
  invalidate()
  const old = sourceLanguage.value
  sourceLanguage.value = targetLanguage.value
  targetLanguage.value = old === 'AUTO' ? 'EN' : old
  if (result.value && !stale.value) source.value = result.value.translatedText
  result.value = null
  streamedText.value = ''
  mobileTab.value = 'source'
}
function selectHistory(item: Translation) {
  invalidate()
  suppressWatch = true
  result.value = item
  translationComplete.value = true
  streamedText.value = item.translatedText
  source.value = item.sourceText
  sourceLanguage.value = item.sourceLanguage
  targetLanguage.value = item.targetLanguage
  domainId.value = item.domainId ?? 'ARCHIVED'
  stale.value = false
  translationError.value = ''
  historyOpen.value = false
  mobileTab.value = 'result'
  nextTick(() => { suppressWatch = false })
}
function wordRange(text: string, offset: number) {
  if (!('Segmenter' in Intl)) {
    const match = /[\p{L}\p{N}_-]+/gu
    for (const found of text.matchAll(match)) {
      const start = found.index
      if (offset >= start && offset <= start + found[0].length) return [start, start + found[0].length]
    }
    return null
  }
  const segments = new Intl.Segmenter(undefined, { granularity: 'word' }).segment(text)
  for (const part of segments) {
    if (part.isWordLike && offset >= part.index && offset <= part.index + part.segment.length) return [part.index, part.index + part.segment.length]
  }
  return null
}
function sourceSelection(event: MouseEvent | KeyboardEvent) {
  if (!canLookup.value || composing.value || !sourceEditor.value) return
  let start = sourceEditor.value.selectionStart
  let end = sourceEditor.value.selectionEnd
  if (start === end) {
    const range = wordRange(source.value, start)
    if (!range) return
    ;[start, end] = range
  }
  void performLookup('SOURCE', start, end, event)
}
function targetSelection(event: MouseEvent) {
  if (!canLookup.value || !targetArticle.value) return
  const selection = window.getSelection()
  if (!selection || !selection.rangeCount) return
  let range = selection.getRangeAt(0)
  if (!targetArticle.value.contains(range.commonAncestorContainer)) return
  if (range.collapsed) {
    const caret = document.caretPositionFromPoint?.(event.clientX, event.clientY)
    if (!caret || !targetArticle.value.contains(caret.offsetNode)) return
    range = document.createRange(); range.setStart(caret.offsetNode, caret.offset); range.collapse(true)
  }
  const before = document.createRange()
  before.selectNodeContents(targetArticle.value)
  before.setEnd(range.startContainer, range.startOffset)
  let start = before.toString().length
  let end = start + range.toString().length
  if (start === end) {
    const found = wordRange(result.value!.translatedText, start)
    if (!found) return
    ;[start, end] = found
  }
  void performLookup('TARGET', start, end, event)
}
async function performLookup(side: Side, start: number, end: number, event: MouseEvent | KeyboardEvent) {
  if (!result.value) return
  if (end - start > 120) { message.warning('请选择不超过 120 个字符'); return }
  closeLookup()
  const ownVersion = version
  const ownLookup = lookupVersion
  const aborter = new AbortController()
  lookupController = aborter
  lookupLoading.value = true
  if ('clientX' in event) positionPopover(event.clientX, event.clientY)
  try {
    const response = await api.post<Lookup>(`/api/translations/${result.value.id}/lookups/v1`, { requestId: requestId(), side, start, end }, { signal: aborter.signal })
    if (ownVersion === version && ownLookup === lookupVersion) lookup.value = response.data
  } catch { /* 全局客户端显示错误；已取消的请求不提示、不覆盖状态。 */ }
  finally { if (ownVersion === version && ownLookup === lookupVersion) lookupLoading.value = false }
}
function positionPopover(x: number, y: number) {
  const top = Math.max(12, Math.min(y + 14, innerHeight - 310))
  popover.value = { left: Math.max(12, Math.min(x, innerWidth - 372)), top, maxHeight: innerHeight - top - 12 }
}
async function loadDetails() {
  if (!lookup.value || lookupDetails.value || detailsLoading.value) return
  const ownLookup = lookupVersion
  const id = lookup.value.lookupId
  const aborter = new AbortController()
  detailsController = aborter
  detailsLoading.value = true
  try {
    const data = (await api.post<LookupDetails>(`/api/lookups/${id}/details/v1`, { requestId: requestId() }, { signal: aborter.signal })).data
    if (ownLookup === lookupVersion && lookup.value?.lookupId === id) lookupDetails.value = data
  } catch { /* 保留简要解释及收藏，允许重试详情。 */ }
  finally { if (ownLookup === lookupVersion) detailsLoading.value = false }
}
async function collect() {
  if (!lookup.value?.collectable || collecting.value) return
  const ownLookup = lookupVersion
  const id = lookup.value.lookupId
  collecting.value = true
  try { await api.post(`/api/vocabulary/lookup/${id}/v1`); message.success('已加入个人词库') }
  catch { /* 全局客户端显示失败，保留解释。 */ }
  finally { if (ownLookup === lookupVersion) collecting.value = false }
}
async function copyTranslation() {
  if (!canLookup.value || !result.value) return
  try { await navigator.clipboard.writeText(result.value.translatedText); message.success('译文已复制') }
  catch { message.error('复制失败，请手动选择译文') }
}
</script>

<template>
  <div class="page-header translation-page-header">
    <div><h1>AI 翻译</h1><p>先获得连贯译文，再按需点击或划选查词。</p></div>
    <a-button @click="historyOpen = true"><HistoryOutlined />最近翻译</a-button>
  </div>
  <section class="translation-shell" aria-label="翻译工作区" @keydown.esc="closeLookup">
    <div class="translation-toolbar">
      <div class="toolbar-group domain-control">
        <label for="domain-select">翻译场景</label>
        <a-select id="domain-select" v-model:value="domainId" :loading="domainsLoading" :disabled="domainsError">
          <a-select-option v-if="domainId === 'ARCHIVED'" value="ARCHIVED" disabled>{{ result?.sceneSnapshot?.name || '历史场景' }}（历史快照）</a-select-option>
          <a-select-option v-for="domain in domains" :key="domain.id" :value="domain.id">{{ domain.name }}</a-select-option>
        </a-select>
        <a-button v-if="domainsError" type="link" @click="loadDomains"><ReloadOutlined />重试</a-button>
      </div>
      <div class="toolbar-group language-controls">
        <a-select v-model:value="sourceLanguage"><a-select-option value="AUTO">自动检测</a-select-option><a-select-option value="EN">英文</a-select-option><a-select-option value="ZH">中文</a-select-option></a-select>
        <a-button aria-label="交换语言" @click="swapLanguages"><SwapOutlined /></a-button>
        <a-select v-model:value="targetLanguage"><a-select-option value="ZH">中文</a-select-option><a-select-option value="EN">英文</a-select-option></a-select>
      </div>
      <div class="toolbar-actions">
        <span>停止输入 900ms 后自动翻译</span>
        <a-button @click="router.push('/domains')"><SettingOutlined />翻译场景</a-button>
        <a-button v-if="translating" danger @click="stop">停止</a-button>
        <a-button v-else type="primary" :disabled="!source.trim() || !domainId || sourceLanguage === targetLanguage" @click="translate(false)"><TranslationOutlined />立即翻译</a-button>
        <a-button v-if="result && !translating" :disabled="!translationComplete || stale || !source.trim() || !domainId || sourceLanguage === targetLanguage" title="忽略已有结果，重新调用 AI；成功后计入翻译额度" @click="translate(true)"><ReloadOutlined />重新翻译</a-button>
      </div>
    </div>
    <a-alert v-if="translationError" class="workspace-alert" type="error" show-icon :message="translationError" closable @close="translationError = ''" />
    <div class="mobile-workspace-tabs"><button :class="{ active: mobileTab === 'source' }" @click="mobileTab = 'source'">原文</button><button :class="{ active: mobileTab === 'result' }" @click="mobileTab = 'result'">译文</button></div>
    <div class="document-workspace">
      <DocumentPane :class="{ 'mobile-pane-active': mobileTab === 'source' }" title="原文">
        <textarea ref="sourceEditor" v-model="source" class="source-editor mono" maxlength="5000" aria-label="待翻译原文" placeholder="输入单词、句子或段落……"
          @compositionstart="compositionStart" @compositionend="compositionEnd" @mouseup="sourceSelection" @keyup="sourceSelection"
          @keydown.ctrl.enter.prevent="translate(false)" @keydown.meta.enter.prevent="translate(false)" />
        <template #footer><span>{{ chars.toLocaleString() }} / 5,000 字符</span></template>
      </DocumentPane>
      <DocumentPane class="result-document" :class="{ 'mobile-pane-active': mobileTab === 'result' }" title="译文">
        <template #actions><a-button type="text" :disabled="!canLookup" aria-label="复制译文" @click="copyTranslation"><CopyOutlined /></a-button></template>
        <article v-if="visibleTranslation" ref="targetArticle" class="translated-document" :class="{ stale }" @mouseup="targetSelection">
          <p>{{ visibleTranslation }}</p>
          <a-alert v-if="stale" type="info" show-icon message="内容已修改，等待更新；当前旧译文暂不可查词" />
          <a-alert v-else-if="translating" type="info" show-icon message="正在翻译…" />
          <div v-if="result?.alternatives?.length && !stale && !translating" class="alternatives"><strong>其他可能含义</strong><div v-for="item in result.alternatives" :key="item.meaning">{{ item.meaning }}<small v-if="item.note"> — {{ item.note }}</small></div></div>
        </article>
        <div v-else class="result-state"><TranslationOutlined /><strong>{{ translating ? '正在连接翻译服务…' : '译文将在这里显示' }}</strong></div>
        <template #footer><span>{{ translating ? '正在翻译' : canLookup ? '完成 · 可点击或划选查词' : translationError ? '未完成' : '等待翻译' }}</span></template>
      </DocumentPane>
    </div>
  </section>
  <div v-if="lookupLoading || lookup" class="lookup-popover" :style="{ left: `${popover.left}px`, top: `${popover.top}px`, maxHeight: `${popover.maxHeight}px`, overflowY: 'auto' }" role="dialog" aria-label="语境解释">
    <button class="close" aria-label="关闭" @click="closeLookup">×</button>
    <a-spin v-if="lookupLoading" size="small" tip="正在理解当前语境…" />
    <template v-else-if="lookup">
      <strong class="selected">{{ lookup.selectedText }}</strong><div class="meaning">{{ lookup.meaning }}</div><p>{{ lookup.explanation }}</p>
      <p class="counterpart">对应表达：{{ lookup.counterpartText || '无直接对应表达' }}</p>
      <div v-if="lookupDetails" class="details"><b>{{ lookupDetails.partOfSpeech }} {{ lookupDetails.ipa }}</b><p>{{ lookupDetails.exampleSentence }}</p><small>{{ lookupDetails.exampleTranslation }}</small></div>
      <div class="lookup-actions"><a-tooltip :title="lookup.collectable ? '' : '没有可靠英文对应表达，不能加入当前英语词库'"><span><a-button :disabled="!lookup.collectable" :loading="collecting" @click="collect">收藏</a-button></span></a-tooltip><a-button type="link" :loading="detailsLoading" @click="loadDetails">展开详情</a-button></div>
    </template>
  </div>
  <a-drawer v-model:open="historyOpen" title="最近翻译" placement="right" :width="460">
    <a-skeleton v-if="historyLoading" active /><a-empty v-else-if="!history.length" description="还没有翻译记录" />
    <div v-else class="history-list"><button v-for="item in history" :key="item.id" class="history-item" @click="selectHistory(item)"><b>{{ item.sourceText }}</b><span>{{ item.translatedText }}</span></button></div>
  </a-drawer>
</template>

<style scoped>
.translation-page-header{margin-bottom:16px}.translation-shell{overflow:hidden;background:var(--color-bg-surface);border:1px solid var(--color-border-strong);border-radius:var(--radius-lg)}.translation-toolbar{display:flex;align-items:center;gap:18px;min-height:58px;padding:10px 12px;background:#e9ebef;border-bottom:1px solid var(--color-border-strong)}
.toolbar-group,.toolbar-actions{display:flex;align-items:center;gap:8px}.toolbar-group label,.toolbar-actions span{color:var(--color-text-secondary);font-size:12px}.domain-control :deep(.ant-select){width:210px}.language-controls{margin-left:auto}.language-controls :deep(.ant-select){width:116px}.workspace-alert{margin:12px}.document-workspace{display:grid;grid-template-columns:1fr 1fr}.result-document{border-left:1px solid var(--color-border-strong)}
.source-editor{display:block;width:100%;min-height:421px;height:100%;padding:20px;resize:none;border:0;outline:0;background:var(--color-bg-editor);line-height:var(--line-height-document)}.translated-document{min-height:421px;padding:20px 22px;overflow:auto;user-select:text}.translated-document.stale{opacity:.62}.translated-document>p{margin:0;white-space:pre-wrap;line-height:var(--line-height-document)}.translated-document .ant-alert{margin-top:16px}.result-state{min-height:421px;display:flex;gap:10px;align-items:center;justify-content:center;color:var(--color-text-secondary)}.alternatives{margin-top:20px;padding:12px;border-radius:8px;background:var(--color-bg-subtle)}.alternatives div{margin-top:6px}
.lookup-popover{position:fixed;z-index:1100;width:min(360px,calc(100vw - 24px));padding:18px;background:white;border:1px solid var(--color-border-strong);border-radius:12px;box-shadow:0 12px 38px rgb(0 0 0 / 18%)}.lookup-popover .close{position:absolute;right:8px;top:6px;border:0;background:none;font-size:22px;cursor:pointer}.selected{display:block;padding-right:20px;font-size:16px}.meaning{margin-top:8px;color:var(--color-primary);font-weight:600}.lookup-popover p{margin:8px 0}.counterpart,.details small{color:var(--color-text-secondary)}.details{margin-top:10px;padding-top:10px;border-top:1px solid var(--color-border)}.lookup-actions{display:flex;justify-content:space-between;margin-top:12px}
.mobile-workspace-tabs{display:none}.history-list{display:grid;gap:8px}.history-item{display:grid;gap:6px;padding:12px;text-align:left;border:1px solid var(--color-border);border-radius:8px;background:white;cursor:pointer}.history-item b,.history-item span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
@media(max-width:900px){.translation-toolbar{flex-wrap:wrap}.domain-control{width:100%}.domain-control :deep(.ant-select){flex:1;width:auto}.language-controls{margin-left:0}.toolbar-actions{width:100%}.toolbar-actions>span{display:none}.mobile-workspace-tabs{display:grid;grid-template-columns:1fr 1fr;padding:5px}.mobile-workspace-tabs button{height:34px;border:0;background:transparent}.mobile-workspace-tabs button.active{background:white;font-weight:600}.document-workspace{display:block}.document-workspace>:deep(.document-pane){display:none}.document-workspace>:deep(.mobile-pane-active){display:flex}.result-document{border-left:0}}
</style>
