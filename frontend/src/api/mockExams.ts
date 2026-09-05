import { apiFetch } from './http'
import type { TaskProgress } from './tasks'

export type QuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TRUE_FALSE' | 'FILL_BLANK' | 'SHORT_ANSWER' | 'CALCULATION' | 'ESSAY' | 'COMPREHENSIVE'
export type MockExamScope = 'WHOLE_COURSE' | 'OUTLINE_NODES'
export type ScoreMode = 'AUTO' | 'CUSTOM'
export interface MockExamInput {
  displayName?: string | null; scope: MockExamScope; outlineNodeIds: string[]
  questionCounts: Partial<Record<QuestionType, number>>; scoreMode: ScoreMode
  scorePerQuestion: Partial<Record<QuestionType, number>>; totalScore?: number | null
  durationMinutes?: number | null; allowGeneralKnowledge: boolean; instructions?: string | null
}
export interface MockExamSummary {
  id: string; taskId: string; retryOfId: string | null; displayName: string; status: TaskProgress['status']; currentStage: string | null
  questionCount: number; scoreSum: number; errorCode: string | null; errorMessage: string | null; warnings: string[]
  createdAt: string; completedAt: string | null
}
export interface MockExamPage { items: MockExamSummary[]; page: number; size: number; totalElements: number; totalPages: number }
export interface MockExamDetail { summary: MockExamSummary; request: MockExamInput & { questionCount: number; scoreSum: number }; knowledgeVersionId: string; taskId: string; filesReady: boolean }
export interface MockExamAccepted { mockExamId: string; taskId: string; task: TaskProgress; idempotentReplay: boolean }
export interface MockExamFile { url: string; filename: string; expiresAt: string }

export const createMockExam = (courseId: string, input: MockExamInput, key: string) => apiFetch<MockExamAccepted>(`/courses/${courseId}/mock-exams`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(input) })
export const listMockExams = (courseId: string, page = 0, size = 10) => apiFetch<MockExamPage>(`/courses/${courseId}/mock-exams?page=${page}&size=${size}`)
export const getMockExam = (id: string) => apiFetch<MockExamDetail>(`/mock-exams/${id}`)
export const retryMockExam = (id: string, input: MockExamInput, key: string) => apiFetch<MockExamAccepted>(`/mock-exams/${id}/retry`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(input) })
export const deleteMockExam = (id: string) => apiFetch<void>(`/mock-exams/${id}`, { method: 'DELETE' })
export const getMockExamFile = (id: string, kind: 'paper' | 'answer') => apiFetch<MockExamFile>(`/mock-exams/${id}/files/${kind}/preview`)
export const mockExamDownloadUrl = (id: string, kind: 'paper' | 'answer') => `/api/v1/mock-exams/${id}/files/${kind}/download`
