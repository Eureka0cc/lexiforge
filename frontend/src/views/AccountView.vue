<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { useAuthStore } from '../stores/auth'
const auth = useAuthStore()
const loading = ref(false)
const sent = ref(false)
const error = ref('')
onMounted(() => auth.reloadUser().catch(() => { error.value = '账户信息加载失败，请重试' }))
async function requestVerification() {
  loading.value = true; error.value = ''
  try { await api.post('/api/auth/email-verification/request/v1', { email: auth.user?.email }); sent.value = true }
  catch (failure) { error.value = (failure as { response?: { data?: { message?: string } } }).response?.data?.message ?? '操作未完成' }
  finally { loading.value = false }
}
</script>
<template>
  <div class="page-header"><div><h1>账户与安全</h1><p>查看邮箱状态和管理账户访问。</p></div></div>
  <section class="workspace-surface account-settings">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-descriptions :column="1"><a-descriptions-item label="昵称">{{ auth.user?.displayName }}</a-descriptions-item><a-descriptions-item label="邮箱">{{ auth.user?.email }}</a-descriptions-item><a-descriptions-item label="邮箱状态">{{ auth.user?.emailVerified ? '已验证' : '尚未验证' }}</a-descriptions-item></a-descriptions>
    <a-alert v-if="sent" type="success" show-icon message="如果邮箱尚未验证，验证邮件已进入发送队列。" />
    <a-button v-if="!auth.user?.emailVerified" type="primary" :loading="loading" @click="requestVerification">申请验证邮件</a-button>
    <p>更改密码可通过登录页的“找回密码”完成。更新密码会使原有会话失效。</p>
  </section>
</template>
<style scoped>.account-settings{max-width:720px;padding:24px}.account-settings .ant-alert{margin-bottom:16px}.account-settings p{margin-top:24px;color:var(--color-text-secondary)}</style>
