import type { Competency, LinkSource } from './self-introduction'
import type { Row, SectionKey } from './resume'

/**
 * 경험 원본(경험 은행). 백엔드 experience/dto 와 키가 1:1 이다 — docs/experiences.md.
 * 사실(facts)은 한 곳에 두고, 이력서 행·자소서는 (경험 id, 개정) 앵커로 가리킨다.
 */
export type ExperienceKind = 'PROJECT' | 'CAREER' | 'ACTIVITY' | 'TRAINING' | 'OTHER'

export const kindLabels: Record<ExperienceKind, string> = {
  PROJECT: '프로젝트',
  CAREER: '경력',
  ACTIVITY: '대내외활동',
  TRAINING: '교육',
  OTHER: '기타',
}

/** 경험 앵커를 갖는 이력서 섹션과 그 섹션에 연결할 수 있는 경험 종류(백엔드 ResumeExperienceLinker.SECTION_KINDS). */
export const SECTION_KINDS: Partial<Record<SectionKey, ExperienceKind>> = {
  projects: 'PROJECT',
  experiences: 'CAREER',
  activities: 'ACTIVITY',
  trainings: 'TRAINING',
}

/** 사실 항목. id 는 경험 안에서 유일한 안정 id 다 — 고친 항목은 같은 id, 새 항목은 id 없이 보낸다. */
export interface FactItem {
  id?: string
  text: string
  evidenceIds?: string[]
}

export interface ExperienceFacts {
  period?: string | null
  role?: string | null
  measurement?: { environment?: string | null; scope?: string | null } | null
  actions?: FactItem[] | null
  results?: FactItem[] | null
  /** 근거·미확인·충돌은 화면에서 고치지 않는다. 받은 그대로 돌려보낸다. */
  evidence?: unknown[] | null
  unverified?: FactItem[] | null
  conflicts?: FactItem[] | null
}

export interface ExperienceDossier {
  narrative?: string | null
  questionUses?: { competency: Competency; point: string }[] | null
  phrasings?: string[] | null
  cautions?: string[] | null
}

/** 목차 한 줄. reviewed:false 면 'AI 기록'. */
export interface ExperienceLine {
  id: string
  kind: ExperienceKind
  title: string
  period: string | null
  revision: number
  reviewed: boolean
  skills: string[]
  domains: string[]
  inResumes: { id: string; name: string }[]
  usedInSelfIntros: number
}

export interface ExperienceSource {
  resumeId: string
  resumeName: string
  section: string
  index: number
  resumeVersion: number
}

export interface UnlinkedGroup {
  kind: ExperienceKind
  title: string
  period: string | null
  sources: ExperienceSource[]
}

export interface ExperienceIndex {
  counts: { experiences: Partial<Record<ExperienceKind, number>>; aiRecords: number; unlinkedRows: number; groups: number }
  complete: boolean
  nextCursor: number | null
  importHint: string | null
  /** 목록 모드는 ExperienceLine, ids 상세 모드는 ExperienceDetail. */
  experiences: (ExperienceLine | ExperienceDetail)[]
  unlinkedRows: UnlinkedGroup[]
}

export interface ExperienceRevision {
  revision: number
  changeKind: 'INIT' | 'ADD' | 'CORRECT'
  source: LinkSource
  createdAt: string
}

export interface ExperienceDetail {
  id: string
  kind: ExperienceKind
  title: string
  startYm: string | null
  memo: string | null
  /** 다음 저장의 expectedVersion. */
  version: number
  revision: number
  reviewed: boolean
  reviewedRevision: number | null
  dossierBasedOn: number | null
  /** 소재집을 만든 개정이 지금 개정과 다름 — '소재집 갱신 필요'. */
  dossierStale: boolean
  skills: string[]
  domains: string[]
  facts: ExperienceFacts | null
  dossier: ExperienceDossier | null
  rows: { resumeId: string; resumeName: string; section: string; index: number; experienceRevision: number; needsReview: boolean }[]
  selfIntros: { id: string; question: string; experienceRevision: number; trust: string; needsReview: boolean }[]
  revisions: ExperienceRevision[]
}

/** 생성·수정 공용. 빠진 칸(undefined)은 서버가 유지한다. */
export interface ExperiencePayload {
  kind?: ExperienceKind
  title?: string
  startYm?: string | null
  memo?: string | null
  facts?: ExperienceFacts
  skills?: string[]
  domains?: string[]
  expectedVersion?: number
}

export interface ExperienceSaved {
  id: string
  version: number
  revision: number
  changeKind: 'INIT' | 'ADD' | 'CORRECT' | 'NONE'
  needsReview: { type: 'RESUME' | 'SELF_INTRO'; id: string; name: string; basedOnRevision: number }[]
  normalizedSkills: string[]
  warnings: string[]
}

export type ImportAction = 'CREATE' | 'SKIP' | 'LINK_TO'

export interface ImportGroup {
  key: string
  kind: ExperienceKind
  title: string
  period: string | null
  rows: ExperienceSource[]
  /** 같은 경험일 수 있는 다른 묶음 키. 자동으로 합치지 않는다. */
  mergeCandidates: string[]
}

