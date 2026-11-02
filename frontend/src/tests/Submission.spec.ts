import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SubmissionRecords from '../components/SubmissionRecords.vue'
import WritingGuideEditor from '../components/WritingGuideEditor.vue'
import * as api from '../api/submissions'
import { ApiClientError } from '../api/http'
import type { Submission } from '../types/submission'

vi.mock('../api/submissions', async () => ({
  ApiClientError: (await import('../api/http')).ApiClientError,
  listSubmissions: vi.fn(),
  confirmSubmission: vi.fn(),
  getWritingGuide: vi.fn(),
  saveWritingGuide: vi.fn(),
}))
vi.mock('../api/resumes', () => ({
  listResumes: vi.fn().mockResolvedValue([
    { id: 'r1', name: '백엔드', createdAt: '', updatedAt: '' },
    { id: 'r2', name: '데이터', createdAt: '', updatedAt: '' },
  ]),
}))
vi.mock('../api/self-introductions', () => ({
  listSelfIntroductions: vi.fn().mockResolvedValue([
    { id: 's1', question: '지원 동기', resumeIds: [], answer: '', createdAt: '', updatedAt: '', version: 0 },
  ]),
}))
enableAutoUnmount(afterEach)

const linked: Submission = {
  id: 'sub1', level: 'LINKED', recordedAt: '2026-11-02T00:00:00Z',
  resumes: [{ resumeId: 'r1', name: '백엔드', version: 3 }], selfIntros: [{ id: 's1', question: '지원 동기' }],
  attachmentIds: [], receipt: null,
}
const buttonByText = (wrapper: ReturnType<typeof mount>, text: string) =>
  wrapper.findAll('button').find((button) => button.text() === text)!

describe('제출 기록', () => {
  beforeEach(() => {
    vi.mocked(api.listSubmissions).mockReset().mockResolvedValue([linked])
    vi.mocked(api.confirmSubmission).mockReset().mockResolvedValue({ ...linked, id: 'sub2', level: 'RECEIPT_VERIFIED' })
  })

  it('LINKED 는 제출본이 아니라 기록 당시 연결 자료로 보인다', async () => {
    const wrapper = mount(SubmissionRecords, { props: { postingId: 'p1', revision: 'a' } })
    await flushPromises()
    expect(api.listSubmissions).toHaveBeenCalledWith('p1')
    expect(wrapper.text()).toContain('기록 당시 연결 자료')
    expect(wrapper.text()).toContain('미확인')
    expect(wrapper.text()).not.toContain('사용자가 확인한 제출본')
  })

  it('최근 기록의 문서를 처음 선택으로 두고, 접수 근거를 붙여 확인한다', async () => {
    const wrapper = mount(SubmissionRecords, { props: { postingId: 'p1', revision: 'a' } })
    await flushPromises()
    await buttonByText(wrapper, '실제로 낸 것 확인').trigger('click')
    await flushPromises()
    const checked = wrapper.findAll<HTMLInputElement>('input[type=checkbox]').filter((box) => box.element.checked)
    expect(checked.map((box) => box.element.value)).toEqual(['r1', 's1'])

    await wrapper.get('input[value=RECEIPT_VERIFIED]').setValue(true)
    expect(buttonByText(wrapper, '확인 기록').attributes('disabled')).toBeDefined()
    await wrapper.get('input[maxlength="100"]').setValue('A-123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.confirmSubmission).toHaveBeenCalledWith('p1', {
      level: 'RECEIPT_VERIFIED', resumeIds: ['r1'], selfIntroIds: ['s1'],
      receiptExcerpt: null, receiptNumber: 'A-123', receiptAttachmentIds: [],
    })
    expect(api.listSubmissions).toHaveBeenCalledTimes(2)
  })

  it('공고가 바뀌면(revision) 기록을 다시 읽는다', async () => {
    const wrapper = mount(SubmissionRecords, { props: { postingId: 'p1', revision: 'a' } })
    await flushPromises()
    await wrapper.setProps({ revision: 'b' })
    await flushPromises()
    expect(api.listSubmissions).toHaveBeenCalledTimes(2)
  })

  it('공고를 바꾸면 늦게 온 이전 공고 응답을 버리고 편집 중 선택을 닫는다', async () => {
    let resolveOld!: (value: Submission[]) => void
    vi.mocked(api.listSubmissions)
      .mockResolvedValueOnce([linked])
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
      .mockResolvedValueOnce([{ ...linked, id: 'subB', resumes: [{ resumeId: 'r2', name: '데이터', version: 1 }] }])
    const wrapper = mount(SubmissionRecords, { props: { postingId: 'p1', revision: 'a' } })
    await flushPromises()
    await buttonByText(wrapper, '실제로 낸 것 확인').trigger('click')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)

    await wrapper.setProps({ revision: 'b' }) // p1 재조회 — 응답이 늦는다
    await wrapper.setProps({ postingId: 'p2', revision: 'c' })
    await flushPromises()
    resolveOld([{ ...linked, id: 'subOld', resumes: [{ resumeId: 'r1', name: '옛 공고 이력서', version: 1 }] }])
    await flushPromises()

    expect(wrapper.text()).toContain('데이터')
    expect(wrapper.text()).not.toContain('옛 공고 이력서')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(api.confirmSubmission).not.toHaveBeenCalled()
  })

  it('기록에 남았지만 지금은 없는 문서는 처음 선택에 넣지 않는다(보이지 않는 선택이 404 를 내지 않게)', async () => {
    vi.mocked(api.listSubmissions).mockResolvedValue([{ ...linked, resumes: [...linked.resumes, { resumeId: 'gone', name: '지운 이력서', version: 1 }],
      selfIntros: [...linked.selfIntros, { id: 'gone-s', question: '지운 문항' }] }])
    const wrapper = mount(SubmissionRecords, { props: { postingId: 'p1', revision: 'a' } })
    await flushPromises()
    await buttonByText(wrapper, '실제로 낸 것 확인').trigger('click')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.confirmSubmission).toHaveBeenCalledWith('p1', expect.objectContaining({ resumeIds: ['r1'], selfIntroIds: ['s1'] }))
  })

  it('저장 중에 취소하고 다시 연 폼은 이전 저장의 완료·실패가 닫거나 덮지 않는다', async () => {
    let finish!: (value: Submission) => void
    vi.mocked(api.confirmSubmission).mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const wrapper = mount(SubmissionRecords, { props: { postingId: 'p1', revision: 'a' } })
    await flushPromises()
    await buttonByText(wrapper, '실제로 낸 것 확인').trigger('click')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await buttonByText(wrapper, '취소').trigger('click')
    await buttonByText(wrapper, '실제로 낸 것 확인').trigger('click')
    await flushPromises()

    finish(linked)
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
  })
})

