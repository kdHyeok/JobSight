import type { JobPosting } from './posting'

export type CompanySize = 'STARTUP' | 'SMALL' | 'MEDIUM' | 'LARGE' | 'PUBLIC'
export type RevenueUnit = 'TEN_THOUSAND' | 'HUNDRED_MILLION'

/** 기업의 주요 사업 한 건. 입력 순서를 유지한다. */
export interface BusinessArea {
  name: string
  description: string | null
}

export interface Company {
  id: string
  name: string
  websiteUrl: string | null
  /** 업종 다중값. 입력 순서를 유지한다. */
  industries: string[]
  /** 주요 사업. 업종과 달리 설명이 붙는다. */
  businesses: BusinessArea[]
  companySize: CompanySize | null
  /** 원 단위. 표기는 formatRevenue 가 만든다. */
  annualRevenue: number | null
  revenueUnit: RevenueUnit | null
  employeeCount: number | null
  address: string | null
  /** YYYY-MM-DD. 입력은 연월만 받고 1일로 저장된다. */
  foundedOn: string | null
  summary: string | null
  benefits: string | null
  memo: string | null
  /** 사용자 입력이 아니라 공고에서 채워진다. 목록 응답에서는 비어 있다. */
  openPostings: JobPosting[]
  createdAt: string
  updatedAt: string
  /** 상세에서만 온다(목록·수정 응답은 null) — 이 기업을 지우면 직접 출처가 사라질 자소서 수. 삭제 확인창이 쓴다. */
  linkedSelfIntroCount?: number | null
}

export interface CompanyPayload {
  name: string
  websiteUrl: string
  industries: string[]
  businesses: BusinessArea[]
  companySize: CompanySize | null
  annualRevenue: number | null
  revenueUnit: RevenueUnit | null
  employeeCount: number | null
  address: string
  foundedOn: string | null
  summary: string
  benefits: string
  memo: string
}

export interface ApiErrorBody {
  timestamp: string
  status: number
  code: string
  message: string
  fieldErrors: Record<string, string>
}

export const companySizeLabels: Record<CompanySize, string> = {
  STARTUP: '스타트업',
  SMALL: '중소기업',
  MEDIUM: '중견기업',
  LARGE: '대기업',
  PUBLIC: '공기업',
}

/** 저장한 입력 단위로 표시한다. V12 이전 값은 금액 크기로 단위를 고른다. */
export function formatRevenue(won: number | null, savedUnit: RevenueUnit | null = null): string {
  if (won === null) return '—'
  const unit = savedUnit ?? (won >= 100_000_000 ? 'HUNDRED_MILLION' : 'TEN_THOUSAND')
  const divisor = unit === 'HUNDRED_MILLION' ? 100_000_000 : 10_000
  const value = Number((won / divisor).toFixed(2))
  return `${value.toLocaleString('ko-KR')}${unit === 'HUNDRED_MILLION' ? '억 원' : '만 원'}`
}

/** 2015-03-01 -> 2015.03 */
export function formatFoundedOn(value: string | null): string {
  if (!value) return '—'
  const [year, month] = value.split('-')
  return `${year}.${month}`
}
