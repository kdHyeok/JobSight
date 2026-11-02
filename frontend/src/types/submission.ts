/**
 * 제출 기록(설계 6.6). 고치지 않고 쌓인다.
 * LINKED 는 공고가 제출 완료로 바뀔 때 남는 "기록 당시 연결 자료"이지 제출본이 아니다.
 */
export type SubmissionLevel = 'LINKED' | 'USER_CONFIRMED' | 'RECEIPT_VERIFIED'

export const submissionLevelLabels: Record<SubmissionLevel, string> = {
  LINKED: '기록 당시 연결 자료',
  USER_CONFIRMED: '사용자가 확인한 제출본',
  RECEIPT_VERIFIED: '접수 근거와 대조한 제출본',
}

export interface Submission {
  id: string
  level: SubmissionLevel
  recordedAt: string
  resumes: Array<{ resumeId: string; name: string; version: number }>
  selfIntros: Array<{ id: string; question: string }>
  attachmentIds: string[]
  receipt: { excerpt: string | null; receiptNumber: string | null; attachmentIds: string[] } | null
}

export interface SubmissionPayload {
  level: Exclude<SubmissionLevel, 'LINKED'>
  resumeIds: string[]
  selfIntroIds: string[]
  receiptExcerpt: string | null
  receiptNumber: string | null
  receiptAttachmentIds: string[]
}

/** 계정당 작성 기준 Markdown(설계 6.7). 아직 쓰지 않았으면 version 0. */
export interface WritingGuide {
  version: number
  body: string
  updatedAt: string | null
}
