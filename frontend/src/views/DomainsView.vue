<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { CheckCircleOutlined, DeleteOutlined, PlusOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons-vue'
import { api } from '../api'
import type { Domain } from '../types'

interface SceneTemplate { key: string; name: string; eyebrow: string; description: string; example: string }
const sceneTemplates: SceneTemplate[] = [
  { key: 'IT', name: 'IT / 软件开发', eyebrow: '推荐', description: '理解代码、API、云计算与工程文档，自动采用业内常用译法。', example: '例如：把英文 API 文档翻成适合中国开发者阅读的中文，保留代码和参数名。' },
  { key: 'TECH_LEARNING', name: '技术学习', eyebrow: '学习型', description: '兼顾准确翻译与概念解释，适合课程、教程和技术博客。', example: '例如：面向计算机专业学生，专业词首次出现时保留英文并简要解释。' },
  { key: 'CUSTOM', name: '自由定制', eyebrow: '自定义', description: '直接告诉 AI 你的资料、读者和表达偏好，不需要整理术语表。', example: '例如：我经常翻译芯片数据手册，读者是嵌入式工程师，语言要简洁严谨。' },
]

const domains = ref<Domain[]>([])
const activeId = ref('')
const domainOpen = ref(false)
const loading = ref(false)
const loadError = ref(false)
const saving = ref(false)
const selectedTemplate = ref('CUSTOM')
const domainFormRef = ref<FormInstance>()
const domainForm = reactive({ name: '', description: '' })
const active = computed(() => domains.value.find((domain) => domain.id === activeId.value) ?? null)
const builtInDomains = computed(() => domains.value.filter((domain) => domain.builtIn))
const customDomains = computed(() => domains.value.filter((domain) => !domain.builtIn))

onMounted(load)
async function load() {
  loading.value = true
  loadError.value = false
  const previousActive = activeId.value
  try {
    domains.value = (await api.get<Domain[]>('/api/domains/v1')).data
    activeId.value = domains.value.some((domain) => domain.id === previousActive) ? previousActive : (domains.value.find((domain) => domain.code === 'IT')?.id ?? domains.value[0]?.id ?? '')
  } catch { loadError.value = true } finally { loading.value = false }
}
function selectTemplate(template: SceneTemplate) {
  selectedTemplate.value = template.key
  domainForm.name = template.key === 'CUSTOM' ? '' : template.name
  domainForm.description = template.example.replace('例如：', '')
}
function openSceneCreator(templateKey = 'CUSTOM') {
  selectTemplate(sceneTemplates.find((item) => item.key === templateKey) ?? sceneTemplates[2])
  domainFormRef.value?.clearValidate()
  domainOpen.value = true
}
async function createDomain() {
  await domainFormRef.value?.validate()
  saving.value = true
  try {
    const created = (await api.post<Domain>('/api/domains/v1', domainForm)).data
    domainOpen.value = false
    Object.assign(domainForm, { name: '', description: '' })
    await load()
    activeId.value = created.id
    message.success('AI 翻译场景已创建，可以直接用于翻译')
  } finally { saving.value = false }
}
async function removeDomain(domain: Domain) {
  await api.delete(`/api/domains/${domain.id}/v1`)
  message.success('自定义场景已删除')
  await load()
}
</script>

<template>
  <div class="page-header">
    <div><h1>AI 翻译场景</h1><p>选择一个专业场景，或用一句话告诉 AI 你通常翻什么。</p></div>
    <div class="page-header-actions"><a-button type="primary" @click="openSceneCreator()"><PlusOutlined />定制翻译场景</a-button></div>
  </div>

  <section class="scene-intro workspace-surface">
    <div><span class="scene-kicker">AI 自动适配</span><h2>不需要先录入单词，描述使用场景就够了</h2><p>AI 会根据资料类型、目标读者和表达偏好处理专业术语、语气与解释深度；翻译中识别出的重点词汇仍可按需收藏。</p></div>
    <div class="scene-benefits"><span><CheckCircleOutlined />识别专业语境</span><span><CheckCircleOutlined />自动选择行业译法</span><span><CheckCircleOutlined />保持全文表达一致</span></div>
  </section>

  <div class="template-grid" aria-label="推荐翻译场景">
    <button v-for="template in sceneTemplates" :key="template.key" type="button" @click="openSceneCreator(template.key)"><span>{{ template.eyebrow }}</span><strong>{{ template.name }}</strong><p>{{ template.description }}</p><em>以此定制 →</em></button>
  </div>

  <a-skeleton v-if="loading" active :paragraph="{ rows: 8 }" />
  <a-result v-else-if="loadError" status="error" title="翻译场景加载失败"><template #extra><a-button @click="load"><ReloadOutlined />重新加载</a-button></template></a-result>
  <a-empty v-else-if="!domains.length" class="domain-empty workspace-surface" description="当前没有可用场景"><a-button type="primary" @click="openSceneCreator()">定制第一个场景</a-button></a-empty>

  <section v-else class="domain-workspace workspace-surface" aria-label="我的翻译场景">
    <aside class="domain-master">
      <header><h2>我的翻译场景</h2><span>{{ domains.length }} 个</span></header>
      <div class="domain-section-label">AI 预设</div>
      <button v-for="domain in builtInDomains" :key="domain.id" type="button" :class="{ active: activeId === domain.id }" @click="activeId = domain.id"><span><b>{{ domain.name }}</b><small>开箱即用</small></span><em>预设</em></button>
      <div class="domain-section-label custom-label">我的定制</div>
      <button v-for="domain in customDomains" :key="domain.id" type="button" :class="{ active: activeId === domain.id }" @click="activeId = domain.id"><span><b>{{ domain.name }}</b><small>按描述自动适配</small></span><em>定制</em></button>
      <p v-if="!customDomains.length" class="no-custom-domain">还没有定制场景</p>
    </aside>

    <div v-if="active" class="domain-detail">
      <header class="domain-detail-header">
        <div><span>{{ active.builtIn ? 'AI 预设场景' : '我的定制场景' }}</span><h2>{{ active.name }}</h2><p>{{ active.description }}</p></div>
        <div class="domain-actions"><a-button type="primary" @click="$router.push({ path: '/translate', query: { scene: active.id } })">使用这个场景</a-button><a-popconfirm v-if="!active.builtIn" title="确认删除这个翻译场景？" @confirm="removeDomain(active)"><a-button danger aria-label="删除场景"><DeleteOutlined /></a-button></a-popconfirm></div>
      </header>
      <div v-if="active.medicalDisclaimer" class="domain-warning"><SettingOutlined /><span><b>医学场景提示</b>译文只用于语言学习，不用于诊疗决策。</span></div>
      <div class="ai-behavior"><h3>AI 会如何处理</h3><div class="behavior-grid"><article><b>理解上下文</b><p>先判断文档属于哪个细分主题，再选择符合语境的含义。</p></article><article><b>统一专业表达</b><p>自动保持同一术语在全文中的译法一致，无需逐条维护。</p></article><article><b>适配目标读者</b><p>依据场景描述控制解释深度、语气和中英文保留方式。</p></article></div></div>
      <div v-if="active.terms.length" class="built-in-rules"><div><h3>内置专业规则</h3><p>这些规则由系统随场景自动应用，用户无需录入。</p></div><div class="rule-tags"><a-tag v-for="term in active.terms" :key="term.id">{{ term.sourceTerm }} → {{ term.targetTerm }}</a-tag></div></div>
    </div>
  </section>

  <a-modal v-model:open="domainOpen" title="定制 AI 翻译场景" :width="680" :confirm-loading="saving" ok-text="创建并使用" cancel-text="取消" @ok="createDomain">
    <p class="modal-intro">选择一个起点，再用自然语言描述需求。AI 会在每次翻译时自动应用，无需导入词汇。</p>
    <div class="modal-template-grid"><button v-for="template in sceneTemplates" :key="template.key" type="button" :class="{ active: selectedTemplate === template.key }" @click="selectTemplate(template)"><b>{{ template.name }}</b><span>{{ template.eyebrow }}</span></button></div>
    <a-form ref="domainFormRef" :model="domainForm" layout="vertical" required-mark="optional">
      <a-form-item label="场景名称" name="name" :rules="[{ required: true, message: '请给这个场景起个名字' }, { max: 60, message: '最多 60 个字符' }]"><a-input v-model:value="domainForm.name" placeholder="例如：Java 后端文档" /></a-form-item>
      <a-form-item label="告诉 AI 你的翻译需求" name="description" :rules="[{ required: true, message: '请简单描述你的翻译需求' }, { max: 500, message: '最多 500 个字符' }]"><a-textarea v-model:value="domainForm.description" :rows="5" :maxlength="500" show-count placeholder="我通常翻译什么资料？读者是谁？希望译文是什么风格？" /></a-form-item>
      <div class="prompt-hint"><b>可以这样说：</b>{{ sceneTemplates.find((item) => item.key === selectedTemplate)?.example }}</div>
    </a-form>
  </a-modal>
</template>

<style scoped>
.scene-intro{display:flex;align-items:center;justify-content:space-between;gap:32px;margin-bottom:14px;padding:22px 24px;background:linear-gradient(120deg,#f7f8ff,#eef1fb)}.scene-kicker{color:var(--color-primary);font-size:11px;font-weight:700;letter-spacing:.06em}.scene-intro h2{margin:5px 0 7px;font-size:18px}.scene-intro p{max-width:720px;margin:0;color:var(--color-text-secondary);line-height:1.65}.scene-benefits{display:grid;flex:0 0 190px;gap:8px;color:#354078;font-size:12px}.scene-benefits span{display:flex;align-items:center;gap:7px}.template-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-bottom:18px}.template-grid button{min-height:142px;padding:16px;color:var(--color-text);text-align:left;background:var(--color-bg-surface);border:1px solid var(--color-border);border-radius:var(--radius-lg);cursor:pointer;transition:.15s ease}.template-grid button:hover{border-color:var(--color-primary);box-shadow:0 5px 16px rgb(40 52 120/10%);transform:translateY(-1px)}.template-grid button>span{color:var(--color-primary);font-size:10px;font-weight:700}.template-grid strong{display:block;margin-top:6px;font-size:15px}.template-grid p{min-height:40px;margin:7px 0 10px;color:var(--color-text-secondary);font-size:12px;line-height:1.55}.template-grid em{color:var(--color-primary);font-size:11px;font-style:normal;font-weight:600}.domain-empty{padding:64px 20px}.domain-workspace{display:grid;grid-template-columns:260px minmax(0,1fr);min-height:480px;overflow:hidden}.domain-master{background:#eef0f3;border-right:1px solid var(--color-border-strong)}.domain-master>header{display:flex;align-items:center;justify-content:space-between;height:52px;padding:0 15px;border-bottom:1px solid var(--color-border)}.domain-master>header h2{margin:0;font-size:14px}.domain-master>header span{color:var(--color-text-muted);font-size:11px}.domain-section-label{padding:14px 15px 6px;color:var(--color-text-muted);font-size:10px;font-weight:700;letter-spacing:.06em}.domain-section-label.custom-label{margin-top:5px;border-top:1px solid var(--color-border)}.domain-master button{display:flex;align-items:center;justify-content:space-between;gap:10px;width:calc(100% - 12px);margin:2px 6px;padding:10px;color:var(--color-text);text-align:left;background:transparent;border:0;border-left:3px solid transparent;border-radius:var(--radius-sm);cursor:pointer}.domain-master button:hover{background:#e3e6eb}.domain-master button.active{background:var(--color-bg-surface);border-left-color:var(--color-primary)}.domain-master button b,.domain-master button small{display:block}.domain-master button b{font-size:13px}.domain-master button small{margin-top:2px;color:var(--color-text-secondary);font-size:10px}.domain-master button em{color:var(--color-text-muted);font-size:9px;font-style:normal}.no-custom-domain{padding:6px 15px;color:var(--color-text-muted);font-size:11px}.domain-detail{min-width:0;background:var(--color-bg-surface)}.domain-detail-header{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding:22px 24px;border-bottom:1px solid var(--color-border)}.domain-detail-header span{color:var(--color-text-muted);font-size:10px;font-weight:700}.domain-detail-header h2{margin:5px 0 4px;font-size:19px}.domain-detail-header p{max-width:720px;margin:0;color:var(--color-text-secondary);line-height:1.6}.domain-actions{display:flex;flex-shrink:0;gap:7px}.domain-warning{display:flex;gap:10px;margin:16px 20px 0;padding:11px 13px;color:var(--color-warning);background:var(--color-warning-soft);border:1px solid #e7ce9f;border-radius:var(--radius-md)}.domain-warning b,.domain-warning span{display:block}.domain-warning b{margin-bottom:2px;font-size:12px}.domain-warning span{color:#75521d;font-size:11px}.ai-behavior{padding:24px}.ai-behavior h3,.built-in-rules h3{margin:0 0 12px;font-size:14px}.behavior-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.behavior-grid article{padding:14px;background:var(--color-bg-editor);border:1px solid var(--color-border);border-radius:var(--radius-md)}.behavior-grid b{font-size:12px}.behavior-grid p{margin:6px 0 0;color:var(--color-text-secondary);font-size:11px;line-height:1.55}.built-in-rules{margin:0 24px 24px;padding-top:18px;border-top:1px solid var(--color-border)}.built-in-rules h3{margin-bottom:3px}.built-in-rules p{margin:0 0 12px;color:var(--color-text-muted);font-size:11px}.rule-tags{display:flex;flex-wrap:wrap;gap:6px}.modal-intro{margin:-4px 0 14px;color:var(--color-text-secondary);font-size:13px}.modal-template-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:18px}.modal-template-grid button{display:flex;align-items:center;justify-content:space-between;padding:10px;color:var(--color-text);background:var(--color-bg-surface);border:1px solid var(--color-border);border-radius:var(--radius-md);cursor:pointer}.modal-template-grid button.active{color:var(--color-primary);background:var(--color-primary-soft);border-color:var(--color-primary)}.modal-template-grid b{font-size:12px}.modal-template-grid span{color:var(--color-text-muted);font-size:9px}.prompt-hint{margin-top:-10px;padding:10px 12px;color:var(--color-text-secondary);background:var(--color-bg-editor);border-radius:var(--radius-md);font-size:11px;line-height:1.55}.prompt-hint b{margin-right:4px;color:var(--color-text)}
@media(max-width:760px){.scene-intro{align-items:flex-start;flex-direction:column}.scene-benefits{flex-basis:auto}.template-grid{grid-template-columns:1fr}.template-grid p{min-height:0}.domain-workspace{grid-template-columns:1fr}.domain-master{max-height:280px;overflow-y:auto;border-right:0;border-bottom:1px solid var(--color-border-strong)}.domain-detail-header{align-items:stretch;flex-direction:column;padding:18px 16px}.behavior-grid,.modal-template-grid{grid-template-columns:1fr}.ai-behavior{padding:18px 16px}.built-in-rules{margin-inline:16px}}
</style>