describe('작성 기준', () => {
  beforeEach(() => {
    vi.mocked(api.getWritingGuide).mockReset().mockResolvedValue({ version: 2, body: '- 과장하지 않는다', updatedAt: null })
    vi.mocked(api.saveWritingGuide).mockReset()
  })

  async function opened() {
    const wrapper = mount(WritingGuideEditor)
    const details = wrapper.get('details')
    ;(details.element as HTMLDetailsElement).open = true
    await details.trigger('toggle')
    await flushPromises()
    return wrapper
  }

  it('읽은 version 으로 저장한다', async () => {
    vi.mocked(api.saveWritingGuide).mockResolvedValue({ version: 3, body: '새 기준', updatedAt: null })
    const wrapper = await opened()
    await wrapper.get('textarea').setValue('새 기준')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.saveWritingGuide).toHaveBeenCalledWith('새 기준', 2)
    expect(wrapper.text()).toContain('저장했습니다.')
  })

  it('다른 곳에서 먼저 저장했으면(409) 새 내용을 다시 읽는다', async () => {
    vi.mocked(api.saveWritingGuide).mockRejectedValue(new ApiClientError(409, 'WRITING_GUIDE_STALE', '다른 곳에서 먼저 저장했습니다.'))
    const wrapper = await opened()
    vi.mocked(api.getWritingGuide).mockResolvedValue({ version: 4, body: '남이 고친 기준', updatedAt: null })
    await wrapper.get('textarea').setValue('내 입력')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('남이 고친 기준')
    expect(wrapper.text()).toContain('다른 곳에서 바뀌었습니다')
  })

  it('409 뒤 다시 읽기도 실패하면 입력을 남기고 이유를 보인다', async () => {
    vi.mocked(api.saveWritingGuide).mockRejectedValue(new ApiClientError(409, 'WRITING_GUIDE_STALE', '다른 곳에서 먼저 저장했습니다.'))
    const wrapper = await opened()
    vi.mocked(api.getWritingGuide).mockRejectedValue(new ApiClientError(503, 'UNAVAILABLE', '잠시 뒤 다시 시도하세요.'))
    await wrapper.get('textarea').setValue('내 입력')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('내 입력')
    expect(wrapper.get('[role="status"]').text()).toContain('새 내용을 불러오지 못했습니다')
  })

  it('처음 읽는 중에 접었다 다시 펼쳐도 한 번만 부른다', async () => {
    let finish!: (value: Awaited<ReturnType<typeof api.getWritingGuide>>) => void
    vi.mocked(api.getWritingGuide).mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const wrapper = mount(WritingGuideEditor)
    const details = wrapper.get('details')
    const element = details.element as HTMLDetailsElement
    element.open = true
    await details.trigger('toggle')
    element.open = false
    await details.trigger('toggle')
    element.open = true
    await details.trigger('toggle')
    finish({ version: 2, body: '- 과장하지 않는다', updatedAt: null })
    await flushPromises()
    expect(api.getWritingGuide).toHaveBeenCalledTimes(1)
  })
})
