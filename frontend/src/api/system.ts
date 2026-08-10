import { apiFetch } from './http'

export interface SystemStatus {
  status: 'ok'
  service: string
  time: string
}

export const getSystemStatus = () => apiFetch<SystemStatus>('/system/ping')

