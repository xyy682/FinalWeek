import { defineStore } from 'pinia'
import { getMe, login as loginRequest, logout as logoutRequest, type UserAccount } from '@/api/auth'
import { ApiError } from '@/api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as UserAccount | null,
    initialized: false,
  }),
  actions: {
    async load(): Promise<boolean> {
      if (this.initialized) return this.user !== null
      try {
        this.user = await getMe()
      } catch (error) {
        if (error instanceof ApiError && error.status === 503) throw error
        this.user = null
      } finally {
        this.initialized = true
      }
      return this.user !== null
    },
    async login(email: string, code: string): Promise<void> {
      this.user = await loginRequest(email, code)
      this.initialized = true
    },
    async logout(): Promise<void> {
      await logoutRequest()
      this.user = null
      this.initialized = true
    },
  },
})
