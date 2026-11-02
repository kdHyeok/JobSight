import { request } from './http'
import { API } from '../routes'
import type { Resume, ResumePayload, ResumeSummary } from '../types/resume'

export { ApiClientError } from './http'

// 화면을 벗어났다가 돌아와도 저장 전 문서를 다시 읽지 않게 한다.
const pendingUpdates = new Map<string, Promise<Resume>>()

/** 목록엔 content 가 없다. 카드는 이름·수정일만 그린다. */
export const listResumes = () => request<ResumeSummary[]>(API.resumes)

export async function getResume(id: string): Promise<Resume> {
  const pending = pendingUpdates.get(id)
  if (pending) await pending.catch(() => undefined)
  return request<Resume>(API.resume(id))
}

/** content 를 비우면 서버가 빈 문서로 만든다. */
export const createResume = (name: string) =>
  request<Resume>(API.resumes, { method: 'POST', body: JSON.stringify({ name, content: null, positionIds: [] }) })

/** 문서 통째 교체. 행 단위 API 는 없다. */
export async function updateResume(id: string, payload: ResumePayload): Promise<Resume> {
  const send = (expectedVersion = payload.expectedVersion) =>
    request<Resume>(API.resume(id), { method: 'PUT', body: JSON.stringify({ ...payload, expectedVersion }) })
  const previous = pendingUpdates.get(id)
  // 같은 화면의 연속 저장은 앞 저장이 올린 version 을 이어받는다. 편집기 상태가 누적이라 뒤 저장이 앞 저장을 포함한다.
  // 앞 저장이 실패했으면 처음 값을 그대로 보내 서버가 판단하게 한다(바뀌었으면 409).
  const pending = previous ? previous.then((saved) => send(saved.version), () => send()) : send()
  pendingUpdates.set(id, pending)
  try {
    return await pending
  } finally {
    if (pendingUpdates.get(id) === pending) pendingUpdates.delete(id)
  }
}

export const copyResume = (id: string, name: string) =>
  request<Resume>(API.resumeCopy(id), { method: 'POST', body: JSON.stringify({ name }) })

export const deleteResume = (id: string) =>
  request<void>(API.resume(id), { method: 'DELETE' })
