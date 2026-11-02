import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ResumeSection from '../components/ResumeSection.vue'
import { SECTIONS, emptyRow, terms } from '../types/resume'
import type { Row } from '../types/resume'

// 첨부 필드는 마운트되자마자 서버를 부른다. 이 테스트가 볼 것은 학업 이수 쪽이다.
vi.mock('../api/attachments', () => ({
  uploadAttachment: vi.fn(),
  fetchAttachmentMeta: vi.fn().mockResolvedValue({ id: 'x', filename: 'x', contentType: 'image/png', size: 1 }),
  attachmentUrl: (id: string) => `/api/attachments/${id}`,
}))

const educations = SECTIONS.find((section) => section.key === 'educations')!

const rowOf = (schoolType: string): Row => ({ ...emptyRow(educations), schoolType })

const mountWith = (row: Row) => mount(ResumeSection, { props: { section: educations, rows: [row] } })

describe('학력 — 학교 구분에 따라 학업 이수 입력이 갈린다', () => {
  it('대학교면 학년별 전공 이수와 총 이수학점만 보인다', () => {
    const text = mountWith(rowOf('대학교')).text()
    expect(text).toContain('학년별 전공 이수')
    expect(text).toContain('총 이수학점')
    expect(text).not.toContain('학기별 이수 과목')
    expect(text).not.toContain('내신등급')
  })

  it('고등학교면 학기별 이수 과목과 내신등급만 보인다', () => {
    const text = mountWith(rowOf('고등학교')).text()
    expect(text).toContain('학기별 이수 과목')
    expect(text).toContain('내신등급')
    expect(text).toContain('대입검정고시 유무')
    expect(text).not.toContain('학년별 전공 이수')
  })

  /** 증빙은 학교 구분과 무관하게 늘 있어야 한다. */
  it('졸업증·성적증명서는 어느 구분에서도 보인다', () => {
    for (const type of ['대학교', '고등학교', '']) {
      const text = mountWith(rowOf(type)).text()
      expect(text).toContain('졸업증')
      expect(text).toContain('성적증명서')
    }
  })

  it('중첩 표의 행 추가가 그 필드만 바꾼다', async () => {
    const wrapper = mountWith(rowOf('대학교'))
    const addTermRow = wrapper.findAll('button').filter((b) => b.text() === '+ 행 추가').at(-1)!
    await addTermRow.trigger('click')

    const updated = wrapper.emitted('update:rows')!.at(-1)![0] as Row[]
    expect(terms(updated[0], 'collegeTerms')).toHaveLength(1)
    expect(terms(updated[0], 'schoolTerms')).toHaveLength(0)
  })
})
