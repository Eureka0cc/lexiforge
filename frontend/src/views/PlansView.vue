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
interface BillingOrder { id: string; channel: string; cycle: string; planName: string; amountMinor: number; currency: string; status: string; tradeNo?: string; createdAt: string; expiresAt: string }
const billing = ref({ enabled: false, mode: 'DISABLED', officialChannelConnected: false })
const orders = ref<BillingOrder[]>([])
const checkoutOpen = ref(false)
const cycle = ref('MONTH')
const channel = ref('ALIPAY_SIMULATOR')
const busy = ref('')
let draft: { key: string; requestId: string } | null = null
const currentPlanCode = computed(() => subscription.value?.status === 'ACTIVE' && (!subscription.value.expiresAt || Date.parse(subscription.value.expiresAt) > Date.now()) ? subscription.value.planCode : 'FREE')
const statusLabel: Record<string, string> = { PENDING: '待支付', PAID: '已支付', REFUNDED: '已退款', CANCELLED: '已取消' }

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const [planResponse, subscriptionResponse, configResponse, ordersResponse] = await Promise.all([
      api.get<Plan[]>('/api/plans/v1'),
      api.get<Subscription>('/api/subscriptions/v1'),
      api.get<typeof billing.value>('/api/billing/config/v1'),
      api.get<BillingOrder[]>('/api/billing/orders/v1'),
    ])
    plans.value = planResponse.data
    subscription.value = subscriptionResponse.data
    billing.value = configResponse.data
    orders.value = ordersResponse.data
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function refreshOrders() {
  const [orderResponse, subscriptionResponse] = await Promise.all([
    api.get<BillingOrder[]>('/api/billing/orders/v1'), api.get<Subscription>('/api/subscriptions/v1'),
  ])
  orders.value = orderResponse.data; subscription.value = subscriptionResponse.data
}
async function createOrder() {
  if (busy.value) return
  busy.value = 'create'
  const key = cycle.value + '|' + channel.value
  if (!draft || draft.key !== key) draft = { key, requestId: crypto.randomUUID() }
  try {
    await api.post('/api/billing/orders/v1', { requestId: draft.requestId, cycle: cycle.value, channel: channel.value })
    draft = null; checkoutOpen.value = false
    await refreshOrders()
    message.success('Sandbox 订单已创建，请在订单中模拟支付')
  } catch { /* 全局客户端展示错误；保留请求编号，重试不会重复创建订单。 */ }
  finally { busy.value = '' }
}
async function act(order: BillingOrder, action: 'sandbox-pay' | 'sandbox-refund' | 'cancel' | 'query') {
  if (busy.value) return
  busy.value = order.id
  try {
    const path = `/api/billing/orders/${order.id}/${action === 'query' ? 'v1' : action + '/v1'}`
    if (action === 'query') await api.get(path)
    else await api.post(path)
    await refreshOrders()
    message.success(action === 'sandbox-refund' ? '模拟退款已确认' : action === 'sandbox-pay' ? '模拟支付结果已确认' : action === 'cancel' ? '订单已取消' : '订单状态已同步')
  } catch { /* 保留订单，允许查询服务端状态或重试。 */ }
  finally { busy.value = '' }
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
      <div><b>Sandbox 订阅</b><span>月付 30 天 / 年付 365 天，手动续费。</span></div>
      <div v-for="plan in plans" :key="plan.id">
        <a-button v-if="plan.code === 'PRO'" type="primary" :disabled="!billing.enabled || !!busy" @click="checkoutOpen = true">{{ currentPlanCode === 'PRO' ? 'Sandbox 续费 Pro' : 'Sandbox 订阅 Pro' }}</a-button>
        <a-button v-else disabled>免费套餐</a-button>
      </div>
    </footer>
  </section>

  <a-alert class="billing-note" type="info" show-icon :message="billing.enabled ? '本地 Sandbox：不会发生真实扣款' : 'Sandbox 支付未启用'" description="支付宝和微信均为本地模拟渠道，尚未连接官方 Sandbox。支付结果由服务端确认，退款撤销对应订单期限，保留其他订单和学习数据。" />
  <p v-if="subscription?.expiresAt">当前权益到期时间：{{ new Date(subscription.expiresAt).toLocaleString() }}</p>
  <section v-if="!loading && !loadError" class="billing-orders workspace-surface" aria-label="Sandbox 订单">
    <h2>Sandbox 订单</h2>
    <a-empty v-if="!orders.length" description="还没有订单" />
    <article v-for="order in orders" :key="order.id" class="billing-order" :aria-label="`订单 ${order.id}`">
      <div><strong>{{ order.planName }} · {{ order.cycle === 'ANNUAL' ? '年付' : '月付' }}</strong><a-tag>{{ statusLabel[order.status] || order.status }}</a-tag></div>
      <p>{{ order.channel === 'ALIPAY_SIMULATOR' ? '支付宝本地模拟' : '微信本地模拟' }} · ¥{{ (order.amountMinor / 100).toFixed(2) }} <span class="mono">{{ order.id }}</span></p>
      <div class="order-actions">
        <a-button v-if="order.status === 'PENDING'" type="primary" :disabled="!!busy" @click="act(order, 'sandbox-pay')">模拟支付</a-button>
        <a-button :disabled="!!busy" @click="act(order, 'query')">查询状态</a-button>
        <a-button v-if="order.status === 'PENDING'" :disabled="!!busy" @click="act(order, 'cancel')">取消订单</a-button>
        <a-popconfirm v-if="order.status === 'PAID'" title="确认整单模拟退款并撤销对应期限？" ok-text="确认退款" cancel-text="取消" @confirm="act(order, 'sandbox-refund')"><a-button danger :disabled="!!busy">模拟退款</a-button></a-popconfirm>
      </div>
    </article>
  </section>
  <a-modal v-model:open="checkoutOpen" title="创建 Sandbox 订阅订单" :confirm-loading="busy === 'create'" ok-text="创建订单" cancel-text="取消" :closable="!busy" :mask-closable="!busy" @ok="createOrder">
    <a-form layout="vertical">
      <a-form-item label="订阅周期"><a-radio-group v-model:value="cycle" :disabled="!!busy"><a-radio value="MONTH">月付（30 天）</a-radio><a-radio value="ANNUAL">年付（365 天）</a-radio></a-radio-group></a-form-item>
      <a-form-item label="模拟渠道"><a-radio-group v-model:value="channel" :disabled="!!busy"><a-radio value="ALIPAY_SIMULATOR">支付宝本地模拟</a-radio><a-radio value="WECHAT_SIMULATOR">微信本地模拟</a-radio></a-radio-group></a-form-item>
      <p>¥{{ cycle === 'ANNUAL' ? plans.find(p => p.code === 'PRO')?.annualPrice : plans.find(p => p.code === 'PRO')?.monthlyPrice }}，无需真实付款。创建订单后可模拟支付。</p>
    </a-form>
  </a-modal>
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
.billing-orders{margin-top:24px;padding:20px}.billing-orders h2{font-size:18px}.billing-order{padding:16px 0;border-top:1px solid var(--color-border)}.billing-order>div:first-child{display:flex;gap:12px;flex-wrap:wrap}.billing-order p{color:var(--color-text-secondary);overflow-wrap:anywhere}.order-actions{display:flex;gap:8px;flex-wrap:wrap}
</style>
