<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CodeOutlined, LockOutlined, MailOutlined, UserOutlined } from '@ant-design/icons-vue'
import { useAuthStore } from '../stores/auth'
import { api } from '../api'

type AuthMode = 'login' | 'register'

const auth = useAuthStore()
const router = useRouter()
const mode = ref<AuthMode>('login')
const loading = ref(false)
const errorMessage = ref('')
const previewOpen = ref(false)
const form = reactive({ email: '', password: '', displayName: '' })
const recoveryOpen = ref(false)
const recoveryEmail = ref('')
const recoveryLoading = ref(false)
const recoverySent = ref(false)
const recoveryError = ref('')
async function requestRecovery() {
  recoveryLoading.value = true; recoveryError.value = ''; recoverySent.value = false
  try {
    await api.post('/api/auth/password-reset/request/v1', { email: recoveryEmail.value })
    recoverySent.value = true
  } catch (failure) {
    recoveryError.value = (failure as { response?: { data?: { message?: string } } }).response?.data?.message ?? '操作未完成，请重试'
  } finally { recoveryLoading.value = false }
}

function switchMode(nextMode: AuthMode) {
  mode.value = nextMode
  errorMessage.value = ''
}

async function submit() {
  loading.value = true
  errorMessage.value = ''
  try {
    if (mode.value === 'login') {
      await auth.login({ email: form.email, password: form.password })
    } else {
      await auth.register(form)
    }
    await router.push('/')
  } catch (error) {
    const requestError = error as { response?: { data?: { message?: string } } }
    errorMessage.value = requestError.response?.data?.message ?? '操作未完成，请检查输入后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-preview" aria-labelledby="preview-title">
      <div class="auth-brand brand">
        <div class="brand-mark" aria-hidden="true">Lx</div>
        <div class="brand-copy">
          <strong>LexiForge</strong>
          <small>技术文档阅读工作台</small>
        </div>
      </div>

      <div class="preview-intro">
        <span class="preview-kicker"><CodeOutlined /> 产品预览</span>
        <h2 id="preview-title">在真实语境中理解技术术语</h2>
        <p>翻译开发文档时保留代码结构、理解语境中的专业含义，并把值得复习的词加入个人词库。</p>
        <a-button class="mobile-preview-toggle" type="link" @click="previewOpen = !previewOpen">
          {{ previewOpen ? '收起批注示例' : '查看术语批注示例' }}
        </a-button>
      </div>

      <article class="document-preview" :class="{ 'is-open': previewOpen }" aria-label="技术文档术语批注示例">
        <header class="document-preview-header">
          <span class="mono">http-client.ts</span>
          <span class="language-code">TypeScript · EN → ZH</span>
        </header>
        <pre class="code-sample"><code><span class="line-number">12</span> const response = await client.retry({
<span class="line-number">13</span>   strategy: <span class="code-string">'exponential'</span>,
<span class="line-number">14</span>   <mark>backoff</mark>: 500,
<span class="line-number">15</span> })</code></pre>
        <aside class="term-note">
          <div class="term-note-title">
            <strong class="term-word">backoff</strong>
            <span class="mono">/ˈbækˌɔːf/ · noun</span>
          </div>
          <b>退避；重试前的等待策略</b>
          <p>请求失败后逐步增加等待时间，避免持续占用服务资源。</p>
        </aside>
      </article>
    </section>

    <section class="auth-panel" :aria-labelledby="mode === 'login' ? 'login-title' : 'register-title'">
      <div class="auth-box">
        <div class="auth-mode-switch" role="group" aria-label="登录或注册">
          <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
          <button type="button" :class="{ active: mode === 'register' }" @click="switchMode('register')">创建账户</button>
        </div>

        <h1 :id="mode === 'login' ? 'login-title' : 'register-title'">
          {{ mode === 'login' ? '登录 LexiForge' : '创建账户' }}
        </h1>
        <p class="auth-subtitle">阅读技术文档，积累真正用得上的专业词汇</p>

        <a-alert
          v-if="errorMessage"
          class="auth-error"
          type="error"
          show-icon
          :message="errorMessage"
          role="alert"
        />

        <a-form :model="form" layout="vertical" required-mark="optional" @finish="submit">
          <a-form-item
            v-if="mode === 'register'"
            label="昵称"
            name="displayName"
            :rules="[
              { required: true, message: '请输入昵称' },
              { min: 2, max: 80, message: '昵称长度应为 2～80 个字符' },
            ]"
          >
            <a-input v-model:value="form.displayName" size="large" autocomplete="name" placeholder="怎么称呼你">
              <template #prefix><UserOutlined /></template>
            </a-input>
          </a-form-item>

          <a-form-item
            label="邮箱"
            name="email"
            :rules="[
              { required: true, message: '请输入邮箱' },
              { type: 'email', message: '请输入有效的邮箱地址' },
            ]"
          >
            <a-input v-model:value="form.email" size="large" type="email" autocomplete="email" placeholder="you@example.com">
              <template #prefix><MailOutlined /></template>
            </a-input>
          </a-form-item>

          <a-form-item
            label="密码"
            name="password"
            :rules="[
              { required: true, message: '请输入密码' },
              { min: 8, max: 72, message: '密码长度应为 8～72 个字符' },
            ]"
          >
            <a-input-password v-model:value="form.password" size="large" autocomplete="current-password" placeholder="至少 8 位">
              <template #prefix><LockOutlined /></template>
            </a-input-password>
          </a-form-item>

          <a-button class="auth-submit" html-type="submit" type="primary" size="large" block :loading="loading">
            {{ mode === 'login' ? '登录' : '创建账户' }}
          </a-button>
        </a-form>
        <a-button v-if="mode === 'login'" type="link" @click="recoveryEmail = form.email; recoverySent = false; recoveryError = ''; recoveryOpen = true">找回密码</a-button>

        <p class="auth-switch-copy">
          {{ mode === 'login' ? '还没有账户？' : '已经有账户？' }}
          <a-button type="link" @click="switchMode(mode === 'login' ? 'register' : 'login')">
            {{ mode === 'login' ? '创建账户' : '返回登录' }}
          </a-button>
        </p>
      </div>
    </section>
    <a-modal v-model:open="recoveryOpen" title="找回密码" :footer="null">
      <a-alert v-if="recoveryError" type="error" show-icon :message="recoveryError" />
      <a-alert v-if="recoverySent" type="success" show-icon message="如果邮箱已注册，重置邮件已进入发送队列。请查看邮件中的链接。" />
      <a-form :model="{ email: recoveryEmail }" layout="vertical" @finish="requestRecovery">
        <a-form-item label="账户邮箱" name="email" :rules="[{ required: true, type: 'email', message: '请输入注册时使用的邮箱' }]"><a-input v-model:value="recoveryEmail" type="email" autocomplete="email" /></a-form-item>
        <a-button type="primary" html-type="submit" :loading="recoveryLoading">申请重置邮件</a-button>
      </a-form>
    </a-modal>
  </main>