export interface ImportPlan {
  groups: ImportGroup[]
  resumeVersions: Record<string, number>
}

export interface ImportDecision {
  key: string
  action: ImportAction
  experienceId?: string
}

export interface ImportResult {
  created: number
  linked: number
  skipped: number
  experienceIds: string[]
}

/**
 * 행이 참조한 개정 뒤에 정정(CORRECT) 개정이 있으면 '재검토 필요'(서버 ExperienceRevisions.needsReview 와 같은 규칙).
 * 개정 번호는 경험마다 seq 순서대로 오르므로 번호로 비교한다.
 */
export function needsReview(detail: Pick<ExperienceDetail, 'revisions'> | undefined, revision: unknown): boolean {
  if (!detail || typeof revision !== 'number') return false
  return detail.revisions.some((rev) => rev.revision > revision && rev.changeKind === 'CORRECT')
}

/** 행의 앵커 id. 화면에 칸이 없는 키라 문자열인지 여기서만 확인한다. */
export const anchorOf = (row: Row): string | null =>
  typeof row.experienceId === 'string' && row.experienceId ? row.experienceId : null

const str = (row: Row, key: string) => (typeof row[key] === 'string' ? (row[key] as string).trim() : '')
/** max 자를 넘으면 앞 max-1 자 + "…"(서버 ResumeService.fit 과 같은 규칙). */
const fit = (value: string, max: number) => (value.length <= max ? value : `${value.slice(0, max - 1)}…`)
const bullets = (items?: FactItem[] | null) => (items ?? []).map((item) => `- ${item.text}`).join('\n')

/** 행의 제목 칸. 경력은 기업명, 나머지는 이름이다. */
export const rowTitle = (section: SectionKey, row: Row) => str(row, section === 'experiences' ? 'company' : 'name')

/**
 * "경험에서 불러오기": 경험 원본의 사실로 만든 중립 행(서버 resume_add_experiences 의 FACTS 와 같은 모양).
 * 사실에 없는 것을 지어내지 않는다. 앵커는 experienceId 만 싣는다 — 개정은 저장할 때 서버가 지금 개정으로 정한다.
 */
export function rowFromExperience(section: SectionKey, detail: ExperienceDetail, empty: Row): Row {
  const facts = detail.facts ?? {}
  const [start, end] = (facts.period ?? '').split('~', 2).map((part) => part?.trim() ?? '')
  const actions = bullets(facts.actions)
  const results = bullets(facts.results)
  const joined = [facts.role ?? '', actions, results].filter((part) => part.trim()).join('\n')
  const row: Row = { ...empty, startYm: fit(detail.startYm ?? start ?? '', 200), endYm: fit(end ?? '', 200) }
  if (section === 'projects') {
    Object.assign(row, {
      name: fit(detail.title, 200), role: fit(facts.role ?? '', 500), techStack: fit(detail.skills.join(', '), 500),
      description: fit(actions, 5000), outcome: fit(results, 5000),
    })
  } else if (section === 'experiences') {
    Object.assign(row, { company: fit(detail.title, 200), description: fit(joined, 5000) })
  } else {
    Object.assign(row, { name: fit(detail.title, 200), description: fit(joined, 5000) })
  }
  row.experienceId = detail.id
  return row
}

/** 2,000자 사실 항목 상한에 맞춰 줄 단위로 나눈다. 빈 줄은 버리고 배열 50개에서 멈춘다. */
function factLines(value: string): FactItem[] {
  const out: FactItem[] = []
  for (const line of value.split('\n').map((part) => part.replace(/^\s*[-•]\s*/, '').trim()).filter(Boolean)) {
    for (let i = 0; i < line.length && out.length < 50; i += 2000) out.push({ text: line.slice(i, i + 2000) })
  }
  return out
}

/**
 * "경험으로 등록": 미연결 행으로 새 경험 원본 초안을 만든다. 행 문구가 곧 사용자가 쓴 사실이라 웹 생성(확정)으로 보낸다.
 * 프로젝트는 상세 내용을 한 일, 성과를 결과로, 그 밖에는 설명을 한 일로 나눈다.
 */
export function experienceFromRow(section: SectionKey, row: Row): ExperiencePayload | null {
  const kind = SECTION_KINDS[section]
  const title = rowTitle(section, row)
  if (!kind || !title) return null
  const start = str(row, 'startYm')
  const end = str(row, 'endYm')
  const period = start || end ? `${start}~${end}` : null
  const skills = section === 'projects'
    ? [...new Set(str(row, 'techStack').split(',').map((skill) => skill.trim().slice(0, 100)).filter(Boolean))].slice(0, 50)
    : []
  return {
    kind,
    title: fit(title, 200),
    startYm: start || null,
    facts: {
      period,
      role: section === 'projects' ? str(row, 'role') || null : null,
      actions: factLines(str(row, 'description')),
      results: section === 'projects' ? factLines(str(row, 'outcome')) : [],
    },
    skills,
  }
}
