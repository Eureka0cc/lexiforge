<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { BulbOutlined, PlusOutlined, ReloadOutlined, SwapOutlined, LikeOutlined } from '@ant-design/icons-vue'
import { api } from '../api'

interface ConfusionWord {
  word: string
  meaning: string
  ipa?: string
  partOfSpeech?: string
  exampleSentence?: string
}

interface Mnemonic {
  id: string
  style: string
  slogan: string
  visualHook: string
  itAnalogy: string
  contrastExample: string
  recallHook: string
  helpful?: boolean
}

interface ConfusionGroup {
  id: string
  name: string
  note?: string
  words: ConfusionWord[]
  mnemonics: Mnemonic[]
}

const groups = ref<ConfusionGroup[]>([])
const open = ref(false)
const loading = ref(false)
const loadError = ref(false)
const saving = ref(false)
const generating = ref('')
const formRef = ref<FormInstance>()
const form = reactive({ name: '', note: '', words: [{ word: '', meaning: '' }, { word: '', meaning: '' }] })

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    groups.value = (await api.get<ConfusionGroup[]>('/api/confusion-groups/v1')).data
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function addWord() {
  if (form.words.length < 8) form.words.push({ word: '', meaning: '' })
}

function removeWord(index: number) {
  if (form.words.length > 2) form.words.splice(index, 1)
}

async function create() {
  await formRef.value?.validate()
  saving.value = true
  try {
    await api.post('/api/confusion-groups/v1', form)
    open.value = false
    Object.assign(form, { name: '', note: '', words: [{ word: '', meaning: '' }, { word: '', meaning: '' }] })
    message.success('易混词组已创建')
    await load()
  } finally {
    saving.value = false
  }
}

async function generate(id: string) {
  generating.value = id
  try {
    await api.post(`/api/confusion-groups/${id}/mnemonics/v1`, {
      style: 'PROGRAMMER',
      mistakeContext: '我在阅读开发文档时容易把这些词认错',
    })
    message.success('辅助记忆法已生成')
    await load()
  } finally {
    generating.value = ''
  }
}

async function rate(groupId: string, mnemonicId: string, helpful: boolean) {
  await api.patch(`/api/confusion-groups/${groupId}/mnemonics/${mnemonicId}/v1`, { helpful })
  message.success('反馈已记录')
  await load()
}

function isDifferentAt(words: ConfusionWord[], index: number) {
  return new Set(words.map((word) => word.word[index] ?? '')).size > 1
}
</script>

<template>
  <div class="page-header">
    <div><h1>易混词</h1><p>并排比较拼写、核心含义、使用语境和例句。</p></div>
    <div class="page-header-actions"><a-button type="primary" @click="open = true"><PlusOutlined />创建词组</a-button></div>
  </div>

  <a-skeleton v-if="loading" active :paragraph="{ rows: 10 }" />
  <a-result v-else-if="loadError" status="error" title="易混词加载失败">
    <template #extra><a-button @click="load"><ReloadOutlined />重新加载</a-button></template>
  </a-result>
  <a-empty v-else-if="!groups.length" class="confusion-empty workspace-surface" description="阅读时遇到相似单词，可以把它们放在一起比较">
    <a-button type="primary" @click="open = true">创建第一个易混词组</a-button>
  </a-empty>

  <div v-else class="confusion-list">
    <section v-for="group in groups" :key="group.id" class="comparison-workspace workspace-surface">
      <header class="comparison-header">
        <div><span class="comparison-label"><SwapOutlined /> 对比组</span><h2>{{ group.name }}</h2><p>{{ group.note || '暂无混淆原因说明' }}</p></div>
        <a-button :loading="generating === group.id" @click="generate(group.id)"><BulbOutlined />生成辅助记忆法</a-button>
      </header>

      <div class="spelling-comparison" aria-label="拼写差异">
        <div v-for="word in group.words" :key="word.word" class="spelling-word">
          <span
            v-for="(letter, index) in word.word"
            :key="index"
            :class="{ different: isDifferentAt(group.words, index) }"
          >{{ letter }}</span>
        </div>
      </div>

      <div class="comparison-grid" :style="{ '--word-count': group.words.length }">
        <article v-for="word in group.words" :key="word.word">
          <header><strong class="term-word">{{ word.word }}</strong><span class="mono">{{ word.ipa || '—' }} · {{ word.partOfSpeech || '未标注词性' }}</span></header>
          <dl>
            <div><dt>核心含义</dt><dd>{{ word.meaning }}</dd></div>
            <div><dt>使用语境</dt><dd>{{ word.exampleSentence ? '见下方例句' : '暂无语境说明' }}</dd></div>
            <div><dt>例句</dt><dd class="mono">{{ word.exampleSentence || '—' }}</dd></div>
          </dl>
        </article>
      </div>

      <a-collapse v-if="group.mnemonics.length" class="mnemonic-section" ghost>
        <a-collapse-panel key="mnemonics" :header="`辅助记忆法 · ${group.mnemonics.length}`">
          <article v-for="mnemonic in group.mnemonics" :key="mnemonic.id" class="mnemonic-note">
            <header><BulbOutlined /><strong>{{ mnemonic.slogan }}</strong><span class="mono">{{ mnemonic.style }}</span></header>
            <dl><div><dt>拼写钩子</dt><dd>{{ mnemonic.visualHook }}</dd></div><div><dt>程序员类比</dt><dd>{{ mnemonic.itAnalogy }}</dd></div><div><dt>回忆提示</dt><dd>{{ mnemonic.recallHook }}</dd></div></dl>
            <div class="mnemonic-feedback"><span>这个方法有帮助吗？</span><a-button size="small" :type="mnemonic.helpful === true ? 'primary' : 'default'" @click="rate(group.id, mnemonic.id, true)"><LikeOutlined />有帮助</a-button><a-button size="small" @click="rate(group.id, mnemonic.id, false)">换个思路</a-button></div>
          </article>
        </a-collapse-panel>
      </a-collapse>
    </section>
  </div>

  <a-modal v-model:open="open" title="创建易混词组" :confirm-loading="saving" ok-text="创建" cancel-text="取消" @ok="create">
    <a-form ref="formRef" :model="form" layout="vertical" required-mark="optional">
      <a-form-item label="词组名称" name="name" :rules="[{ required: true, message: '请输入词组名称' }, { max: 120, message: '最多 120 个字符' }]">
        <a-input v-model:value="form.name" placeholder="例如：though / thought" />
      </a-form-item>
      <div v-for="(word, index) in form.words" :key="index" class="word-form-row">
        <a-form-item :label="`单词 ${index + 1}`" :name="['words', index, 'word']" :rules="[{ required: true, message: '请输入单词' }]">
          <a-input v-model:value="word.word" class="mono" />
        </a-form-item>
        <a-form-item label="核心释义" :name="['words', index, 'meaning']"><a-input v-model:value="word.meaning" /></a-form-item>
        <a-button v-if="form.words.length > 2" danger type="text" aria-label="移除这个单词" @click="removeWord(index)">移除</a-button>
      </div>
      <a-button type="dashed" block :disabled="form.words.length >= 8" @click="addWord">添加更多单词</a-button>
      <a-form-item class="note-field" label="为什么容易混淆" name="note"><a-textarea v-model:value="form.note" :rows="3" /></a-form-item>
    </a-form>
  </a-modal>
