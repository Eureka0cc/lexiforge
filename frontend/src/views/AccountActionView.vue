<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const parameters = new URLSearchParams(location.hash.slice(1))
const token = parameters.get('token') ?? ''
const purpose = parameters.get('type') === 'verify' ? 'verify' : 'reset'
const form = reactive({ password: '', confirmation: '' })
const loading = ref(false)
const completed = ref(false)
const error = ref(token ? '' : '缺少验证链接，请重新申请')
// 一次性凭据只在页面内存中保存，立即从地址栏移除，不落到查询参数或历史。
history.replaceState(history.state, '', location.pathname + location.search)
async function confirm() {
  if (!token) { error.value = '缺少验证链接，请重新申请'; return }
  if (purpose === 'reset' && form.password !== form.confirmation) { error.value = '两次输入的密码不一致'; return }
  loading.value = true; error.value = ''
  try {
    await api.post(`/api/auth/${purpose === 'verify' ? 'email-verification' : 'password-reset'}/confirm/v1`,
      purpose === 'verify' ? { token } : { token, password: form.password })
    completed.value = true
    if (purpose === 'reset') auth.clear()
    else if (auth.loggedIn) await auth.reloadUser()
  } catch (failure) {
    error.value = (failure as { response?: { data?: { message?: string } } }).response?.data?.message ?? '操作未完成，请重试'
  } finally { loading.value = false }
}
</script>

<template>
  <main class="account-action"><section class="workspace-surface">
    <h1>{{ purpose === 'verify' ? '验证邮箱' : '设置新密码' }}</h1>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-result v-if="completed" status="success" :title="purpose === 'verify' ? '邮箱已验证' : '密码已更新，原有会话已失效'"><template #extra><router-link :to="purpose === 'verify' && auth.loggedIn ? '/account' : '/login'">{{ auth.loggedIn ? '返回账户' : '返回登录' }}</router-link></template></a-result>
    <a-form v-else :model="form" layout="vertical" @finish="confirm">
      <p>{{ purpose === 'verify' ? '请确认这是你的邮箱。验证链接只能使用一次。' : '新密码至少 8 个字符。更新后需要重新登录。' }}</p>
      <template v-if="purpose === 'reset'">
        <a-form-item label="新密码" name="password" :rules="[{ required: true, min: 8, max: 72, message: '请输入至少 8 个字符的新密码' }]"><a-input-password v-model:value="form.password" autocomplete="new-password" /></a-form-item>
        <a-form-item label="确认新密码" name="confirmation" :rules="[{ required: true, message: '请再次输入新密码' }]"><a-input-password v-model:value="form.confirmation" autocomplete="new-password" /></a-form-item>
      </template>
      <a-button type="primary" html-type="submit" :loading="loading" :disabled="!token">{{ purpose === 'verify' ? '确认邮箱' : '更新密码' }}</a-button>
    </a-form>
  </section></main>
</template>
<style scoped>.account-action{min-height:100vh;display:grid;place-items:center;padding:24px}.account-action>section{width:min(480px,100%);padding:28px}.account-action h1{font-size:24px}.account-action .ant-alert{margin-bottom:18px}</style>
