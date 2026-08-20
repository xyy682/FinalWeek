import { apiFetch } from './http'
import type { MaterialStatus } from './materials'

export interface TaskProgress {
  id: string
  courseId: string
  courseName: string | null
  materialId: string | null
  type: 'PARSE_MATERIAL' | 'GENERATE_OUTLINE' | 'GENERATE_PLAN' | 'GENERATE_MOCK_EXAM' | 'ANSWER_CHAT'
  status: MaterialStatus
  currentStage: string | null
  publishAttemptCount: number
  deliveryAttemptCount: number
  apiAttemptCount: number
  manualRetryCount: number
  executionRound: number
  errorCode: string | null
  errorMessage: string | null
  jumpTarget: string
  visibleInGlobalDrawer: boolean
  updatedAt: string
}

export interface TaskEvent {
  eventId: string
  taskId: string
  courseId: string
  type: TaskProgress['type']
  status: MaterialStatus
  stage: string | null
  progress: number
  message: string | null
  jumpTarget: string
  visibleInGlobalDrawer: boolean
  occurredAt: string
}

export const getTask = (taskId: string) => apiFetch<TaskProgress>(`/tasks/${taskId}`)
export const cancelTask = (taskId: string) => apiFetch<TaskProgress>(`/tasks/${taskId}/cancel`, { method: 'POST' })
export const republishTask = (taskId: string) => apiFetch<TaskProgress>(`/tasks/${taskId}/republish`, { method: 'POST' })
export const retryTask = (taskId: string) => apiFetch<TaskProgress>(`/tasks/${taskId}/retry`, { method: 'POST' })
export const listActiveTasks = () => apiFetch<TaskProgress[]>('/tasks/active')
export const retryMaterial = (materialId: string) => apiFetch<TaskProgress>(`/materials/${materialId}/retry`, { method: 'POST' })
