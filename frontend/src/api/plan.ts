import { apiFetch } from './http'

export type MasteryLevel = 'LOW' | 'MEDIUM' | 'HIGH'
export interface PlanTask {
  id: string
  outlineNodeId: string
  knowledgeTitle: string
  plannedDate: string
  estimatedMinutes: number
  completed: boolean
  completedAt: string | null
}
export interface StudyPlan {
  id: string
  examDate: string
  dailyMinutes: number
  masteryLevel: MasteryLevel
  targetScore: number
  outlineGenerationVersion: number
  generatedAt: string
  version: number
  tasks: PlanTask[]
}
export interface PlanInput {
  examDate: string
  dailyMinutes: number
  masteryLevel: MasteryLevel
  targetScore: number
}

export async function getPlan(courseId: string): Promise<StudyPlan | null> {
  return (await apiFetch<{ plan: StudyPlan | null }>(`/courses/${courseId}/plan`)).plan
}
export async function generatePlan(courseId: string, input: PlanInput, key: string) {
  return apiFetch<{ plan: StudyPlan; idempotentReplay: boolean }>(`/courses/${courseId}/plan/generate`, {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(input),
  })
}
export async function setPlanTaskCompleted(taskId: string, completed: boolean) {
  return apiFetch<PlanTask>(`/plan-tasks/${taskId}/completed`, {
    method: 'PATCH', body: JSON.stringify({ completed }),
  })
}
