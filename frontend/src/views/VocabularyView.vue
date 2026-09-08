<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { DeleteOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, SoundOutlined } from '@ant-design/icons-vue'
import { api } from '../api'
import type { Vocabulary } from '../types'

interface WordPack {
  id: string
  name: string
  description: string
  itemCount: number
  imported: boolean
}

const words = ref<Vocabulary[]>([])
const packs = ref<WordPack[]>([])
const query = ref('')
const sourceFilter = ref('ALL')
const partOfSpeechFilter = ref('ALL')
const selectedIds = ref<string[]>([])
const open = ref(false)
const loading = ref(false)
const loadError = ref(false)
const saving = ref(false)
const deleting = ref(false)
const importingId = ref('')
const formRef = ref<FormInstance>()
const form = reactive({
  word: '',
  partOfSpeech: '',
  ipa: '',
  chineseMeaning: '',
  englishDefinition: '',
  exampleSentence: '',
  sourceContext: '',
  sourceType: 'MANUAL',
})

const sourceOptions = computed(() => [...new Set(words.value.map((word) => word.sourceType).filter(Boolean))])
const partOfSpeechOptions = computed(() => [...new Set(words.value.map((word) => word.partOfSpeech).filter(Boolean))] as string[])
const availablePacks = computed(() => packs.value.filter((pack) => !pack.imported))
const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return words.value.filter((word) => {
    const matchesKeyword = !keyword || `${word.word} ${word.chineseMeaning} ${word.englishDefinition ?? ''}`.toLowerCase().includes(keyword)
    const matchesSource = sourceFilter.value === 'ALL' || word.sourceType === sourceFilter.value
    const matchesPart = partOfSpeechFilter.value === 'ALL' || word.partOfSpeech === partOfSpeechFilter.value
    return matchesKeyword && matchesSource && matchesPart
  })
})

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const [wordResponse, packResponse] = await Promise.all([
      api.get<Vocabulary[]>('/api/vocabulary/v1'),
      api.get<WordPack[]>('/api/word-packs/v1'),
    ])
    words.value = wordResponse.data
    packs.value = packResponse.data
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function create() {
  await formRef.value?.validate()
  saving.value = true
  try {
    await api.post('/api/vocabulary/v1', form)
    open.value = false
    Object.assign(form, { word: '', partOfSpeech: '', ipa: '', chineseMeaning: '', englishDefinition: '', exampleSentence: '', sourceContext: '', sourceType: 'MANUAL' })
    formRef.value?.clearValidate()
    message.success('单词已加入个人词库')
    await load()
  } finally {
    saving.value = false
  }
}

async function importPack(id: string) {
  importingId.value = id
  try {
    const { data } = await api.post<{ imported: number }>(`/api/word-packs/${id}/import/v1`)
    message.success(`已导入 ${data.imported} 个单词`)
    await load()
  } finally {
    importingId.value = ''
  }
}

async function remove(id: string) {
  await api.delete(`/api/vocabulary/${id}/v1`)
  selectedIds.value = selectedIds.value.filter((selectedId) => selectedId !== id)
  message.success('已从词库删除')
  await load()
}

async function removeSelected() {
  deleting.value = true
  try {
    await Promise.all(selectedIds.value.map((id) => api.delete(`/api/vocabulary/${id}/v1`)))
    message.success(`已删除 ${selectedIds.value.length} 个单词`)
    selectedIds.value = []
    await load()
  } finally {
    deleting.value = false
  }
}

function speak(text: string) {
  const utterance = new SpeechSynthesisUtterance(text)
  utterance.lang = 'en-US'
  speechSynthesis.speak(utterance)
}
</script>

