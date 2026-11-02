/** 첨부 메타. 이력서 행에는 id 문자열만 저장한다 — docs/attachments.md. */
export interface AttachmentMeta {
  id: string
  filename: string
  contentType: string
  size: number
}

/** 첨부 서랍 한 줄(GET /api/attachments). deletable 은 서버가 계산한다: 어디서도 쓰지 않고 올린 지 7일이 지났다. */
export interface AttachmentItem extends AttachmentMeta {
  createdAt: string
  usedBy: AttachmentUse[]
  deletable: boolean
}

export interface AttachmentUse {
  type: 'RESUME' | 'EXPERIENCE' | 'EXPERIENCE_REVISION' | 'SUBMISSION' | string
  id: string
  /** 이력서 이름, 경험 제목, 제출 기록이면 공고 제목. */
  name: string
}

export const USE_LABELS: Record<string, string> = {
  RESUME: '이력서',
  EXPERIENCE: '경험',
  EXPERIENCE_REVISION: '경험 개정 이력',
  SUBMISSION: '제출 기록',
}

/**
 * 저장·삭제로 빠진 첨부(설계 6.9 길 1): 저장 전 응답에는 있고 저장 뒤 응답에는 없는 메타.
 * 서버 응답의 attachments 맵 키만 비교한다 — 내 것이고 지금 있는 파일만 실리므로, 이미 지워진 id 는 묻지 않는다.
 */
export function removedAttachments(
  before: Record<string, AttachmentMeta> | undefined,
  after: Record<string, AttachmentMeta> | undefined,
): AttachmentMeta[] {
  return Object.values(before ?? {}).filter((meta) => !after?.[meta.id])
}

/** 파일 선택 대화상자의 필터. 실제 판정은 서버가 파일 앞머리로 한다. */
export const ATTACHMENT_ACCEPT = 'image/jpeg,image/png,application/pdf,.jpg,.jpeg,.png,.pdf'

/** 10MB. 백엔드 AttachmentService.MAX_BYTES · nginx client_max_body_size 와 함께 움직인다. */
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

export const isImage = (contentType: string) => contentType.startsWith('image/')

export function formatBytes(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${Math.round(size / 1024)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
