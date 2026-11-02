import { request } from './http'
import { API } from '../routes'
import type { AttachmentMeta } from '../types/attachment'

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

/** img·iframe 의 src. 세션 쿠키로 인증되므로 그대로 쓰면 된다. */
export const attachmentUrl = (id: string) => API.attachment(id)
