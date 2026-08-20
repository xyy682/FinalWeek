import { apiFetch } from './http'
import type { TaskProgress } from './tasks'

export type KnowledgeVersionStatus = 'GENERATING' | 'PUBLISHED' | 'SUPERSEDED' | 'FAILED'
export interface KnowledgeVersionSummary {
  id: string; version: number; status: KnowledgeVersionStatus; outlineId: string | null
  createdAt: string; publishedAt: string | null; errorCode: string | null
}
export interface KnowledgeVersionState {
  current: KnowledgeVersionSummary | null
  hasUnconfirmedSuccessfulMaterials: boolean
  activeOutlineTask: TaskProgress | null
  recentFailedOutlineTask: TaskProgress | null
  planAndMockExamAvailable: boolean
}
export interface KnowledgeVersionConfirmation {
  version: KnowledgeVersionSummary; task: TaskProgress; ignoredMaterials: string[]
}
export const getKnowledgeVersion = (courseId: string) =>
  apiFetch<KnowledgeVersionState>(`/courses/${courseId}/knowledge-version`)
export const confirmKnowledgeVersion = (courseId: string, ignoreFailedMaterials: boolean) =>
  apiFetch<KnowledgeVersionConfirmation>(`/courses/${courseId}/knowledge-versions`, {
    method: 'POST', body: JSON.stringify({ ignoreFailedMaterials }),
  })
