import { attachmentUrl, fetchAttachmentMeta } from '../api/attachments'
import type { PositionOption } from '../types/position'
import type { ResumeContent } from '../types/resume'
import { BASIC_FIELDS, SECTIONS, text } from '../types/resume'

const encoder = new TextEncoder()
const u16 = (value: number) => [value & 255, (value >>> 8) & 255]
const u32 = (value: number) => [...u16(value), ...u16(value >>> 16)]

function crc32(bytes: Uint8Array) {
  let crc = 0xffffffff
  for (const byte of bytes) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1))
  }
  return (crc ^ 0xffffffff) >>> 0
}

/** 의존성 없이 만드는 표준 store ZIP. 첨부는 이미 압축된 jpg/png/pdf라 재압축 이득이 없다. */
export function buildZip(files: { name: string; bytes: Uint8Array }[]): Uint8Array<ArrayBuffer> {
  const locals: Uint8Array[] = []
  const centrals: Uint8Array[] = []
  let offset = 0
  for (const file of files) {
    const name = encoder.encode(file.name)
    const crc = crc32(file.bytes)
    const local = new Uint8Array([80, 75, 3, 4, 20, 0, 0, 8, 0, 0, 0, 0, 0, 0, ...u32(crc), ...u32(file.bytes.length), ...u32(file.bytes.length), ...u16(name.length), 0, 0, ...name, ...file.bytes])
    locals.push(local)
    centrals.push(new Uint8Array([80, 75, 1, 2, 20, 0, 20, 0, 0, 8, 0, 0, 0, 0, 0, 0, ...u32(crc), ...u32(file.bytes.length), ...u32(file.bytes.length), ...u16(name.length), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ...u32(offset), ...name]))
    offset += local.length
  }
  const centralSize = centrals.reduce((sum, value) => sum + value.length, 0)
  const end = new Uint8Array([80, 75, 5, 6, 0, 0, 0, 0, ...u16(files.length), ...u16(files.length), ...u32(centralSize), ...u32(offset), 0, 0])
  const parts = [...locals, ...centrals, end]
  const result = new Uint8Array(parts.reduce((sum, value) => sum + value.length, 0))
  let cursor = 0
  for (const part of parts) { result.set(part, cursor); cursor += part.length }
  return result
}

export function zipFiles(files: { name: string; bytes: Uint8Array }[]): Blob {
  return new Blob([buildZip(files).buffer], { type: 'application/zip' })
}

function download(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

const escape = (value: unknown) => String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]!)

export function downloadWord(name: string, content: ResumeContent, positions: PositionOption[]) {
  const sections = SECTIONS.map((section) => {
    const rows = content[section.key].map((row) => `<article>${section.fields.filter((field) => field.kind !== 'file' && field.kind !== 'terms').map((field) => `<p><b>${escape(field.label)}</b> ${escape(text(row, field.key))}</p>`).join('')}</article>`).join('')
    return rows ? `<h2>${escape(section.label)}</h2>${rows}` : ''
  }).join('')
  const html = `<!doctype html><html><meta charset="utf-8"><body><h1>${escape(name)}</h1><h2>지원 직무</h2><p>${positions.map((item) => escape(`${item.companyName || ''} ${item.name}`)).join(', ')}</p><h2>기본정보</h2>${BASIC_FIELDS.filter((field) => field.kind !== 'file').map((field) => `<p><b>${escape(field.label)}</b> ${escape(content.basic[field.key as keyof typeof content.basic])}</p>`).join('')}${sections}</body></html>`
  download(new Blob(['\ufeff', html], { type: 'application/msword' }), `${name}.doc`)
}

export async function downloadAttachments(name: string, ids: string[]) {
  const files = await Promise.all([...new Set(ids)].map(async (id) => {
    const meta = await fetchAttachmentMeta(id)
    const response = await fetch(attachmentUrl(id), { credentials: 'same-origin' })
    if (!response.ok) throw new Error(`${meta.filename} 다운로드 실패`)
    return { name: meta.filename, bytes: new Uint8Array(await response.arrayBuffer()) }
  }))
  if (files.length) download(zipFiles(files), `${name}-첨부파일.zip`)
}
