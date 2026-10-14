<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { CheckCircleOutlined, DeleteOutlined, PlusOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons-vue'
import { api } from '../api'
import type { Domain, SceneConfig, SceneDraft, Translation } from '../types'

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
const router = useRouter()
const editingId = ref('')
const expectedRevision = ref<number | null>(null)
const generating = ref(false)
const previewLoading = ref(false)
const configured = ref(false)
const configDescription = ref('')
const configuration = reactive<SceneConfig>({ domain: '', documentType: '', audience: '', style: '', preserveRules: [], explanationPreference: '' })
const preserveText = ref('')
const previewSource = ref('')
const previewLanguage = ref('ZH')
const previewResult = ref('')
const previewOutput = ref<HTMLElement>()
let modalVersion = 0
let previewVersion = 0
watch([previewSource, previewLanguage, domainForm, configuration, preserveText], () => {
  previewVersion += 1
  previewResult.value = ''
}, { deep: true })
const configStale = computed(() => configured.value && configDescription.value !== domainForm.description)
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
  modalVersion += 1
  generating.value = false
  previewLoading.value = false
  editingId.value = ''
  expectedRevision.value = null
  configured.value = false
  previewSource.value = ''
  previewResult.value = ''
  selectTemplate(sceneTemplates.find((item) => item.key === templateKey) ?? sceneTemplates[2])
  domainFormRef.value?.clearValidate()
  domainOpen.value = true
}
function openSceneEditor(domain: Domain) {
  modalVersion += 1
  generating.value = false
  previewLoading.value = false
  editingId.value = domain.id
  expectedRevision.value = domain.revision
  domainForm.name = domain.name
  domainForm.description = domain.description
  configured.value = !!domain.configuration
  if (domain.configuration) Object.assign(configuration, domain.configuration)
  preserveText.value = domain.configuration?.preserveRules.join('\n') ?? ''
  configDescription.value = domain.description
  previewSource.value = ''
  previewResult.value = ''
  domainFormRef.value?.clearValidate()
  domainOpen.value = true
}
function currentConfiguration(): SceneConfig | null {
  return configured.value ? { ...configuration, preserveRules: preserveText.value.split('\n').map(item => item.trim()).filter(Boolean) } : null
}
function requestId() { return crypto.randomUUID() }
async function generateScene() {
  if (generating.value) return
  if (!domainForm.description.trim()) { message.info('请先描述翻译需求'); return }
  generating.value = true
  const ownVersion = modalVersion
  const description = domainForm.description
  try {
    const { data } = await api.post<SceneDraft>('/api/scenes/generate/v1', { requestId: requestId(), description }, { timeout: 95000 })
    if (ownVersion !== modalVersion || !domainOpen.value || domainForm.description !== description) return
    if (!domainForm.name.trim()) domainForm.name = data.name
    Object.assign(configuration, data.configuration)
    preserveText.value = data.configuration.preserveRules.join('\n')
    configDescription.value = description
    configured.value = true
    message.success('场景草稿已生成，可以修改配置并试译')
  } finally { if (ownVersion === modalVersion) generating.value = false }
}
async function previewScene() {
  await domainFormRef.value?.validate()
  if (!previewSource.value.trim()) { message.info('请输入自己的试译文本'); return }
  if (configStale.value) { message.info('需求已修改，请重新生成配置，或选择仅使用描述'); return }
  previewLoading.value = true
  const ownModal = modalVersion
  const ownPreview = previewVersion
  try {
    const { data } = await api.post<Translation>('/api/scenes/preview/v1', {
      requestId: requestId(), ...domainForm, configuration: currentConfiguration(),
      sourceText: previewSource.value, sourceLanguage: 'AUTO', targetLanguage: previewLanguage.value,
    }, { timeout: 95000 })
    if (ownModal === modalVersion && ownPreview === previewVersion && domainOpen.value) {
      previewResult.value = data.translatedText
      await nextTick()
      previewOutput.value?.scrollIntoView({ block: 'nearest' })
    }
  } finally { if (ownModal === modalVersion) previewLoading.value = false }
}
async function createDomain() {
  await domainFormRef.value?.validate()
  if (generating.value || previewLoading.value) return
  if (configStale.value) { message.info('需求已修改，请重新生成配置，或选择仅使用描述'); return }
  saving.value = true
  try {
    const payload = { ...domainForm, configuration: currentConfiguration(), expectedRevision: expectedRevision.value }
    const created = editingId.value
      ? (await api.patch<Domain>(`/api/domains/${editingId.value}/v1`, payload)).data
      : (await api.post<Domain>('/api/domains/v1', payload)).data
    domainOpen.value = false
    Object.assign(domainForm, { name: '', description: '' })
    await load()
    activeId.value = created.id
    message.success('场景已保存，正在进入翻译')
    await router.push({ path: '/translate', query: { scene: created.id } })
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
    <div class="page-header-actions"><a-button type="primary" aria-label="定制翻译场景" @click="openSceneCreator()"><PlusOutlined aria-hidden="true" />定制翻译场景</a-button></div>
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
        <div class="domain-actions"><a-button type="primary" @click="$router.push({ path: '/translate', query: { scene: active.id } })">使用这个场景</a-button><a-button v-if="!active.builtIn" @click="openSceneEditor(active)">编辑与试译</a-button><a-popconfirm v-if="!active.builtIn" title="确认删除这个翻译场景？" @confirm="removeDomain(active)"><a-button danger aria-label="删除场景"><DeleteOutlined /></a-button></a-popconfirm></div>
      </header>
      <div v-if="active.medicalDisclaimer" class="domain-warning"><SettingOutlined /><span><b>医学场景提示</b>译文只用于语言学习，不用于诊疗决策。</span></div>
      <div class="ai-behavior"><h3>场景配置 <small>版本 {{ active.revision }}</small></h3><template v-if="active.configuration"><a-descriptions :column="1" size="small"><a-descriptions-item label="专业领域">{{ active.configuration.domain }}</a-descriptions-item><a-descriptions-item label="资料类型">{{ active.configuration.documentType || '未限定' }}</a-descriptions-item><a-descriptions-item label="目标读者">{{ active.configuration.audience || '未限定' }}</a-descriptions-item><a-descriptions-item label="表达风格">{{ active.configuration.style || '自然准确' }}</a-descriptions-item><a-descriptions-item label="内容保留">{{ active.configuration.preserveRules.join('；') || '沿用通用保留原则' }}</a-descriptions-item><a-descriptions-item label="解释偏好">{{ active.configuration.explanationPreference || '不额外解释' }}</a-descriptions-item></a-descriptions></template><p v-else>当前按原始描述翻译。自定义场景可通过“编辑与试译”生成结构化配置。</p><p>术语根据实际上下文理解，不预设固定译法。配置效果可通过试译检查。</p></div>
    </div>
  </section>

  <a-modal v-model:open="domainOpen" :title="editingId ? '编辑 AI 翻译场景' : '定制 AI 翻译场景'" :width="760" :style="{ top: '24px' }" :body-style="{ maxHeight: 'calc(100dvh - 180px)', overflowY: 'auto' }" :confirm-loading="saving" :ok-button-props="{ disabled: generating || previewLoading }" ok-text="保存并使用" cancel-text="取消" @ok="createDomain">
    <p class="modal-intro">描述需求，生成并修改配置，用自己的文本试译后保存。正常翻译直接使用已保存配置。</p>
    <div v-if="!editingId" class="modal-template-grid"><button v-for="template in sceneTemplates" :key="template.key" type="button" :class="{ active: selectedTemplate === template.key }" @click="selectTemplate(template)"><b>{{ template.name }}</b><span>{{ template.eyebrow }}</span></button></div>
    <a-form ref="domainFormRef" :model="domainForm" layout="vertical" required-mark="optional">
      <a-form-item label="场景名称" name="name" :rules="[{ required: true, message: '请给这个场景起个名字' }, { max: 60, message: '最多 60 个字符' }]"><a-input v-model:value="domainForm.name" placeholder="例如：Java 后端文档" /></a-form-item>
      <a-form-item label="告诉 AI 你的翻译需求" name="description" :rules="[{ required: true, message: '请简单描述你的翻译需求' }, { max: 500, message: '最多 500 个字符' }]"><a-textarea v-model:value="domainForm.description" :rows="5" :maxlength="500" show-count placeholder="我通常翻译什么资料？读者是谁？希望译文是什么风格？" /></a-form-item>
      <div class="prompt-hint"><b>可以这样说：</b>{{ sceneTemplates.find((item) => item.key === selectedTemplate)?.example }}</div>
      <div class="scene-draft-actions"><a-button type="primary" :loading="generating" @click="generateScene">{{ configured ? '重新整理场景' : 'AI 生成场景草稿' }}</a-button><a-button v-if="configured" @click="configured = false">仅使用原始描述</a-button></div>
      <a-alert v-if="configStale" type="warning" message="需求描述已修改，当前配置尚未同步，请重新整理场景。" show-icon />
      <section v-if="configured" class="scene-config-editor">
        <h3>可编辑的翻译要求</h3>
        <a-form-item label="专业领域"><a-input v-model:value="configuration.domain" aria-label="专业领域" :maxlength="120" /></a-form-item>
        <a-form-item label="资料类型"><a-input v-model:value="configuration.documentType" aria-label="资料类型" :maxlength="120" /></a-form-item>
        <a-form-item label="目标读者"><a-input v-model:value="configuration.audience" aria-label="目标读者" :maxlength="120" /></a-form-item>
        <a-form-item label="表达风格"><a-textarea v-model:value="configuration.style" aria-label="表达风格" :rows="2" :maxlength="300" /></a-form-item>
        <a-form-item label="内容保留规则（每行一条，最多十条）"><a-textarea v-model:value="preserveText" aria-label="内容保留规则" :rows="3" placeholder="例如：保留代码和寄存器名称" /></a-form-item>
        <a-form-item label="解释偏好"><a-textarea v-model:value="configuration.explanationPreference" aria-label="解释偏好" :rows="2" :maxlength="300" /></a-form-item>
      </section>
      <section class="scene-preview"><h3>用自己的内容试译</h3><p>成功试译计入一次翻译额度，结果保留在历史中。</p><a-textarea v-model:value="previewSource" aria-label="试译原文" :rows="4" :maxlength="5000" placeholder="粘贴一段你实际需要翻译的文本" /><div class="scene-draft-actions"><a-select v-model:value="previewLanguage" style="width: 120px"><a-select-option value="ZH">译为中文</a-select-option><a-select-option value="EN">译为英文</a-select-option></a-select><a-button :loading="previewLoading" :disabled="generating || !previewSource.trim()" @click="previewScene">试译当前草稿</a-button></div><pre v-if="previewResult" ref="previewOutput" class="scene-preview-result">{{ previewResult }}</pre></section>
    </a-form>
  </a-modal>
</template>

<style scoped>
.scene-draft-actions{display:flex;gap:10px;margin:16px 0}.scene-config-editor,.scene-preview{margin-top:20px;padding-top:16px;border-top:1px solid var(--color-border)}.scene-preview p{color:var(--color-text-secondary);font-size:12px}.scene-preview-result{padding:16px;white-space:pre-wrap;overflow-wrap:anywhere;background:var(--color-bg-editor);border-radius:8px;line-height:1.7}
.scene-intro{display:flex;align-items:center;justify-content:space-between;gap:32px;margin-bottom:14px;padding:22px 24px;background:linear-gradient(120deg,#f7f8ff,#eef1fb)}.scene-kicker{color:var(--color-primary);font-size:11px;font-weight:700;letter-spacing:.06em}.scene-intro h2{margin:5px 0 7px;font-size:18px}.scene-intro p{max-width:720px;margin:0;color:var(--color-text-secondary);line-height:1.65}.scene-benefits{display:grid;flex:0 0 190px;gap:8px;color:#354078;font-size:12px}.scene-benefits span{display:flex;align-items:center;gap:7px}.template-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-bottom:18px}.template-grid button{min-height:142px;padding:16px;color:var(--color-text);text-align:left;background:var(--color-bg-surface);border:1px solid var(--color-border);border-radius:var(--radius-lg);cursor:pointer;transition:.15s ease}.template-grid button:hover{border-color:var(--color-primary);box-shadow:0 5px 16px rgb(40 52 120/10%);transform:translateY(-1px)}.template-grid button>span{color:var(--color-primary);font-size:10px;font-weight:700}.template-grid strong{display:block;margin-top:6px;font-size:15px}.template-grid p{min-height:40px;margin:7px 0 10px;color:var(--color-text-secondary);font-size:12px;line-height:1.55}.template-grid em{color:var(--color-primary);font-size:11px;font-style:normal;font-weight:600}.domain-empty{padding:64px 20px}.domain-workspace{display:grid;grid-template-columns:260px minmax(0,1fr);min-height:480px;overflow:hidden}.domain-master{background:#eef0f3;border-right:1px solid var(--color-border-strong)}.domain-master>header{display:flex;align-items:center;justify-content:space-between;height:52px;padding:0 15px;border-bottom:1px solid var(--color-border)}.domain-master>header h2{margin:0;font-size:14px}.domain-master>header span{color:var(--color-text-muted);font-size:11px}.domain-section-label{padding:14px 15px 6px;color:var(--color-text-muted);font-size:10px;font-weight:700;letter-spacing:.06em}.domain-section-label.custom-label{margin-top:5px;border-top:1px solid var(--color-border)}.domain-master button{display:flex;align-items:center;justify-content:space-between;gap:10px;width:calc(100% - 12px);margin:2px 6px;padding:10px;color:var(--color-text);text-align:left;background:transparent;border:0;border-left:3px solid transparent;border-radius:var(--radius-sm);cursor:pointer}.domain-master button:hover{background:#e3e6eb}.domain-master button.active{background:var(--color-bg-surface);border-left-color:var(--color-primary)}.domain-master button b,.domain-master button small{display:block}.domain-master button b{font-size:13px}.domain-master button small{margin-top:2px;color:var(--color-text-secondary);font-size:10px}.domain-master button em{color:var(--color-text-muted);font-size:9px;font-style:normal}.no-custom-domain{padding:6px 15px;color:var(--color-text-muted);font-size:11px}.domain-detail{min-width:0;background:var(--color-bg-surface)}.domain-detail-header{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding:22px 24px;border-bottom:1px solid var(--color-border)}.domain-detail-header span{color:var(--color-text-muted);font-size:10px;font-weight:700}.domain-detail-header h2{margin:5px 0 4px;font-size:19px}.domain-detail-header p{max-width:720px;margin:0;color:var(--color-text-secondary);line-height:1.6}.domain-actions{display:flex;flex-shrink:0;gap:7px}.domain-warning{display:flex;gap:10px;margin:16px 20px 0;padding:11px 13px;color:var(--color-warning);background:var(--color-warning-soft);border:1px solid #e7ce9f;border-radius:var(--radius-md)}.domain-warning b,.domain-warning span{display:block}.domain-warning b{margin-bottom:2px;font-size:12px}.domain-warning span{color:#75521d;font-size:11px}.ai-behavior{padding:24px}.ai-behavior h3,.built-in-rules h3{margin:0 0 12px;font-size:14px}.behavior-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.behavior-grid article{padding:14px;background:var(--color-bg-editor);border:1px solid var(--color-border);border-radius:var(--radius-md)}.behavior-grid b{font-size:12px}.behavior-grid p{margin:6px 0 0;color:var(--color-text-secondary);font-size:11px;line-height:1.55}.built-in-rules{margin:0 24px 24px;padding-top:18px;border-top:1px solid var(--color-border)}.built-in-rules h3{margin-bottom:3px}.built-in-rules p{margin:0 0 12px;color:var(--color-text-muted);font-size:11px}.rule-tags{display:flex;flex-wrap:wrap;gap:6px}.modal-intro{margin:-4px 0 14px;color:var(--color-text-secondary);font-size:13px}.modal-template-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:18px}.modal-template-grid button{display:flex;align-items:center;justify-content:space-between;padding:10px;color:var(--color-text);background:var(--color-bg-surface);border:1px solid var(--color-border);border-radius:var(--radius-md);cursor:pointer}.modal-template-grid button.active{color:var(--color-primary);background:var(--color-primary-soft);border-color:var(--color-primary)}.modal-template-grid b{font-size:12px}.modal-template-grid span{color:var(--color-text-muted);font-size:9px}.prompt-hint{margin-top:-10px;padding:10px 12px;color:var(--color-text-secondary);background:var(--color-bg-editor);border-radius:var(--radius-md);font-size:11px;line-height:1.55}.prompt-hint b{margin-right:4px;color:var(--color-text)}
@media(max-width:760px){.scene-intro{align-items:flex-start;flex-direction:column}.scene-benefits{flex-basis:auto}.template-grid{grid-template-columns:1fr}.template-grid p{min-height:0}.domain-workspace{grid-template-columns:1fr}.domain-master{max-height:280px;overflow-y:auto;border-right:0;border-bottom:1px solid var(--color-border-strong)}.domain-detail-header{align-items:stretch;flex-direction:column;padding:18px 16px}.behavior-grid,.modal-template-grid{grid-template-columns:1fr}.ai-behavior{padding:18px 16px}.built-in-rules{margin-inline:16px}}
</style>
