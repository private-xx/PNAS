import axios, { type AxiosInstance } from 'axios'
import { useSessionStore } from '@/stores/session'

/** 统一 HTTP 客户端:同源 Cookie 会话 + 状态变更请求自动带 X-CSRF。 */
export const http: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
})

const SAFE_METHODS = ['get', 'head', 'options']

http.interceptors.request.use((config) => {
  const store = useSessionStore()
  const method = (config.method ?? 'get').toLowerCase()
  if (!SAFE_METHODS.includes(method) && store.csrf) {
    config.headers = config.headers ?? {}
    ;(config.headers as Record<string, string>)['X-CSRF'] = store.csrf
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.response?.status === 401) {
      const store = useSessionStore()
      store.clear()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  },
)

/** 从后端错误响应里提取可读消息。 */
export function errorMessage(error: unknown, fallback = '操作失败'): string {
  const anyError = error as { response?: { data?: { message?: string } }; message?: string }
  return anyError?.response?.data?.message ?? anyError?.message ?? fallback
}
