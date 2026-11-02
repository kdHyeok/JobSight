import type { AttachmentMeta } from './attachment'

/**
 * 이력서 문서의 모양. 백엔드 ResumeContent 와 키가 1:1 이어야 한다 —
 * Jackson 은 모르는 키를 조용히 버리므로 오타가 나면 저장은 되는데 값이 사라진다.
 * 연월은 사용자가 쓴 표현("2024.03", "현재")을 그대로 두는 문자열이다.
 */
export interface BasicInfo {
  name: string
  phone: string
  birthDate: string
  email: string
  address: string
  portfolioUrl: string
  portfolioFileId: string
  githubUrl: string
}

/** 행 안의 표 한 줄(학년별 이수 내역 등). 값은 전부 문자열이다. */
export type TermRow = Record<string, string>

/** 섹션 행의 값 — 보통은 문자열, 중첩 표(kind: 'terms')만 배열이다. */
export type RowValue = string | TermRow[]

/** 섹션 행. 키는 SECTIONS 의 field.key. */
export type Row = Record<string, RowValue>

/** 행에서 문자열 필드를 꺼낸다. 중첩 표가 섞여 있어 캐스팅을 한 곳에 모은다. */
export const text = (row: Row, key: string): string =>
  typeof row[key] === 'string' ? (row[key] as string) : ''

export const terms = (row: Row, key: string): TermRow[] =>
  Array.isArray(row[key]) ? (row[key] as TermRow[]) : []

export type SectionKey =
  | 'educations'
  | 'trainings'
  | 'activities'
  | 'experiences'
  | 'awards'
  | 'certificates'
  | 'skills'
  | 'projects'

export type ResumeContent = { basic: BasicInfo } & Record<SectionKey, Row[]>

export interface ResumeSummary {
  id: string
  name: string
  createdAt: string
  updatedAt: string
  /** 연결 직무. 자소서 편집기의 "어느 지원용?" 후보 칩에 쓴다. */
  positionIds?: string[]
}

export interface Resume extends ResumeSummary {
  content: ResumeContent
  positionIds: string[]
  /** 저장할 때마다 오른다. 저장 요청의 expectedVersion 으로 돌려보낸다 — docs/resumes.md 불변 조건 8. */
  version: number
  /** content 가 가리키는 내 첨부의 메타(응답 전용). 빠진 id 는 첨부 칸이 직접 부른다 — docs/attachments.md. */
  attachments?: Record<string, AttachmentMeta>
}

export interface ResumePayload {
  name: string
  content: ResumeContent
  positionIds: string[]
  /** 문서 저장(PUT)에 필수다. 생성에는 쓰지 않는다. 다르면 409 RESUME_STALE. */
  expectedVersion?: number
}

export type FieldKind = 'text' | 'ym' | 'url' | 'textarea' | 'file' | 'select' | 'terms'

/** 중첩 표의 열. wide 열은 여러 줄 입력이다(과목 목록처럼 길다). */
export interface ColumnConfig {
  key: string
  label: string
  wide?: boolean
  placeholder?: string
}

export interface FieldConfig {
  key: string
  label: string
  kind: FieldKind
  placeholder?: string
  /** select 의 선택지. 빈 값("선택 안 함")은 컴포넌트가 붙인다. */
  options?: string[]
  /** terms 의 열 구성. */
  columns?: ColumnConfig[]
  /** 같은 행의 이 키가 아래 값 중 하나일 때만 보인다 — 고등학교/대학교로 갈리는 필드들. */
  when?: { key: string; equals: string[] }
}

export interface SectionConfig {
  key: SectionKey
  label: string
  fields: FieldConfig[]
}

/** 기본정보는 행이 아니라 필드 묶음 하나다. */
export const BASIC_FIELDS: FieldConfig[] = [
  { key: 'name', label: '성명', kind: 'text' },
  { key: 'phone', label: '휴대전화', kind: 'text', placeholder: '010-0000-0000' },
  { key: 'birthDate', label: '생년월일', kind: 'text', placeholder: 'YYYYMMDD' },
  { key: 'email', label: '이메일', kind: 'text' },
  { key: 'address', label: '주소', kind: 'url' },
  { key: 'portfolioUrl', label: '포트폴리오', kind: 'url', placeholder: 'https://' },
  { key: 'portfolioFileId', label: '포트폴리오 파일', kind: 'file' },
  { key: 'githubUrl', label: 'GitHub', kind: 'url', placeholder: 'https://github.com/' },
]

const YM = '예: 2024.03 / 현재'

