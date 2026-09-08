import axios from 'axios'
import { message } from 'ant-design-vue'

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
let refreshing=false
api.interceptors.response.use(response=>{
  const body=response.data as ApiResponse<unknown>
  if(body&&typeof body==='object'&&typeof body.success==='boolean'&&'data'in body)response.data=body.data
  return response
},async error=>{
  const original=error.config
  if(error.response?.status===401&&!original?._retry&&!original?.url?.includes('/auth/')){
    original._retry=true
    if(!refreshing){refreshing=true;try{const {data}=await api.post('/api/auth/refresh/v1');localStorage.setItem('access_token',data.accessToken);localStorage.setItem('user',JSON.stringify(data.user))}catch{localStorage.clear();location.href='/login'}finally{refreshing=false}}
    return api(original)
  }
  const text=!error.response
    ? '无法连接后端服务，请确认服务已启动后重试'
    : error.response.data?.message||'请求失败，请稍后重试'
  if(!original?._silent)message.error(text)
  return Promise.reject(error)
})

export interface SseMessage {
  event: string
  data: unknown
}

export async function postSse(
  url: string,
  body: unknown,
  signal: AbortSignal,
  onMessage: (message: SseMessage) => void,
) {
  const token = localStorage.getItem('access_token')
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal,
  })
  if (!response.ok || !response.body) {
    let reason = `请求失败（${response.status}）`
    try {
      const error = await response.json()
      reason = error.message ?? reason
    } catch { /* response was not JSON */ }
    throw new Error(reason)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      let event = 'message'
      const data: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
      }
      if (data.length) onMessage({ event, data: JSON.parse(data.join('\n')) })
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }
}
