import axios, { type AxiosInstance, type AxiosHeaders } from 'axios'
import { useSessionStore } from '@/stores/session'

/** 统一 HTTP 客户端:同源 Cookie 会话 + 状态变更请求自动带 X-CSRF。 */
export const http: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
})

const SAFE_METHODS = ['get', 'head', 'options']

/** 读取双提交 Cookie(刷新后 CSRF 仍可用,无需重新登录)。 */
export function readCookie(name: string): string {
  const hit = document.cookie.split('; ').find((c) => c.startsWith(name + '='))
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : ''
}

http.interceptors.request.use((config) => {
  const store = useSessionStore()
  const method = (config.method ?? 'get').toLowerCase()
  const csrf = store.csrf || readCookie('PNAS_CSRF')
  if (!SAFE_METHODS.includes(method) && csrf) {
    if (config.headers && typeof (config.headers as AxiosHeaders).set === 'function') {
      ;(config.headers as AxiosHeaders).set('X-CSRF', csrf)
    } else {
      config.headers = { ...(config.headers ?? {}), 'X-CSRF': csrf } as never
    }
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
