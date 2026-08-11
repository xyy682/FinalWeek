import { apiFetch } from './http'

export type ChatRole = 'USER' | 'ASSISTANT'
export type ChatStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED'
export interface ChatSource {
  segmentId: string; sourceType: string; pageNumber: number | null; slideNumber: number | null
  paragraphNumber: number | null; startTimeMs: number | null; endTimeMs: number | null
}
export interface ChatMessage {
  id: string; role: ChatRole; content: string; sources: ChatSource[]; generalKnowledgeUsed: boolean
  generalKnowledgeContent: string | null; status: ChatStatus; replyToId: string | null
  errorCode: string | null; createdAt: string
}
export interface MessagePage { messages: ChatMessage[]; nextCursor: string | null }
export interface ChatExchange { question: ChatMessage; answer: ChatMessage; idempotentReplay: boolean }

export const getMessages = (courseId: string, cursor?: string | null) =>
  apiFetch<MessagePage>(`/courses/${courseId}/messages${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`)
export const askQuestion = (courseId: string, question: string, signal?: AbortSignal) =>
  apiFetch<ChatExchange>(`/courses/${courseId}/messages`, {
    method: 'POST', body: JSON.stringify({ question }), signal,
  })
export const retryQuestion = (messageId: string, signal?: AbortSignal) =>
  apiFetch<ChatExchange>(`/chat-messages/${messageId}/retry`, { method: 'POST', signal })
