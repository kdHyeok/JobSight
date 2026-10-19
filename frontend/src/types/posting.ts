export type EmploymentType = 'FULL_TIME' | 'CONTRACT' | 'INTERN' | 'PART_TIME' | 'DISPATCH'

/** 내 참여 상태. 회사 절차(RecruitmentStep)와는 다른 축 — docs/job-postings.md. */
export type ApplicationStatus =
  | 'INTERESTED' | 'DRAFTING' | 'SUBMITTED' | 'WRITTEN_TEST_PREP' | 'INTERVIEW_PREP' | 'ACCEPTED'
  | 'DOCUMENT_REJECTED' | 'WRITTEN_TEST_REJECTED' | 'INTERVIEW_REJECTED' | 'CLOSED'

export type StepResult = 'UPCOMING' | 'PASSED'
export type PostingTab = 'interested' | 'progress' | 'archived'

/** 공고와 그 모집 직무가 표시될 상위 탭. 수동·자동 보관을 상태보다 우선한다. */
export function postingTabFor(status: ApplicationStatus | null, archived: boolean): PostingTab {
  if (archived) return 'archived'
  return status === 'INTERESTED' || status === 'DRAFTING' ? 'interested' : 'progress'
}

export interface RecruitmentStep {
  seq: number
  name: string
  result: StepResult
  scheduledAt: string | null
  memo: string | null
}

export interface StepPayload {
  name: string
  result: StepResult
  scheduledAt: string | null
  memo: string
}

/** 공고 응답에 실리는 직무 요약. 상세는 /api/positions/{id}. */
export interface PositionSummary {
  id: string
  name: string
  team: string | null
  headcount: string | null
  workLocation: string | null
}

export interface JobPosting {
  id: string
  companyId: string
  companyName: string | null
  /** 모집 부문. 공채 이름이거나 단일 직무 공고면 그 직무 이름. */
  title: string
  postingUrl: string | null
  employmentType: EmploymentType
  /** UTC ISO. null 이면 상시채용. */
  deadlineAt: string | null
  status: ApplicationStatus
  qualifications: string | null
  memo: string | null
  targetPositionId: string | null
  steps: RecruitmentStep[]
  positions: PositionSummary[]
  archived: boolean
  createdAt: string
  updatedAt: string
}

export interface JobPostingPayload {
  companyId: string | null
  /** companyId 가 없을 때만. 서버가 기존 기업을 찾거나 새로 만든다. */
  companyName: string
  title: string
  postingUrl: string
  employmentType: EmploymentType
  deadlineAt: string | null
  status: ApplicationStatus
  qualifications: string
  memo: string
  /** id 가 있으면 이름 갱신, 없으면 생성. 빠진 기존 직무는 삭제된다. 비우면 제목 이름의 직무 하나. */
  positions: Array<{ id: string | null; name: string }>
  steps: StepPayload[]
}

export const employmentTypeLabels: Record<EmploymentType, string> = {
  FULL_TIME: '정규직',
  CONTRACT: '계약직',
  INTERN: '인턴',
  PART_TIME: '파트타임',
  DISPATCH: '파견직',
}

export const statusLabels: Record<ApplicationStatus, string> = {
  INTERESTED: '관심',
  DRAFTING: '작성 중',
  SUBMITTED: '제출 완료',
  WRITTEN_TEST_PREP: '필기 준비',
  INTERVIEW_PREP: '면접 준비',
  ACCEPTED: '합격',
  DOCUMENT_REJECTED: '서류 탈락',
  WRITTEN_TEST_REJECTED: '필기 탈락',
  INTERVIEW_REJECTED: '면접 탈락',
  CLOSED: '종료',
}

