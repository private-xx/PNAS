import { http } from './http'

export interface SessionInfo {
  username: string
  displayName: string
  role: string
}

/** 登录成功后返回服务端下发的 CSRF 令牌(响应头 X-CSRF-Token)。 */
export async function login(username: string, password: string): Promise<string | undefined> {
  const response = await http.post('/auth/login', { username, password })
  return response.headers['x-csrf-token'] as string | undefined
}

export async function logout(): Promise<void> {
  await http.post('/auth/logout')
}

export async function fetchSession(): Promise<SessionInfo | null> {
  try {
    const response = await http.get<SessionInfo>('/auth/session')
    return response.data
  } catch {
    return null
  }
}
