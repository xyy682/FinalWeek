import { apiFetch } from './http'

export type MaterialType = 'COURSEWARE' | 'NOTES' | 'PAST_EXAM' | 'QUESTION_BANK' | 'RECORDING' | 'VIDEO' | 'OTHER'
export type MaterialStatus = 'PENDING_PUBLISH' | 'PUBLISH_FAILED' | 'QUEUED' | 'PROCESSING' | 'RETRYING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface Material {
  id: string
  courseId: string
  originalFilename: string
  sizeBytes: number
  mediaType: string
  materialType: MaterialType
  focusNotes: string | null
  status: MaterialStatus
  contentHash: string
  createdAt: string
  updatedAt: string
}

export interface UploadSession {
  uploadId: string
  chunkSize: number
  totalChunks: number
  expiresAt: string
}

export interface UploadStatus {
  uploadId: string
  status: 'UPLOADING' | 'COMPLETED'
  uploadedChunks: number[]
  materialId: string | null
  expiresAt: string | null
}

export const listMaterials = (courseId: string) => apiFetch<Material[]>(`/courses/${courseId}/materials`)
export const initializeUpload = (courseId: string, file: File, materialType: MaterialType, focusNotes: string) =>
  apiFetch<UploadSession>(`/courses/${courseId}/uploads/init`, {
    method: 'POST',
    body: JSON.stringify({ filename: file.name, fileSize: file.size, materialType, focusNotes: focusNotes || null }),
  })
export const getUploadStatus = (uploadId: string) => apiFetch<UploadStatus>(`/uploads/${uploadId}`)
export const putChunk = (uploadId: string, chunkIndex: number, data: Blob) =>
  apiFetch<void>(`/uploads/${uploadId}/chunks/${chunkIndex}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/octet-stream' }, body: data,
  })
export const completeUpload = (uploadId: string) =>
  apiFetch<{ material: Material; duplicate: boolean }>(`/uploads/${uploadId}/complete`, { method: 'POST' })
export const deleteMaterial = (materialId: string) => apiFetch<void>(`/materials/${materialId}`, { method: 'DELETE' })
