/** 관계를 누가 남겼나. 웹 저장은 USER(확정), MCP 저장은 AI(AI 기록). AI → USER 확정은 웹에서만 한다. */
export type LinkSource = 'USER' | 'AI'

/**
 * 문항 유형 코드. 백엔드 enum Competency·V23 competencies 표와 순서·이름이 같아야 한다.
 * 값을 지어내지 않도록 이 목록 밖의 코드는 보내지 않는다.
 */
export const competencyLabels = {
  COLLABORATION: '협업·팀워크',
  COMMUNICATION: '의사소통·설득',
  PROBLEM_SOLVING: '문제 해결',
  CONFLICT: '갈등 조정',
  LEADERSHIP: '리더십·주도성',
  CHALLENGE: '도전·목표 달성',
  FAILURE: '실패와 극복',
  LEARNING: '학습·자기개발',
  TECH_EXPERTISE: '기술 전문성',
  DATA_ANALYSIS: '데이터·수리 분석',
  IMPROVEMENT: '개선·창의',
  CUSTOMER: '고객·사용자 지향',
  ETHICS: '원칙·직업윤리',
  MOTIVATION: '지원동기',
  ASPIRATION: '입사 후 포부',
  JOB_FIT: '직무 이해·적합성',
  GROWTH: '성장과정·가치관',
  PERSONALITY: '성격 장단점',
} as const

export type Competency = keyof typeof competencyLabels

export interface PositionLink {
  positionId: string
  source: LinkSource
}

export interface QuestionTypeLink {
  code: Competency
  source: LinkSource
}

export interface SelfIntroduction {
  id: string
  resumeIds: string[]
  question: string
  answer: string | null
  createdAt: string
  updatedAt: string
  /** 이 답변을 어느 지원용으로 썼나(직접 출처). */
  positionLinks?: PositionLink[]
  questionTypes?: QuestionTypeLink[]
}

/**
 * 생성은 이력서 또는 직무가 1개 이상, 수정은 0개도 된다.
 * positionIds·questionTypes 를 빼면(undefined) 서버가 기존 연결을 그대로 둔다.
 */
export interface SelfIntroductionPayload {
  resumeIds: string[]
  question: string
  answer: string
  positionIds?: string[]
  questionTypes?: Competency[]
}

/** 웹의 ✓: 이미 연결된 AI 기록을 확정한다. */
export interface SelfIntroductionConfirmPayload {
  positionIds?: string[]
  questionTypes?: Competency[]
}