export const statusTone: Record<ApplicationStatus, string> = {
  INTERESTED: 'neutral',
  DRAFTING: 'warning',
  SUBMITTED: 'primary',
  WRITTEN_TEST_PREP: 'warning',
  INTERVIEW_PREP: 'warning',
  ACCEPTED: 'positive',
  DOCUMENT_REJECTED: 'negative',
  WRITTEN_TEST_REJECTED: 'negative',
  INTERVIEW_REJECTED: 'negative',
  CLOSED: 'neutral',
}

/** CLOSED 는 기존 데이터 표시 전용이라 새 입력 선택지에는 넣지 않는다. */
export const selectableStatuses = (Object.keys(statusLabels) as ApplicationStatus[])
  .filter((status) => status !== 'CLOSED')

type KanbanColumn = { key: string; label: string; statuses: ApplicationStatus[]; dropStatus: ApplicationStatus }

export const interestedKanbanColumns: KanbanColumn[] = [
  { key: 'interested', label: '관심', statuses: ['INTERESTED'], dropStatus: 'INTERESTED' },
  { key: 'drafting', label: '작성 중', statuses: ['DRAFTING'], dropStatus: 'DRAFTING' },
]

export const progressKanbanColumns: KanbanColumn[] = [
  { key: 'submitted', label: '제출 완료', statuses: ['SUBMITTED'], dropStatus: 'SUBMITTED' },
  { key: 'written', label: '필기 준비', statuses: ['WRITTEN_TEST_PREP'], dropStatus: 'WRITTEN_TEST_PREP' },
  { key: 'interview', label: '면접 준비', statuses: ['INTERVIEW_PREP'], dropStatus: 'INTERVIEW_PREP' },
  { key: 'accepted', label: '합격', statuses: ['ACCEPTED'], dropStatus: 'ACCEPTED' },
]

export const archivedKanbanColumns: KanbanColumn[] = [
  { key: 'not-applied', label: '미지원', statuses: ['INTERESTED', 'DRAFTING'], dropStatus: 'INTERESTED' },
  { key: 'document-rejected', label: '서류 탈락', statuses: ['DOCUMENT_REJECTED'], dropStatus: 'DOCUMENT_REJECTED' },
  { key: 'written-rejected', label: '필기 탈락', statuses: ['WRITTEN_TEST_REJECTED'], dropStatus: 'WRITTEN_TEST_REJECTED' },
  { key: 'interview-rejected', label: '면접 탈락', statuses: ['INTERVIEW_REJECTED'], dropStatus: 'INTERVIEW_REJECTED' },
]

const columnsByTab: Record<PostingTab, KanbanColumn[]> = {
  interested: interestedKanbanColumns,
  progress: progressKanbanColumns,
  archived: archivedKanbanColumns,
}

export function groupPostingsByKanban(postings: JobPosting[], tab: PostingTab) {
  const definitions = columnsByTab[tab]
  const known = new Set(definitions.flatMap((column) => column.statuses))
  const columns = definitions.map((column) => ({
    ...column,
    postings: postings.filter((posting) => column.statuses.includes(posting.status)),
  }))
  const other = postings.filter((posting) => !known.has(posting.status))
  return tab === 'archived' && other.length
    ? [...columns, { key: 'other', label: '기타 보관', statuses: [], dropStatus: 'INTERESTED' as ApplicationStatus, postings: other }]
    : columns
}

export function postingTabCounts(open: JobPosting[], archived: JobPosting[]): Record<PostingTab, number> {
  const count = (tab: PostingTab) => groupPostingsByKanban(open, tab)
    .reduce((sum, column) => sum + column.postings.length, 0)
  return { interested: count('interested'), progress: count('progress'), archived: archived.length }
}

