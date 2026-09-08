<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { CheckOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { api } from '../api'

interface Plan {
  id: string
  code: string
  name: string
  monthlyPrice: number
  annualPrice: number
  translationLimit: number
  gradingLimit: number
  mnemonicLimit: number
  customDomainLimit: number
  mnemonicVersions: number
}

interface Subscription {
  status: string
  planCode: string
  expiresAt?: string
}

const plans = ref<Plan[]>([])
const subscription = ref<Subscription | null>(null)
const loading = ref(false)
const loadError = ref(false)
const activating = ref(false)
const currentPlanCode = computed(() => subscription.value?.status === 'ACTIVE' ? subscription.value.planCode : 'FREE')

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const [planResponse, subscriptionResponse] = await Promise.all([
      api.get<Plan[]>('/api/plans/v1'),
      api.get<Subscription>('/api/subscriptions/v1'),
    ])
    plans.value = planResponse.data
    subscription.value = subscriptionResponse.data
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function activate() {
  activating.value = true
  try {
    subscription.value = (await api.post<Subscription>('/api/subscriptions/dev/activate/v1?days=30')).data
    message.success('开发测试订阅已激活')
  } catch {
    message.info('测试订阅只在开发环境开放，未发生任何扣款')
  } finally {
    activating.value = false
  }
}
</script>

<template>
  <div class="page-header">
    <div><h1>套餐与用量</h1><p>查看当前权益和每日功能额度。</p></div>
    <a-tag>{{ currentPlanCode === 'PRO' ? '当前：Pro' : '当前：免费版' }}</a-tag>
  </div>

  <a-skeleton v-if="loading" active :paragraph="{ rows: 10 }" />
  <a-result v-else-if="loadError" status="error" title="套餐信息加载失败">
    <template #extra><a-button @click="load"><ReloadOutlined />重新加载</a-button></template>
  </a-result>
  <a-empty v-else-if="!plans.length" class="plans-empty workspace-surface" description="当前没有可用套餐" />

  <section v-else class="plan-comparison workspace-surface" aria-label="套餐对比">
    <header class="plan-grid plan-grid-header">
      <div><h2>功能与额度</h2><span>额度按自然日重置</span></div>
      <div v-for="plan in plans" :key="plan.id" :class="{ active: plan.code === currentPlanCode }">
        <span v-if="plan.code === currentPlanCode" class="current-label">当前套餐</span>
        <h3>{{ plan.name }}</h3>
        <p v-if="plan.code === 'PRO'"><strong>¥{{ plan.monthlyPrice }}</strong> / 月</p>
        <p v-else><strong>¥0</strong> 长期使用</p>
      </div>
    </header>

    <div class="plan-grid feature-row"><span>专业文档翻译</span><div v-for="plan in plans" :key="plan.id"><CheckOutlined />每日 {{ plan.translationLimit }} 次</div></div>
    <div class="plan-grid feature-row"><span>AI 语义判分</span><div v-for="plan in plans" :key="plan.id"><CheckOutlined />每日 {{ plan.gradingLimit }} 次</div></div>
    <div class="plan-grid feature-row"><span>辅助记忆法</span><div v-for="plan in plans" :key="plan.id"><CheckOutlined />每日 {{ plan.mnemonicLimit }} 次</div></div>
    <div class="plan-grid feature-row"><span>自定义领域</span><div v-for="plan in plans" :key="plan.id"><CheckOutlined />{{ plan.customDomainLimit }} 个</div></div>
    <div class="plan-grid feature-row"><span>记忆法版本</span><div v-for="plan in plans" :key="plan.id"><CheckOutlined />每组 {{ plan.mnemonicVersions }} 个</div></div>

    <footer class="plan-grid plan-actions">
      <div><b>说明</b><span>当前版本的升级入口只用于验证权益流程。</span></div>
      <div v-for="plan in plans" :key="plan.id">
        <a-button v-if="plan.code === currentPlanCode" disabled>正在使用</a-button>
        <a-button v-else-if="plan.code === 'PRO'" type="primary" :loading="activating" @click="activate">开发模式试用 Pro</a-button>
        <a-button v-else disabled>免费套餐</a-button>
      </div>
    </footer>
  </section>

  <a-alert class="billing-note" type="info" show-icon message="当前不会发生真实扣款" description="支付宝和微信支付尚未接入；开发测试订阅只验证套餐权益。" />
</template>

<style scoped>
.plans-empty { padding: 64px 20px; }
.plan-comparison { overflow-x: auto; }
.plan-grid { display: grid; min-width: 760px; grid-template-columns: minmax(230px, 1.25fr) repeat(2, minmax(220px, 1fr)); }
.plan-grid > * { padding: 15px 20px; }
.plan-grid > * + * { border-left: 1px solid var(--color-border); }
.plan-grid-header { background: #eef0f3; border-bottom: 1px solid var(--color-border-strong); }
.plan-grid-header h2 { margin: 4px 0; font-size: 16px; }
.plan-grid-header > div:first-child span { color: var(--color-text-secondary); font-size: 11px; }
.plan-grid-header > div:not(:first-child) { position: relative; }
.plan-grid-header h3 { margin: 0 0 8px; font-size: 16px; }
.plan-grid-header p { margin: 0; color: var(--color-text-secondary); font-size: 12px; }
.plan-grid-header p strong { color: var(--color-text); font-family: var(--font-mono); font-size: 23px; }
.current-label { display: inline-block; margin-bottom: 6px; color: var(--color-primary); font-size: 10px; font-weight: 700; }
.feature-row { border-bottom: 1px solid var(--color-border); }
.feature-row > span { color: var(--color-text-secondary); font-size: 12px; font-weight: 600; }
.feature-row > div { display: flex; align-items: center; gap: 7px; font-size: 13px; }
.feature-row .anticon { color: var(--color-success); }
.plan-actions { background: var(--color-bg-editor); }
.plan-actions > div:first-child b, .plan-actions > div:first-child span { display: block; }
.plan-actions > div:first-child span { margin-top: 4px; color: var(--color-text-secondary); font-size: 11px; }
.plan-actions .ant-btn { width: 100%; }
.billing-note { margin-top: 16px; }
</style>
