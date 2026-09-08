<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { api } from '../api'

interface AdminMetrics {
  users?: number
  dau?: number
  wau?: number
  activePro?: number
  estimatedMrr?: number
  mnemonics?: number
  mnemonicHelpfulRate?: number
}

const data = ref<AdminMetrics>({})
const loading = ref(false)
const loadError = ref(false)
const rows = computed(() => [
  { group: '用户', label: '注册用户', value: data.value.users ?? '—', note: '累计注册账户' },
  { group: '用户', label: '日活跃用户', value: data.value.dau ?? '—', note: '最近一个自然日' },
  { group: '用户', label: '周活跃用户', value: data.value.wau ?? '—', note: '最近 7 天' },
  { group: '订阅', label: '有效 Pro', value: data.value.activePro ?? '—', note: '当前有效订阅' },
  { group: '订阅', label: '预估 MRR', value: data.value.estimatedMrr == null ? '—' : `¥${data.value.estimatedMrr}`, note: '按有效订阅估算' },
  { group: '学习', label: '记忆法生成量', value: data.value.mnemonics ?? '—', note: '累计生成次数' },
  { group: '学习', label: '记忆法有效率', value: data.value.mnemonicHelpfulRate == null ? '—' : `${data.value.mnemonicHelpfulRate}%`, note: '用户标记为有帮助的比例' },
])

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    data.value = (await api.get<AdminMetrics>('/api/admin/metrics/v1')).data
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-header"><div><h1>运营指标</h1><p>只读查看用户、订阅和学习功能使用情况。</p></div></div>
  <a-skeleton v-if="loading" active :paragraph="{ rows: 8 }" />
  <a-result v-else-if="loadError" status="error" title="运营指标加载失败"><template #extra><a-button @click="load"><ReloadOutlined />重新加载</a-button></template></a-result>
  <section v-else class="metrics-workspace workspace-surface" aria-label="运营指标列表">
    <header><span>分类</span><span>指标</span><span>当前值</span><span>说明</span></header>
    <div v-for="row in rows" :key="row.label" class="metric-row"><span>{{ row.group }}</span><b>{{ row.label }}</b><strong class="mono">{{ row.value }}</strong><span>{{ row.note }}</span></div>
  </section>
</template>

<style scoped>
.metrics-workspace { overflow: hidden; }
.metrics-workspace > header, .metric-row { display: grid; grid-template-columns: 120px minmax(180px, 1fr) 170px minmax(220px, 1.4fr); align-items: center; }
.metrics-workspace > header { min-width: 720px; color: var(--color-text-secondary); background: #eceef2; border-bottom: 1px solid var(--color-border); font-size: 11px; font-weight: 600; }
.metrics-workspace > header span, .metric-row > * { padding: 11px 16px; }
.metric-row { min-width: 720px; }
.metric-row + .metric-row { border-top: 1px solid var(--color-border); }
.metric-row > span { color: var(--color-text-secondary); font-size: 12px; }
.metric-row b { font-size: 13px; }
.metric-row strong { color: #26358a; font-size: 16px; }
@media (max-width: 760px) { .metrics-workspace { overflow-x: auto; } }
</style>
