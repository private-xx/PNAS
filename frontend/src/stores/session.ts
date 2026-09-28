import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'

export const useSessionStore = defineStore('session', {
  state: () => ({
    username: '',
    displayName: '',
    role: '',
    csrf: '',
  }),
  getters: {
    isLoggedIn: (state) => !!state.username,
    isAdmin: (state) => state.role === 'ADMIN',
  },
  actions: {
    async login(username: string, password: string) {
      const csrf = await authApi.login(username, password)
      this.csrf = csrf ?? ''
      const ok = await this.refresh()
      if (!ok) {
        throw new Error('登录后无法读取会话信息')
      }
    },
    async refresh(): Promise<boolean> {
      const info = await authApi.fetchSession()
      if (!info) {
        this.clear()
        return false
      }
      this.username = info.username
      this.displayName = info.displayName
      this.role = info.role
      return true
    },
    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.clear()
      }
    },
    clear() {
      this.username = ''
      this.displayName = ''
      this.role = ''
      this.csrf = ''
    },
  },
})
