import { request } from './http'
import { API } from '../routes'
import type { AttachmentItem, AttachmentMeta } from '../types/attachment'

/**
 * FormData 를 그대로 넘긴다 — Content-Type 을 직접 붙이면 boundary 가 빠져 서버가 파트를 못 읽는다.
 * http.ts 가 FormData 를 예외로 두는 이유다.
 */
export function uploadAttachment(file: File): Promise<AttachmentMeta> {
  const body = new FormData()
  body.append('file', file)
  return request<AttachmentMeta>(API.attachments, { method: 'POST', body })
}

export const fetchAttachmentMeta = (id: string) => request<AttachmentMeta>(API.attachmentMeta(id))

/** 첨부 서랍 목록. 사용처와 서버가 계산한 deletable 이 함께 온다. */
export const listAttachments = () => request<AttachmentItem[]>(API.attachments)

/**
 * 되돌릴 수 없다. 저장·이력서 삭제 뒤 묻기와 첨부 서랍에서만 부른다 — 첨부 칸의 교체·제거는 부르지 않는다
 * (복제본이 같은 id 를 쓴다, AttachmentField.spec). 다른 곳에서 쓰면 서버가 409 ATTACHMENT_IN_USE 를 준다.
 */
export const deleteAttachment = (id: string) => request<void>(API.attachment(id), { method: 'DELETE' })

/** img·iframe 의 src. 세션 쿠키로 인증되므로 그대로 쓰면 된다. */
export const attachmentUrl = (id: string) => API.attachment(id)
