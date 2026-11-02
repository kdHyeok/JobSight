import type { ApplicationStatus } from './posting'

export type ReferenceKind = 'RELATED_POSITION' | 'EXPERIENCED_POSTING' | 'SENIOR_INTERVIEW' | 'ARTICLE' | 'OTHER'

/** 참고 정보. 계정 안에서 공유되어 여러 직무에 붙는다. */
export interface ReferenceItem {
  id: string
  kind: ReferenceKind
  title: string
  url: string | null
  memo: string | null
  /** kind=RELATED_POSITION 일 때 가리키는 내부 직무. 지워지면 null. */
  relatedPositionId: string | null
  createdAt: string
  updatedAt: string
}

export interface ReferencePayload {
  kind: ReferenceKind
  title: string
  url: string
  memo: string
  relatedPositionId: string | null
}

export const referenceKindLabels: Record<ReferenceKind, string> = {
  RELATED_POSITION: '참고 직무',
  EXPERIENCED_POSTING: '경력 모집',
  SENIOR_INTERVIEW: '선배 인터뷰',
  ARTICLE: '아티클',
  OTHER: '기타',
}

/** 모집 직무 + 소속 공고·기업 요약 + 참고 정보. */
export interface Position {
  id: string
  postingId: string
  postingTitle: string | null
  companyId: string | null
  companyName: string | null
  deadlineAt: string | null
  status: ApplicationStatus | null
  archived: boolean
  name: string
  team: string | null
  role: string | null
  responsibilities: string | null
  impact: string | null
  growth: string | null
  experience: string | null
  requiredSkills: string | null
  preferredSkills: string | null
  headcount: string | null
  workLocation: string | null
  memo: string | null
  techStack: string[]
  references: ReferenceItem[]
  createdAt: string
  updatedAt: string
  /** 상세에서만 온다(목록·수정 응답은 null) — 이 직무를 지우면 직접 출처가 사라질 자소서 수. 삭제 확인창이 쓴다. */
  linkedSelfIntroCount?: number | null
}

export interface PositionPayload {
  name: string
  team: string
  role: string
  responsibilities: string
  impact: string
  growth: string
  experience: string
  requiredSkills: string
  preferredSkills: string
  headcount: string
  workLocation: string
  memo: string
  techStack: string[]
}
