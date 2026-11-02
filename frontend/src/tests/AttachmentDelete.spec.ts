import { flushPromises, mount, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AttachmentDrawer from '../components/AttachmentDrawer.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import ResumeBoard from '../components/ResumeBoard.vue'
import ResumeEditor from '../components/ResumeEditor.vue'
import * as attachments from '../api/attachments'
import * as resumes from '../api/resumes'
import { ApiClientError } from '../api/http'
import type { AttachmentItem, AttachmentMeta } from '../types/attachment'
import type { Resume } from '../types/resume'
import { emptyContent } from '../types/resume'

vi.mock('../api/resumes', async () => ({
  ApiClientError: (await import('../api/http')).ApiClientError,
  listResumes: vi.fn(() => Promise.resolve([])),
  getResume: vi.fn(),
  createResume: vi.fn(),
  updateResume: vi.fn(),
  copyResume: vi.fn(),
  deleteResume: vi.fn(),
}))
vi.mock('../api/positions', () => ({ listPositions: vi.fn(), listPositionOptions: vi.fn(() => Promise.resolve([])) }))
vi.mock('../api/self-introductions', () => ({ listSelfIntroductions: vi.fn(() => Promise.resolve([])) }))
vi.mock('../api/experiences', () => ({
  listExperiences: vi.fn(() => Promise.resolve([])), getExperiences: vi.fn(() => Promise.resolve([])), createExperience: vi.fn(),
}))
vi.mock('../api/attachments', () => ({
  listAttachments: vi.fn(), deleteAttachment: vi.fn(), uploadAttachment: vi.fn(), fetchAttachmentMeta: vi.fn(),
  attachmentUrl: (id: string) => `/api/attachments/${id}`,
}))

const A = '11111111-1111-1111-1111-111111111111'
const B = '22222222-2222-2222-2222-222222222222'
const meta = (id: string, filename: string): AttachmentMeta => ({ id, filename, contentType: 'application/pdf', size: 1024 })

function resumeWith(files: Record<string, string>, version = 0): Resume {
  const content = emptyContent()
  content.certificates = Object.keys(files).map((id) => ({ name: '자격증', fileId: id }))
  return {
    id: 'resume', name: '이력서', content, positionIds: [], version, createdAt: '', updatedAt: '',
    attachments: Object.fromEntries(Object.entries(files).map(([id, name]) => [id, meta(id, name)])),
  }
}

const dialogs = (wrapper: ReturnType<typeof shallowMount>) => wrapper.findAllComponents(ConfirmDialog)
const orphanDialog = (wrapper: ReturnType<typeof shallowMount>) =>
  dialogs(wrapper).find((dialog) => dialog.props('ariaLabel') === '빠진 첨부 삭제 확인')

describe('저장·삭제 뒤 빠진 첨부를 묻는다(설계 6.9 길 1)', () => {
  beforeEach(() => {
    vi.mocked(attachments.deleteAttachment).mockReset().mockResolvedValue(undefined)
  })

  it('저장으로 빠진 첨부만 묻고, 지우겠다고 하면 그 파일만 지운다', async () => {
    vi.mocked(resumes.getResume).mockReset().mockResolvedValue(resumeWith({ [A]: '남는 파일.pdf', [B]: '빠진 파일.pdf' }))
    vi.mocked(resumes.updateResume).mockReset().mockResolvedValue(resumeWith({ [A]: '남는 파일.pdf' }, 1))
    const wrapper = shallowMount(ResumeBoard, { props: { focus: 'resume' } })
    await flushPromises()

    wrapper.findComponent(ResumeEditor).vm.$emit('save', {})
    await flushPromises()

    const dialog = orphanDialog(wrapper)!
    expect(dialog.props('subject')).toBe('빠진 파일.pdf')
    dialog.vm.$emit('confirm')
    await flushPromises()

    expect(attachments.deleteAttachment).toHaveBeenCalledTimes(1)
    expect(attachments.deleteAttachment).toHaveBeenCalledWith(B)
    expect(orphanDialog(wrapper)).toBeUndefined()
    expect(wrapper.text()).toContain('파일 1개를 지웠습니다')
  })

  it('빠진 첨부가 없으면 묻지 않는다. 답을 기억하지 않아 다음 저장에서 또 묻는다', async () => {
    vi.mocked(resumes.getResume).mockReset().mockResolvedValue(resumeWith({ [A]: 'a.pdf', [B]: 'b.pdf' }))
    vi.mocked(resumes.updateResume).mockReset()
      .mockResolvedValueOnce(resumeWith({ [A]: 'a.pdf', [B]: 'b.pdf' }, 1))
      .mockResolvedValueOnce(resumeWith({ [A]: 'a.pdf' }, 2))
      .mockResolvedValueOnce(resumeWith({}, 3))
    const wrapper = shallowMount(ResumeBoard, { props: { focus: 'resume' } })
    await flushPromises()

    wrapper.findComponent(ResumeEditor).vm.$emit('save', {})
    await flushPromises()
    expect(orphanDialog(wrapper)).toBeUndefined()

    wrapper.findComponent(ResumeEditor).vm.$emit('save', {})
    await flushPromises()
    orphanDialog(wrapper)!.vm.$emit('cancel')
    await flushPromises()
    expect(attachments.deleteAttachment).not.toHaveBeenCalled()

    wrapper.findComponent(ResumeEditor).vm.$emit('save', {})
    await flushPromises()
    expect(orphanDialog(wrapper)!.props('subject')).toBe('a.pdf')
  })

  it('다른 곳에서 쓰는 파일(409)은 남겨 두었다고 알린다', async () => {
    vi.mocked(resumes.getResume).mockReset().mockResolvedValue(resumeWith({ [A]: '복제본도 쓰는 파일.pdf' }))
    vi.mocked(resumes.updateResume).mockReset().mockResolvedValue(resumeWith({}, 1))
    vi.mocked(attachments.deleteAttachment).mockRejectedValue(
      new ApiClientError(409, 'ATTACHMENT_IN_USE', "다른 곳에서 쓰고 있어 지울 수 없습니다: 이력서 '복제본'."))
    const wrapper = shallowMount(ResumeBoard, { props: { focus: 'resume' } })
    await flushPromises()

    wrapper.findComponent(ResumeEditor).vm.$emit('save', {})
    await flushPromises()
    orphanDialog(wrapper)!.vm.$emit('confirm')
    await flushPromises()

    expect(wrapper.text()).toContain('다른 곳에서 쓰고 있어 남겨 두었습니다: 복제본도 쓰는 파일.pdf')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('저장이 실패하면 묻지 않는다', async () => {
    vi.mocked(resumes.getResume).mockReset().mockResolvedValue(resumeWith({ [A]: 'a.pdf' }))
    vi.mocked(resumes.updateResume).mockReset().mockRejectedValue(new ApiClientError(409, 'RESUME_STALE', '바뀌었습니다'))
    const wrapper = shallowMount(ResumeBoard, { props: { focus: 'resume' } })
    await flushPromises()

    wrapper.findComponent(ResumeEditor).vm.$emit('save', {})
    await flushPromises()

    expect(orphanDialog(wrapper)).toBeUndefined()
  })

  it('이력서를 지운 뒤 그 이력서의 첨부 전부를 묻는다', async () => {
    vi.mocked(resumes.getResume).mockReset().mockResolvedValue(resumeWith({ [A]: 'a.pdf', [B]: 'b.pdf' }))
    vi.mocked(resumes.deleteResume).mockReset().mockResolvedValue(undefined)
    const wrapper = shallowMount(ResumeBoard, { props: { focus: 'resume' } })
    await flushPromises()

    wrapper.findComponent(ResumeEditor).vm.$emit('remove')
    await flushPromises()
    expect(orphanDialog(wrapper)).toBeUndefined()
    dialogs(wrapper).find((dialog) => dialog.props('ariaLabel') === '이력서 삭제 확인')!.vm.$emit('confirm')
    await flushPromises()
    expect(wrapper.emitted('closeResume')).toHaveLength(1)
    await wrapper.setProps({ focus: null })
    await flushPromises()

    const dialog = orphanDialog(wrapper)!
    expect(dialog.props('subject')).toBe('a.pdf, b.pdf')
    dialog.vm.$emit('confirm')
    await flushPromises()
    expect(vi.mocked(attachments.deleteAttachment).mock.calls).toEqual([[A], [B]])
  })
})

const item = (over: Partial<AttachmentItem>): AttachmentItem => ({
  id: A, filename: '파일.pdf', contentType: 'application/pdf', size: 2048, createdAt: '2026-10-01T00:00:00Z',
  usedBy: [], deletable: false, ...over,
})

describe('첨부 서랍(설계 6.9 길 2)', () => {
  beforeEach(() => {
    vi.mocked(attachments.deleteAttachment).mockReset().mockResolvedValue(undefined)
  })

  it('사용처 칩, 7일 안내, 지울 수 있는 파일에만 삭제 버튼', async () => {
    vi.mocked(attachments.listAttachments).mockReset().mockResolvedValue([
      item({ id: A, filename: '쓰는 파일.pdf', usedBy: [{ type: 'RESUME', id: 'r', name: '백엔드 이력서' },
        { type: 'SUBMISSION', id: 's', name: '마사 신입 공채' }] }),
      item({ id: B, filename: '새 파일.pdf' }),
      item({ id: 'c', filename: '오래된 파일.pdf', deletable: true }),
    ])
    const wrapper = mount(AttachmentDrawer)
    await flushPromises()

    const rows = wrapper.findAll('.attachment-row')
    expect(rows).toHaveLength(3)
    expect(rows[0].text()).toContain('이력서 · 백엔드 이력서')
    expect(rows[0].text()).toContain('제출 기록 · 마사 신입 공채')
    expect(rows[0].find('button.danger').exists()).toBe(false)
    expect(rows[1].text()).toContain('올린 지 7일이 지나면 지울 수 있습니다')
    expect(rows[1].find('button.danger').exists()).toBe(false)
    expect(rows[2].find('button.danger').exists()).toBe(true)
  })

  it('삭제는 확인 뒤에 보내고 목록을 다시 읽는다. 409 면 서버 문구를 보여 준다', async () => {
    vi.mocked(attachments.listAttachments).mockReset()
      .mockResolvedValueOnce([item({ id: 'c', filename: '오래된 파일.pdf', deletable: true })])
      .mockResolvedValueOnce([])
    const wrapper = mount(AttachmentDrawer)
    await flushPromises()

    await wrapper.find('.attachment-row button.danger').trigger('click')
    expect(attachments.deleteAttachment).not.toHaveBeenCalled()
    wrapper.findComponent(ConfirmDialog).vm.$emit('confirm')
    await flushPromises()

    expect(attachments.deleteAttachment).toHaveBeenCalledWith('c')
    expect(attachments.listAttachments).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.attachment-row')).toHaveLength(0)
  })

  it('다른 곳에서 막 쓰기 시작한 파일(409)은 서버 문구를 띄우고 목록을 새로 읽는다', async () => {
    vi.mocked(attachments.listAttachments).mockReset().mockResolvedValue([item({ id: 'c', deletable: true })])
    vi.mocked(attachments.deleteAttachment).mockRejectedValue(
      new ApiClientError(409, 'ATTACHMENT_IN_USE', "다른 곳에서 쓰고 있어 지울 수 없습니다: 이력서 '새 이력서'."))
    const wrapper = mount(AttachmentDrawer)
    await flushPromises()

    await wrapper.find('.attachment-row button.danger').trigger('click')
    wrapper.findComponent(ConfirmDialog).vm.$emit('confirm')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain("이력서 '새 이력서'")
    expect(attachments.listAttachments).toHaveBeenCalledTimes(2)
  })

  it('이력서 목록의 [첨부 파일] 버튼이 서랍을 연다', async () => {
    vi.mocked(attachments.listAttachments).mockReset().mockResolvedValue([])
    const wrapper = shallowMount(ResumeBoard, { props: { focus: null } })
    await flushPromises()

    expect(wrapper.findComponent(AttachmentDrawer).exists()).toBe(false)
    await wrapper.findAll('button').find((button) => button.text() === '첨부 파일')!.trigger('click')
    expect(wrapper.findComponent(AttachmentDrawer).exists()).toBe(true)
  })
})

describe('내보내기 체크 목록', () => {
  it('저장된 문서에 있지만 이력서 응답에 메타가 없는(지워졌거나 남의) 첨부는 거른다', async () => {
    const resume = resumeWith({ [A]: '있는 파일.pdf' })
    resume.content.certificates.push({ name: '지워진 파일', fileId: B })
    const wrapper = mount(ResumeEditor, {
      props: { resume, saving: false, apiFieldErrors: {}, positions: [], questions: [] },
    })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '내보내기')!.trigger('click')
    const values = () => wrapper.findAll('.export-files input').map((input) => (input.element as HTMLInputElement).value)
    expect(values()).toEqual([A])
  })
})
