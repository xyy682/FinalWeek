import { apiFetch } from './http'

export interface Course {
  id: string
  name: string
  materialCount: number
  recentParseStatus: string | null
  createdAt: string
  updatedAt: string
}

export const listCourses = () => apiFetch<Course[]>('/courses')
export const getCourse = (courseId: string) => apiFetch<Course>(`/courses/${courseId}`)
export const createCourse = (name: string) =>
  apiFetch<Course>('/courses', { method: 'POST', body: JSON.stringify({ name }) })
export const renameCourse = (courseId: string, name: string) =>
  apiFetch<Course>(`/courses/${courseId}`, { method: 'PATCH', body: JSON.stringify({ name }) })
export const deleteCourse = (courseId: string) => apiFetch<void>(`/courses/${courseId}`, { method: 'DELETE' })