/** 드롭 한 번을 기존 status/archive API 호출로 옮길 최종 상태로 바꾼다. */
export function postingDropTarget(current: ApplicationStatus, tab: PostingTab, columnKey?: string) {
  const columnStatus = columnsByTab[tab].find((column) => column.key === columnKey)?.dropStatus
  if (tab === 'interested') return { status: columnStatus ?? 'INTERESTED' as ApplicationStatus, archived: false }
  if (tab === 'progress') return { status: columnStatus ?? 'SUBMITTED' as ApplicationStatus, archived: false }
  const rejected: Partial<Record<ApplicationStatus, ApplicationStatus>> = {
    SUBMITTED: 'DOCUMENT_REJECTED',
    WRITTEN_TEST_PREP: 'WRITTEN_TEST_REJECTED',
    INTERVIEW_PREP: 'INTERVIEW_REJECTED',
  }
  return { status: columnStatus ?? rejected[current] ?? current, archived: true }
}

export const stepResultLabels: Record<StepResult, string> = {
  UPCOMING: '예정',
  PASSED: '완료',
}

/** 절차 노드를 누르면 예정과 완료만 번갈아 바뀐다. */
const STEP_CYCLE: StepResult[] = ['UPCOMING', 'PASSED']
export function nextStepResult(current: StepResult): StepResult {
  return STEP_CYCLE[(STEP_CYCLE.indexOf(current) + 1) % STEP_CYCLE.length]
}

/**
 * 남은 일수. 서버가 계산해 내려주지 않는다 — 사용자 시계로 계산해야 자정을 넘길 때 어긋나지 않는다.
 * 날짜 경계 기준이라 "오늘 마감"은 0, 내일은 1이다.
 */
export function daysUntil(deadlineAt: string | null, now: Date = new Date()): number | null {
  if (!deadlineAt) return null
  const deadline = new Date(deadlineAt)
  const startOfDeadlineDay = new Date(deadline.getFullYear(), deadline.getMonth(), deadline.getDate())
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.round((startOfDeadlineDay.getTime() - startOfToday.getTime()) / 86_400_000)
}

export function ddayLabel(deadlineAt: string | null, now: Date = new Date()): string {
  const days = daysUntil(deadlineAt, now)
  if (days === null) return '상시'
  if (days === 0) return 'D-DAY'
  return days > 0 ? `D-${days}` : `마감 +${-days}`
}

/** 임박할수록 강한 색. */
export function ddayTone(
  deadlineAt: string | null,
  now: Date = new Date(),
): 'past' | 'urgent' | 'soon' | 'later' | 'open' {
  const days = daysUntil(deadlineAt, now)
  if (days === null) return 'open'
  if (days < 0) return 'past'
  if (days <= 3) return 'urgent'
  if (days <= 7) return 'soon'
  return 'later'
}

/** 2026-09-10T14:00:00Z -> 2026년 9월 10일 오후 11시 00분 (사용자 시간대) */
export function formatDeadline(deadlineAt: string | null): string {
  if (!deadlineAt) return '상시채용'
  const parts = formatDeadlineParts(deadlineAt)
  return `${parts.year} ${parts.emphasized}`
}

export function formatDeadlineParts(deadlineAt: string | null): { year: string; emphasized: string } {
  if (!deadlineAt) return { year: '', emphasized: '상시채용' }
  const date = new Date(deadlineAt)
  const pad = (value: number) => String(value).padStart(2, '0')
  const hour = date.getHours()
  return {
    year: `${date.getFullYear()}년`,
    emphasized: `${date.getMonth() + 1}월 ${date.getDate()}일 ${hour < 12 ? '오전' : '오후'} ${hour % 12 || 12}시 ${pad(date.getMinutes())}분`,
  }
}

/** datetime-local 입력값(시간대 없음)을 UTC ISO 로. 사용자의 로컬 시간대로 해석한다. */
export function toUtcIso(localValue: string): string | null {
  if (!localValue) return null
  return new Date(localValue).toISOString()
}

/** UTC ISO -> datetime-local 입력값. */
export function toLocalInput(deadlineAt: string | null): string {
  if (!deadlineAt) return ''
  const date = new Date(deadlineAt)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function nowLocalInput(): string {
  return toLocalInput(new Date().toISOString())
}