</template>

<style scoped>
.auth-page { min-height: 100vh; display: grid; grid-template-columns: minmax(480px, 1.05fr) minmax(420px, .95fr); background: var(--color-bg-page); }
.auth-preview { display: flex; min-height: 100vh; flex-direction: column; padding: 28px clamp(32px, 6vw, 88px) 56px; color: #e8ecf5; background: #20283a; border-right: 1px solid #111827; }
.auth-brand { display: flex; align-items: center; gap: 10px; color: #fff; }
.auth-brand .brand-copy { min-width: 0; }
.auth-brand .brand-copy strong, .auth-brand .brand-copy small { display: block; }
.auth-brand .brand-copy strong { font-size: 15px; }
.auth-brand .brand-copy small { margin-top: 1px; color: #9fa9bc; font-size: 11px; }
.preview-intro { max-width: 620px; margin-top: auto; }
.preview-kicker { display: inline-flex; align-items: center; gap: 7px; color: #b9c1d1; font-size: 13px; font-weight: 600; letter-spacing: .03em; }
.preview-intro h2 { max-width: 540px; margin: 16px 0 10px; color: #f4f6fa; font-size: clamp(24px, 3vw, 34px); line-height: 1.35; }
.preview-intro p { max-width: 590px; margin: 0; color: #b9c1d1; line-height: 1.8; }
.mobile-preview-toggle { display: none; padding-left: 0; }
.document-preview { max-width: 680px; margin-top: 28px; overflow: hidden; background: #141a27; border: 1px solid #39445a; border-radius: var(--radius-lg); }
.document-preview-header { display: flex; justify-content: space-between; gap: 12px; padding: 10px 14px; color: #aeb7c8; background: #192132; border-bottom: 1px solid #39445a; font-size: 12px; }
.code-sample { margin: 0; padding: 18px 16px 15px; overflow-x: auto; color: #d8deeb; font-family: var(--font-mono); font-size: 13px; line-height: 1.9; }
.line-number { display: inline-block; width: 28px; color: #657088; user-select: none; }
.code-string { color: #a9d1a9; }
.code-sample mark { padding: 1px 2px; color: #eef0ff; background: #3b477d; border-bottom: 1px solid #9ba7eb; }
.term-note { padding: 14px 16px; color: #dce1eb; background: #1b2435; border-top: 1px solid #39445a; }
.term-note-title { display: flex; align-items: baseline; gap: 10px; margin-bottom: 7px; }
.term-note-title strong { color: #fff; font-size: 16px; }
.term-note-title span { color: #99a4b8; font-size: 12px; }
.term-note p { margin: 5px 0 0; color: #aeb7c8; font-size: 13px; line-height: 1.65; }
.auth-panel { display: grid; min-height: 100vh; place-items: center; padding: 40px clamp(24px, 5vw, 72px); background: var(--color-bg-surface); }
.auth-box { width: min(400px, 100%); }
.auth-mode-switch { display: grid; grid-template-columns: 1fr 1fr; margin-bottom: 30px; padding: 3px; background: var(--color-bg-subtle); border: 1px solid var(--color-border); border-radius: var(--radius-lg); }
.auth-mode-switch button { height: 34px; color: var(--color-text-secondary); background: transparent; border: 0; border-radius: var(--radius-md); cursor: pointer; }
.auth-mode-switch button.active { color: var(--color-text); background: var(--color-bg-surface); border: 1px solid var(--color-border); font-weight: 600; }
.auth-box h1 { margin: 0 0 7px; font-size: 26px; line-height: 1.35; }
.auth-subtitle { margin: 0 0 28px; color: var(--color-text-secondary); }
.auth-error { margin-bottom: 18px; }
.auth-submit { margin-top: 4px; }
.auth-switch-copy { margin: 20px 0 0; color: var(--color-text-secondary); text-align: center; }
.auth-switch-copy .ant-btn { padding-inline: 4px; }

@media (max-width: 880px) {
  .auth-page { grid-template-columns: 1fr; }
  .auth-preview { min-height: auto; padding: 20px 20px 0; background: var(--color-bg-surface); border: 0; }
  .auth-brand { color: var(--color-text); }
  .auth-brand :deep(.brand-copy small) { color: var(--color-text-secondary); }
  .preview-intro { margin-top: 24px; }
  .preview-kicker { color: var(--color-text-secondary); }
  .preview-intro h2 { margin: 10px 0 5px; color: var(--color-text); font-size: 18px; }
  .preview-intro p { color: var(--color-text-secondary); font-size: 13px; line-height: 1.6; }
  .mobile-preview-toggle { display: inline-flex; }
  .document-preview { display: none; margin-top: 8px; }
  .document-preview.is-open { display: block; }
  .auth-panel { min-height: auto; padding: 28px 20px 40px; place-items: start center; }
  .auth-mode-switch { margin-bottom: 24px; }
}
</style>
