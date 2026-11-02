import { request } from './http'
import { API } from '../routes'
import type { Submission, SubmissionPayload, WritingGuide } from '../types/submission'

export { ApiClientError } from './http'

/** 공고의 제출 기록, 최신순. */
export const listSubmissions = (postingId: string) => request<Submission[]>(API.postingSubmissions(postingId))

/** 실제로 낸 이력서·자소서를 골라 확인한다. 같은 내용이면 서버가 기존 기록을 돌려준다. */
export const confirmSubmission = (postingId: string, payload: SubmissionPayload) =>
  request<Submission>(API.postingSubmissions(postingId), { method: 'POST', body: JSON.stringify(payload) })

export const getWritingGuide = () => request<WritingGuide>(API.writingGuide)

/** expectedVersion 은 읽은 version(처음이면 0). 다른 곳에서 먼저 저장했으면 409 WRITING_GUIDE_STALE. */
export const saveWritingGuide = (body: string, expectedVersion: number) =>
  request<WritingGuide>(API.writingGuide, { method: 'PUT', body: JSON.stringify({ body, expectedVersion }) })
