import { apiFetch } from './http'
import type { TaskProgress } from './tasks'

export type OutlineImportance = 'HIGH' | 'MEDIUM' | 'LOW'
export interface OutlineSource {
  segmentId: string; sourceType: string; pageNumber: number | null; slideNumber: number | null
  paragraphNumber: number | null; startTimeMs: number | null; endTimeMs: number | null
}
export interface OutlineNode {
  id: string; title: string; importance: OutlineImportance; importanceManuallyAdjusted: boolean
  sources: OutlineSource[]; children: OutlineNode[]
}
export interface Outline {
  id: string; generationVersion: number; generatedAt: string; updatedAt: string; nodes: OutlineNode[]
}
export interface OutlinePage { outline: Outline | null; activeTask: TaskProgress | null }
export const getOutline = (courseId: string) => apiFetch<OutlinePage>(`/courses/${courseId}/outline`)
export const generateOutline = (courseId: string) => apiFetch<{ task: TaskProgress; existing: boolean }>(
  `/courses/${courseId}/outline/generate`, { method: 'POST' },
)
export const updateOutlineImportance = (nodeId: string, importance: OutlineImportance) =>
  apiFetch<OutlineNode>(`/outline-nodes/${nodeId}/importance`, {
    method: 'PATCH', body: JSON.stringify({ importance }),
  })
