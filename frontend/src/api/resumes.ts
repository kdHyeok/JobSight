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
  const body = JSON.stringify(payload)
  const send = () => request<Resume>(API.resume(id), { method: 'PUT', body })
  const previous = pendingUpdates.get(id)
  const pending = previous ? previous.catch(() => undefined).then(send) : send()
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