</template>

<style scoped>
.confusion-empty { padding: 64px 20px; }
.confusion-list { display: grid; gap: 18px; }
.comparison-workspace { overflow: hidden; }
.comparison-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 18px 20px; background: #f1f2f5; border-bottom: 1px solid var(--color-border); }
.comparison-label { color: var(--color-text-secondary); font-size: 11px; font-weight: 600; }
.comparison-header h2 { margin: 5px 0 3px; font-size: 17px; }
.comparison-header p { margin: 0; color: var(--color-text-secondary); font-size: 12px; }
.spelling-comparison { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 16px 28px; padding: 20px; background: #f8f8fa; border-bottom: 1px solid var(--color-border); }
.spelling-word { display: flex; font-family: var(--font-mono); font-size: 23px; font-weight: 650; letter-spacing: .02em; }
.spelling-word span { min-width: 17px; text-align: center; }
.spelling-word span.different { color: #26358a; background: var(--color-term-bg); border-bottom: 2px solid var(--color-term-border); }
.comparison-grid { display: grid; grid-template-columns: repeat(var(--word-count), minmax(220px, 1fr)); overflow-x: auto; }
.comparison-grid article { min-width: 220px; padding: 18px; }
.comparison-grid article + article { border-left: 1px solid var(--color-border); }
.comparison-grid article header strong, .comparison-grid article header span { display: block; }
.comparison-grid article header strong { color: #26358a; font-size: 17px; }
.comparison-grid article header span { margin-top: 3px; color: var(--color-text-secondary); font-size: 11px; }
.comparison-grid dl, .mnemonic-note dl { margin: 15px 0 0; }
.comparison-grid dl div { padding: 10px 0; border-top: 1px solid var(--color-border); }
dt { color: var(--color-text-muted); font-size: 10px; font-weight: 600; text-transform: uppercase; }
dd { margin: 4px 0 0; font-size: 13px; line-height: 1.6; }
.comparison-grid dd.mono { color: var(--color-text-secondary); font-size: 11px; }
.mnemonic-section { border-top: 1px solid var(--color-border); }
.mnemonic-note { padding: 14px; background: var(--color-bg-editor); border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.mnemonic-note + .mnemonic-note { margin-top: 10px; }
.mnemonic-note header { display: flex; align-items: center; gap: 8px; }
.mnemonic-note header span { margin-left: auto; color: var(--color-text-muted); font-size: 10px; }
.mnemonic-note dl { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.mnemonic-feedback { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--color-border); }
.mnemonic-feedback span { margin-right: auto; color: var(--color-text-secondary); font-size: 11px; }
.word-form-row { display: grid; grid-template-columns: 1fr 1.25fr auto; align-items: start; gap: 8px; }
.note-field { margin-top: 18px; }
@media (max-width: 640px) {
  .comparison-header { align-items: stretch; flex-direction: column; }
  .comparison-header .ant-btn { width: 100%; }
  .mnemonic-note dl { grid-template-columns: 1fr; }
  .word-form-row { grid-template-columns: 1fr 1fr; }
  .word-form-row > .ant-btn { grid-column: 1 / -1; justify-self: end; }
}
</style>
