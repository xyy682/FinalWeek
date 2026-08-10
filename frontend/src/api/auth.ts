import { apiFetch, resetCsrf } from './http'

export interface UserAccount {
  id: string
  email: string
}

export interface CodeSentResponse {
  retryAfterSeconds: number
  message: string
}

export const sendLoginCode = (email: string) =>
  apiFetch<CodeSentResponse>('/auth/code', { method: 'POST', body: JSON.stringify({ email }) })

export const login = (email: string, code: string) =>
  apiFetch<UserAccount>('/auth/login', { method: 'POST', body: JSON.stringify({ email, code }) })

export const getMe = () => apiFetch<UserAccount>('/me')

export async function logout(): Promise<void> {
  await apiFetch<void>('/auth/logout', { method: 'POST' })
  resetCsrf()
}

