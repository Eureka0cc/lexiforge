import axios from 'axios'
import { message } from 'ant-design-vue'
import { readSseStream, type SseMessage } from './lib/sse'
import { clearStoredSession, saveStoredSession } from './lib/authStorage'
export type { SseMessage } from './lib/sse'

interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T
  fieldErrors: Record<string, string>
  requestId: string | null
}

export const api=axios.create({baseURL:'',withCredentials:true,timeout:45000})
api.interceptors.request.use(config=>{const token=localStorage.getItem('access_token');if(token)config.headers.Authorization=`Bearer ${token}`;return config})
let refreshPromise: Promise<void> | null = null
export function refreshSession() {
  if (!refreshPromise) {
    refreshPromise = api.post('/api/auth/refresh/v1').then(({ data }) => {
      saveStoredSession(data.accessToken, data.user)
      window.dispatchEvent(new Event('lexiforge:session-updated'))
    }).catch(error => {
      if (error.response?.status === 401) {
        clearStoredSession()
        location.href = '/login'
        throw error
      }
      // A timeout, offline connection or server error does not prove revocation.
      // Keep the mounted editor; the next explicit action may attempt renewal again.
      throw new Error(error.response?.data?.message || '会话续期暂时失败，请稍后重试')
    }).finally(() => { refreshPromise = null })
  }
  return refreshPromise
}
api.interceptors.response.use(response=>{
  const body=response.data as ApiResponse<unknown>
  if(body&&typeof body==='object'&&typeof body.success==='boolean'&&'data'in body)response.data=body.data
  return response
},async error=>{
  if (axios.isCancel(error)) return Promise.reject(error)
  const original=error.config
  if(error.response?.status===401&&!original?._retry&&!original?.url?.includes('/auth/')){
    original._retry=true
    await refreshSession()
    return api(original)
  }
  const text=!error.response
    ? '无法连接后端服务，请确认服务已启动后重试'
    : error.response.data?.message||'请求失败，请稍后重试'
  if(!original?._silent)message.error(text)
  return Promise.reject(error)
})

export async function postSse(
  url: string,
  body: unknown,
  signal: AbortSignal,
  onMessage: (message: SseMessage) => void,
) {
  const serialized = JSON.stringify(body)
  const send = async () => {
    try { return await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(localStorage.getItem('access_token') ? { Authorization: `Bearer ${localStorage.getItem('access_token')}` } : {}),
    },
    body: serialized,
    signal,
    }) } catch (error) {
      if (signal.aborted) throw error
      throw new Error('无法连接翻译服务，请检查网络后重试')
    }
  }
  let response = await send()
  // HTTP 401 表示尚未开始生成，只允许在此时续期并重发一次。
  if (response.status === 401) {
    await response.body?.cancel()
    await refreshSession()
    signal.throwIfAborted()
    response = await send()
  }
  if (!response.ok || !response.body) {
    let reason = `请求失败（${response.status}）`
    try {
      const error = await response.json()
      reason = error.message ?? reason
    } catch { /* response was not JSON */ }
    throw new Error(reason)
  }

  try { await readSseStream(response.body, onMessage) }
  catch (error) {
    if (signal.aborted) throw error
    if (error instanceof TypeError) throw new Error('翻译连接已中断，当前内容未完成，请重试')
    if (error instanceof SyntaxError) throw new Error('翻译响应格式不正确，请重试')
    throw error
  }
}