/** 학교 구분. 이 값으로 학력 행의 학업 이수 입력이 갈린다. */
export const SCHOOL_TYPES = ['대학교', '고등학교', '기타'] as const
const COLLEGE = { key: 'schoolType', equals: ['대학교'] }
const HIGH_SCHOOL = { key: 'schoolType', equals: ['고등학교'] }

/** 대학교: 학년 하나에 이수 과목 목록·총 이수학점·평균 학점. */
const COLLEGE_TERM_COLUMNS: ColumnConfig[] = [
  { key: 'grade', label: '학년', placeholder: '1학년' },
  { key: 'courses', label: '이수과목', wide: true, placeholder: '한 줄에 하나씩\n일반화학(A+)\n대학수학I(A+)' },
  { key: 'credits', label: '총 이수학점', placeholder: '24.00' },
  { key: 'gpa', label: '평균 학점', placeholder: '4.38' },
]

/** 고등학교: 생활기록부 그대로 학기·과목 단위. */
const SCHOOL_TERM_COLUMNS: ColumnConfig[] = [
  { key: 'grade', label: '학년', placeholder: '1' },
  { key: 'term', label: '학기', placeholder: '1' },
  { key: 'subject', label: '과목', placeholder: '국어1' },
  { key: 'units', label: '단위', placeholder: '2' },
  { key: 'achievement', label: '성취도', placeholder: 'C' },
  { key: 'rank', label: '석차등급', placeholder: '2' },
  { key: 'students', label: '이수자 수', placeholder: '59' },
]

/** 섹션 순서와 필드 — 사용자가 쓰던 이력서 양식 그대로. 섹션을 더하면 백엔드 ResumeContent 도 함께. */
export const SECTIONS: SectionConfig[] = [
  {
    key: 'educations', label: '학력사항',
    fields: [
      { key: 'school', label: '학교명', kind: 'text' },
      { key: 'schoolType', label: '학교 구분', kind: 'select', options: [...SCHOOL_TYPES] },
      { key: 'major', label: '학과 / 전공', kind: 'text' },
      { key: 'startYm', label: '입학년월', kind: 'ym', placeholder: YM },
      { key: 'endYm', label: '졸업년월', kind: 'ym', placeholder: YM },
      { key: 'gpa', label: '평균 학점', kind: 'text', placeholder: '예: 3.96 / 4.50' },
      { key: 'totalCredits', label: '총 이수학점', kind: 'text', placeholder: '예: 133.00', when: COLLEGE },
      { key: 'admissionExam', label: '대입검정고시 유무', kind: 'text', placeholder: '예 / 아니오', when: HIGH_SCHOOL },
      { key: 'overallRank', label: '내신등급', kind: 'text', placeholder: '예: 2.6 등급(9등급)', when: HIGH_SCHOOL },
      { key: 'diplomaId', label: '졸업증', kind: 'file' },
      { key: 'transcriptId', label: '성적증명서', kind: 'file' },
      { key: 'collegeTerms', label: '학년별 전공 이수', kind: 'terms', columns: COLLEGE_TERM_COLUMNS, when: COLLEGE },
      { key: 'schoolTerms', label: '학기별 이수 과목', kind: 'terms', columns: SCHOOL_TERM_COLUMNS, when: HIGH_SCHOOL },
    ],
  },
  {
    key: 'trainings', label: '교육이수',
    fields: [
      { key: 'name', label: '교육명', kind: 'text' },
      { key: 'institution', label: '교육기관', kind: 'text' },
      { key: 'startYm', label: '시작년월', kind: 'ym', placeholder: YM },
      { key: 'endYm', label: '종료년월', kind: 'ym', placeholder: YM },
      { key: 'description', label: '교육내용', kind: 'textarea' },
      { key: 'fileId', label: '이수증', kind: 'file' },
    ],
  },
  {
    key: 'activities', label: '대내외활동',
    fields: [
      { key: 'name', label: '활동명', kind: 'text' },
      { key: 'organizer', label: '주관기관', kind: 'text' },
      { key: 'startYm', label: '시작년월', kind: 'ym', placeholder: YM },
      { key: 'endYm', label: '종료년월', kind: 'ym', placeholder: YM },
      { key: 'description', label: '활동내용', kind: 'textarea' },
      { key: 'fileId', label: '활동 첨부', kind: 'file' },
    ],
  },
  {
    key: 'experiences', label: '경력사항 (인턴십 등)',
    fields: [
      { key: 'company', label: '기업명', kind: 'text' },
      { key: 'startYm', label: '입사년월', kind: 'ym', placeholder: YM },
      { key: 'endYm', label: '퇴사년월', kind: 'ym', placeholder: YM },
      { key: 'description', label: '직무내용', kind: 'textarea' },
    ],
  },
  {
    key: 'awards', label: '수상내역',
    fields: [
      { key: 'name', label: '수상명', kind: 'text' },
      { key: 'issuer', label: '수여 기관', kind: 'text' },
      { key: 'awardedYm', label: '수상 일자', kind: 'ym', placeholder: YM },
      { key: 'fileId', label: '수상 증빙', kind: 'file' },
    ],
  },
  {
    key: 'certificates', label: '자격증',
    fields: [
      { key: 'name', label: '자격명', kind: 'text' },
      { key: 'issuer', label: '주관 기관', kind: 'text' },
      { key: 'acquiredYm', label: '취득 일자', kind: 'ym', placeholder: YM },
      { key: 'licenseNo', label: '발급번호', kind: 'text', placeholder: '자격증에 적힌 번호' },
      { key: 'fileId', label: '자격증 사본', kind: 'file' },
    ],
  },
  {
    key: 'skills', label: 'SW 역량 (보유기술 및 프로그래밍언어)',
    fields: [
      { key: 'name', label: '보유기술', kind: 'text' },
      { key: 'level', label: '수준', kind: 'text', placeholder: '예: 상 / 중상 / 중' },
      { key: 'description', label: '상세 내용', kind: 'textarea' },
    ],
  },
  {
    key: 'projects', label: '프로젝트 경험',
    fields: [
      { key: 'name', label: '프로젝트명', kind: 'text' },
      { key: 'headcount', label: '참여인원', kind: 'text', placeholder: '예: 5명' },
      { key: 'startYm', label: '시작년월', kind: 'ym', placeholder: YM },
      { key: 'endYm', label: '종료년월', kind: 'ym', placeholder: YM },
      { key: 'summary', label: '개요', kind: 'textarea' },
      { key: 'techStack', label: '기술 환경', kind: 'url', placeholder: '예: Python, PyTorch' },
      { key: 'role', label: '담당 역할', kind: 'url' },
      { key: 'outcome', label: '성과 및 배운점', kind: 'textarea' },
      { key: 'description', label: '상세 내용', kind: 'textarea' },
      { key: 'url', label: '참고 링크', kind: 'url', placeholder: 'https://' },
    ],
  },
]