<template>
  <div class="page-header">
    <div><h1>个人词库</h1><p>整理阅读中遇到的专业词汇，并保留它们的真实使用语境。</p></div>
    <div class="page-header-actions"><a-button type="primary" @click="open = true"><PlusOutlined />手动添加</a-button></div>
  </div>

  <section v-if="availablePacks.length" class="pack-notice" aria-label="可导入词包">
    <div><b>{{ availablePacks[0].name }}</b><span>{{ availablePacks[0].itemCount }} 词 · {{ availablePacks[0].description }}</span></div>
    <a-button size="small" :loading="importingId === availablePacks[0].id" @click="importPack(availablePacks[0].id)">导入词包</a-button>
  </section>

  <section class="vocabulary-workspace workspace-surface" aria-label="词库列表">
    <div class="vocabulary-toolbar">
      <a-input v-model:value="query" allow-clear placeholder="搜索单词、中文或英文释义">
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <a-select v-model:value="sourceFilter" aria-label="按来源筛选">
        <a-select-option value="ALL">全部来源</a-select-option>
        <a-select-option v-for="sourceType in sourceOptions" :key="sourceType" :value="sourceType">{{ sourceType }}</a-select-option>
      </a-select>
      <a-select v-model:value="partOfSpeechFilter" aria-label="按词性筛选">
        <a-select-option value="ALL">全部词性</a-select-option>
        <a-select-option v-for="part in partOfSpeechOptions" :key="part" :value="part">{{ part }}</a-select-option>
      </a-select>
      <span class="vocabulary-count">{{ filtered.length }} 个单词</span>
    </div>

    <div v-if="selectedIds.length" class="batch-toolbar" role="status">
      <span>已选择 {{ selectedIds.length }} 项</span>
      <a-popconfirm title="确认删除选中的单词？此操作不可撤销。" @confirm="removeSelected">
        <a-button danger size="small" :loading="deleting"><DeleteOutlined />批量删除</a-button>
      </a-popconfirm>
      <a-button type="link" size="small" @click="selectedIds = []">取消选择</a-button>
    </div>

    <a-skeleton v-if="loading" class="table-state" active :paragraph="{ rows: 8 }" />
    <a-result v-else-if="loadError" status="error" title="词库加载失败" sub-title="请检查网络连接后重试">
      <template #extra><a-button @click="load"><ReloadOutlined />重新加载</a-button></template>
    </a-result>
    <a-empty v-else-if="!words.length" class="table-state" description="词库还是空的，从技术文档中收藏第一个术语吧">
      <a-button type="primary" @click="$router.push('/translate')">前往文档翻译</a-button>
    </a-empty>
    <a-empty v-else-if="!filtered.length" class="table-state" description="没有符合当前筛选条件的单词">
      <a-button @click="query = ''; sourceFilter = 'ALL'; partOfSpeechFilter = 'ALL'">清除筛选</a-button>
    </a-empty>

    <a-table
      v-else
      v-model:selected-row-keys="selectedIds"
      class="vocabulary-table"
      :data-source="filtered"
      :row-selection="{}"
      row-key="id"
      size="middle"
      :scroll="{ x: 920 }"
      :pagination="{ pageSize: 12, showSizeChanger: false }"
    >
      <a-table-column title="单词" key="word" :width="220">
        <template #default="{ record }">
          <div class="word-cell">
            <div><strong class="term-word">{{ record.word }}</strong><a-button type="text" size="small" :aria-label="`朗读 ${record.word}`" @click="speak(record.word)"><SoundOutlined /></a-button></div>
            <span class="mono">{{ record.ipa || '—' }} · {{ record.partOfSpeech || '未标注词性' }}</span>
          </div>
        </template>
      </a-table-column>
      <a-table-column title="核心释义" data-index="chineseMeaning" :width="220" />
      <a-table-column title="来源语境" key="example" :width="340">
        <template #default="{ record }"><span class="context-cell">{{ record.exampleSentence || record.sourceContext || '—' }}</span></template>
      </a-table-column>
      <a-table-column title="来源" key="source" :width="110">
        <template #default="{ record }"><a-tag>{{ record.sourceType }}</a-tag></template>
      </a-table-column>
      <a-table-column title="操作" key="action" :width="80" fixed="right">
        <template #default="{ record }">
          <a-popconfirm title="确认删除这个单词？" @confirm="remove(record.id)"><a-button danger type="text" size="small">删除</a-button></a-popconfirm>
        </template>
      </a-table-column>
    </a-table>
  </section>

  <a-modal v-model:open="open" title="添加到个人词库" :confirm-loading="saving" ok-text="添加" cancel-text="取消" @ok="create">
    <a-form ref="formRef" :model="form" layout="vertical" required-mark="optional">
      <a-form-item label="英文单词" name="word" :rules="[{ required: true, message: '请输入英文单词' }, { max: 120, message: '最多 120 个字符' }]">
        <a-input v-model:value="form.word" class="mono" />
      </a-form-item>
      <a-row :gutter="12">
        <a-col :span="12"><a-form-item label="词性" name="partOfSpeech"><a-input v-model:value="form.partOfSpeech" placeholder="noun / verb" /></a-form-item></a-col>
        <a-col :span="12"><a-form-item label="音标" name="ipa"><a-input v-model:value="form.ipa" class="mono" /></a-form-item></a-col>
      </a-row>
      <a-form-item label="中文释义" name="chineseMeaning" :rules="[{ required: true, message: '请输入中文释义' }, { max: 500, message: '最多 500 个字符' }]">
        <a-input v-model:value="form.chineseMeaning" />
      </a-form-item>
      <a-form-item label="英文解释" name="englishDefinition"><a-input v-model:value="form.englishDefinition" /></a-form-item>
      <a-form-item label="开发语境例句" name="exampleSentence"><a-textarea v-model:value="form.exampleSentence" :rows="3" /></a-form-item>
    </a-form>
  </a-modal>
</template>

<style scoped>
.pack-notice { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; padding: 11px 14px; background: #eef1fb; border: 1px solid #cbd2ee; border-radius: var(--radius-md); }
.pack-notice b, .pack-notice span { display: block; }
.pack-notice b { font-size: 13px; }
.pack-notice span { margin-top: 2px; color: var(--color-text-secondary); font-size: 12px; }
.vocabulary-workspace { overflow: hidden; }
.vocabulary-toolbar { display: grid; grid-template-columns: minmax(260px, 1fr) 150px 150px auto; align-items: center; gap: 10px; padding: 12px; background: #eceef2; border-bottom: 1px solid var(--color-border); }
.vocabulary-count { color: var(--color-text-secondary); font-size: 12px; white-space: nowrap; }
.batch-toolbar { display: flex; align-items: center; gap: 8px; min-height: 43px; padding: 7px 12px; color: #273575; background: var(--color-primary-soft); border-bottom: 1px solid #c6cced; }
.batch-toolbar span { margin-right: auto; font-size: 12px; font-weight: 600; }
.table-state { padding: 48px 20px; }
.word-cell > div { display: flex; align-items: center; gap: 2px; }
.word-cell strong { color: #26358a; font-size: 15px; }
.word-cell span { color: var(--color-text-secondary); font-size: 11px; }
.context-cell { display: -webkit-box; overflow: hidden; color: var(--color-text-secondary); font-size: 12px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.vocabulary-table :deep(.ant-table) { border-radius: 0; }
@media (max-width: 760px) {
  .pack-notice { align-items: stretch; flex-direction: column; }
  .vocabulary-toolbar { grid-template-columns: 1fr 1fr; }
  .vocabulary-toolbar > .ant-input-affix-wrapper, .vocabulary-count { grid-column: 1 / -1; }
  .vocabulary-count { text-align: right; }
}
</style>
