import { request } from './http'
import { API } from '../routes'
import type {
  SelfIntroduction,
  SelfIntroductionConfirmPayload,
  SelfIntroductionPayload,
  SelfIntroductionUpdatePayload,
} from '../types/self-introduction'

export { ApiClientError } from './http'

// 같은 문항의 쓰기(수정·확정)를 줄 세운다. 이력서 저장(api/resumes.ts)과 같은 모양이다.
const pendingWrites = new Map<string, Promise<SelfIntroduction>>()

/**
 * 앞 쓰기가 진행 중이면 그 응답의 version 을 이어받아 보낸다. 앞 쓰기가 실패했으면 처음 값을 그대로 보내
 * 서버가 판단하게 한다(바뀌었으면 409) — docs/self-introductions.md 불변 조건 12.
 */
function queued(id: string, send: (version?: number) => Promise<SelfIntroduction>): Promise<SelfIntroduction> {
  const previous = pendingWrites.get(id)
  const pending = previous ? previous.then((saved) => send(saved.version), () => send()) : send()
  pendingWrites.set(id, pending)
  return pending.finally(() => {
    if (pendingWrites.get(id) === pending) pendingWrites.delete(id)
  })
}

export const listSelfIntroductions = (q = '', resumeId = '') => {
  const params = new URLSearchParams()
  if (q.trim()) params.set('q', q.trim())
  if (resumeId) params.set('resumeId', resumeId)
  const suffix = params.size ? `?${params}` : ''
  return request<SelfIntroduction[]>(`${API.selfIntroductions}${suffix}`)
}

export const createSelfIntroduction = (payload: SelfIntroductionPayload) =>
  request<SelfIntroduction>(API.selfIntroductions, { method: 'POST', body: JSON.stringify(payload) })

/** expectedVersion 은 읽어 온 문항의 version 이다. 다른 곳(AI 등)이 먼저 저장했으면 409 로 덮어쓰지 않는다. */
export const updateSelfIntroduction = (id: string, payload: SelfIntroductionUpdatePayload) =>
  queued(id, (expectedVersion = payload.expectedVersion) =>
    request<SelfIntroduction>(API.selfIntroduction(id), { method: 'PUT', body: JSON.stringify({ ...payload, expectedVersion }) }))

export const deleteSelfIntroduction = (id: string) =>
  request<void>(API.selfIntroduction(id), { method: 'DELETE' })

/** 웹 전용 확정(✓). MCP 가 남긴 AI 기록을 사용자 확정으로 바꾼다. version 이 오르므로 같은 줄에 세운다. */
export const confirmSelfIntroduction = (id: string, payload: SelfIntroductionConfirmPayload) =>
  queued(id, () => request<SelfIntroduction>(API.selfIntroductionConfirm(id), { method: 'POST', body: JSON.stringify(payload) }))