/** 입력 길이 상한. 백엔드 @Size 와 같다. file·select·terms 는 자유 입력이 아니라 maxlength 를 쓰지 않는다. */
export const MAX_LENGTH: Record<FieldKind, number> = {
  text: 200, ym: 200, url: 500, textarea: 5000, file: 200, select: 200, terms: 200,
}

/** 이 필드가 이 행에서 보이는가. when 이 없으면 항상 보인다. */
export function visible(field: FieldConfig, row: Row): boolean {
  return !field.when || field.when.equals.includes(text(row, field.when.key))
}

export function emptyRow(section: SectionConfig): Row {
  return Object.fromEntries(section.fields.map((f) => [f.key, f.kind === 'terms' ? [] : ''])) as Row
}

export function emptyContent(): ResumeContent {
  const basic = Object.fromEntries(BASIC_FIELDS.map((f) => [f.key, ''])) as unknown as BasicInfo
  const sections = Object.fromEntries(SECTIONS.map((s) => [s.key, [] as Row[]])) as Record<SectionKey, Row[]>
  return { basic, ...sections }
}

/** 서버가 null 로 내려준 칸을 빈 값으로. 입력칸은 null 을 못 받고, 중첩 표는 [] 이어야 한다. */
export function fillContent(content: ResumeContent): ResumeContent {
  const empty = emptyContent()
  const basic = {
    ...empty.basic,
    ...Object.fromEntries(Object.entries(content.basic ?? {}).map(([k, v]) => [k, v ?? ''])),
  } as BasicInfo
  const sections = Object.fromEntries(
    SECTIONS.map((section) => [
      section.key,
      (content[section.key] ?? []).map((row) => fillRow(section, row)),
    ]),
  ) as Record<SectionKey, Row[]>
  return { basic, ...sections }
}

function fillRow(section: SectionConfig, row: Row): Row {
  const filled: Row = {}
  for (const field of section.fields) {
    if (field.kind === 'terms') {
      const columns = field.columns ?? []
      filled[field.key] = terms(row, field.key).map((term) =>
        Object.fromEntries(columns.map((c) => [c.key, term?.[c.key] ?? ''])) as TermRow)
    } else {
      filled[field.key] = text(row, field.key)
    }
  }
  return filled
}
