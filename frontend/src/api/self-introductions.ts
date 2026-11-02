import { request } from './http'
import { API } from '../routes'
import type { SelfIntroduction, SelfIntroductionConfirmPayload, SelfIntroductionPayload } from '../types/self-introduction'

export { ApiClientError } from './http'

export const listSelfIntroductions = (q = '', resumeId = '') => {
  const params = new URLSearchParams()
  if (q.trim()) params.set('q', q.trim())
  if (resumeId) params.set('resumeId', resumeId)
  const suffix = params.size ? `?${params}` : ''
  return request<SelfIntroduction[]>(`${API.selfIntroductions}${suffix}`)
}

export const createSelfIntroduction = (payload: SelfIntroductionPayload) =>
  request<SelfIntroduction>(API.selfIntroductions, { method: 'POST', body: JSON.stringify(payload) })

export const updateSelfIntroduction = (id: string, payload: SelfIntroductionPayload) =>
  request<SelfIntroduction>(API.selfIntroduction(id), { method: 'PUT', body: JSON.stringify(payload) })

export const deleteSelfIntroduction = (id: string) =>
  request<void>(API.selfIntroduction(id), { method: 'DELETE' })

/** 웹 전용 확정(✓). MCP 가 남긴 AI 기록을 사용자 확정으로 바꾼다. */
export const confirmSelfIntroduction = (id: string, payload: SelfIntroductionConfirmPayload) =>
  request<SelfIntroduction>(API.selfIntroductionConfirm(id), { method: 'POST', body: JSON.stringify(payload) })
