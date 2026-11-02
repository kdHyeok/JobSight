import { request } from './http'
import { API } from '../routes'
import type {
  ExperienceDetail,
  ExperienceIndex,
  ExperienceLine,
  ExperiencePayload,
  ExperienceSaved,
  ImportDecision,
  ImportPlan,
  ImportResult,
  UnlinkedGroup,
} from '../types/experience'

export { ApiClientError } from './http'

/** 서버 상세 모드 상한(ExperienceService.MAX_DETAIL). 넘으면 400 이라 나눠 부른다. */
const DETAIL_CHUNK = 10
/** 목차 쪽수 상한. 한 쪽 60줄이라 1,200줄이면 개인 계정에 넉넉하다. */
const MAX_PAGES = 20

/** 목차 전체(경험 줄 + 미등록 묶음). 서버가 60줄씩 자르므로 complete 까지 이어 부른다. */
export async function listExperiences(): Promise<{ experiences: ExperienceLine[]; unlinked: UnlinkedGroup[]; index: ExperienceIndex }> {
  const experiences: ExperienceLine[] = []
  const unlinked: UnlinkedGroup[] = []
  let cursor: number | null = null
  let index: ExperienceIndex
  let pages = 0
  do {
    index = await request<ExperienceIndex>(cursor === null ? API.experiences : `${API.experiences}?cursor=${cursor}`)
    experiences.push(...(index.experiences as ExperienceLine[]))
    unlinked.push(...index.unlinkedRows)
    cursor = index.nextCursor
  } while (!index.complete && cursor !== null && ++pages < MAX_PAGES)
  return { experiences, unlinked, index }
}

/** 여러 경험의 상세(ids 상세 모드). 10개씩 나눠 같이 보낸다. */
export async function getExperiences(ids: string[]): Promise<ExperienceDetail[]> {
  const unique = [...new Set(ids)]
  const chunks: string[][] = []
  for (let i = 0; i < unique.length; i += DETAIL_CHUNK) chunks.push(unique.slice(i, i + DETAIL_CHUNK))
  const pages = await Promise.all(chunks.map((chunk) => {
    const params = new URLSearchParams()
    chunk.forEach((id) => params.append('ids', id))
    return request<ExperienceIndex>(`${API.experiences}?${params}`)
  }))
  return pages.flatMap((page) => page.experiences as ExperienceDetail[])
}

export const getExperience = (id: string) => request<ExperienceDetail>(API.experience(id))

export const createExperience = (payload: ExperiencePayload) =>
  request<ExperienceSaved>(API.experiences, { method: 'POST', body: JSON.stringify(payload) })

/** facts 를 보내면 expectedVersion 필수(다르면 409 EXPERIENCE_STALE). 빠진 칸은 서버가 유지한다. */
export const updateExperience = (id: string, payload: ExperiencePayload) =>
  request<ExperienceSaved>(API.experience(id), { method: 'PUT', body: JSON.stringify(payload) })

/** 웹 전용 검토 확정. 보고 있던 개정을 보낸다 — 그사이 개정이 올랐으면 409. */
export const reviewExperience = (id: string, revision: number) =>
  request<ExperienceDetail>(API.experienceReview(id), { method: 'POST', body: JSON.stringify({ revision }) })

/** 이력서가 쓰면 409 EXPERIENCE_IN_USE. 자소서 근거만 있으면 detach=true 로 끊고 지운다. */
export const deleteExperience = (id: string, detach = false) =>
  request<{ id: string; detachedSelfIntros: number }>(`${API.experience(id)}?detach=${detach}`, { method: 'DELETE' })

export const getImportPlan = () => request<ImportPlan>(API.experienceImportPlan)

/** 계획의 resumeVersions 그대로 보낸다. 그사이 이력서가 바뀌었으면 409 IMPORT_PLAN_STALE — 계획을 다시 만든다. */
export const applyImport = (resumeVersions: Record<string, number>, decisions: ImportDecision[]) =>
  request<ImportResult>(API.experienceImport, { method: 'POST', body: JSON.stringify({ resumeVersions, decisions }) })
