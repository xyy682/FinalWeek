import { apiFetch } from './http'
import type { MaterialStatus } from './materials'

export interface TaskProgress {
  id: string
  courseId: string
  materialId: string | null
  type: 'PARSE_MATERIAL' | 'GENERATE_OUTLINE'
  status: MaterialStatus
  currentStage: string | null
  publishAttemptCount: number
  deliveryAttemptCount: number
  apiAttemptCount: number
  manualRetryCount: number
  executionRound: number
  errorCode: string | null
  errorMessage: string | null
  updatedAt: string
}

export interface TaskEvent {
  eventId: string
  taskId: string
  status: MaterialStatus
  stage: string | null
  progress: number
  message: string | null
  occurredAt: string
}

export const getTask = (taskId: string) => apiFetch<TaskProgress>(`/tasks/${taskId}`)
export const cancelTask = (taskId: string) => apiFetch<TaskProgress>(`/tasks/${taskId}/cancel`, { method: 'POST' })
export const republishTask = (taskId: string) => apiFetch<TaskProgress>(`/tasks/${taskId}/republish`, { method: 'POST' })
export const retryMaterial = (materialId: string) => apiFetch<TaskProgress>(`/materials/${materialId}/retry`, { method: 'POST' })
