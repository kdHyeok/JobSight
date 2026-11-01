import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeQuestions from '../components/ResumeQuestions.vue'
import * as api from '../api/self-introductions'

vi.mock('../api/self-introductions', () => ({
  listSelfIntroductions: vi.fn(),
  createSelfIntroduction: vi.fn(),
  updateSelfIntroduction: vi.fn(),
}))

const item = {
  id: 'intro',
  resumeIds: ['resume', 'other'],
  question: '기존 질문',
  answer: '기존 답변',
  createdAt: '',
  updatedAt: '',
}

describe('이력서 자기소개 문항', () => {
  beforeEach(() => {
    vi.mocked(api.listSelfIntroductions).mockReset().mockResolvedValue([item])
    vi.mocked(api.updateSelfIntroduction).mockReset().mockResolvedValue(item)
  })

  it('부모가 이력서와 함께 받은 문항이 있으면 다시 부르지 않고, 수정 뒤에만 새로 부른다', async () => {
    const wrapper = mount(ResumeQuestions, { props: { resumeId: 'resume', questions: [item] } })
    await flushPromises()

    expect(api.listSelfIntroductions).not.toHaveBeenCalled()
    expect(wrapper.get('.question-list').text()).toContain('기존 질문')

    await wrapper.get('.question-list .button').trigger('click')
    await wrapper.get('.question-edit').trigger('submit')
    await flushPromises()
    expect(api.listSelfIntroductions).toHaveBeenCalledWith('', 'resume')
  })

  it('부모가 문항을 받지 못했으면(null) 직접 부른다', async () => {
    mount(ResumeQuestions, { props: { resumeId: 'resume', questions: null } })
    await flushPromises()

    expect(api.listSelfIntroductions).toHaveBeenCalledWith('', 'resume')
  })

  it('이력서가 바뀌면 이전 이력서의 목록과 수정 상태를 바로 지운다', async () => {
    vi.mocked(api.listSelfIntroductions).mockReturnValue(new Promise(() => {}))
    const wrapper = mount(ResumeQuestions, { props: { resumeId: 'resume', questions: [item] } })
    await wrapper.get('.question-list .button').trigger('click')
    expect(wrapper.find('.question-edit').exists()).toBe(true)

    // 부모가 새 이력서의 문항을 받지 못한 경우(null). 직접 부르는 동안 이전 문항이 보이면 안 된다.
    await wrapper.setProps({ resumeId: 'next', questions: null })

    expect(wrapper.find('.question-edit').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('기존 질문')
    expect(api.listSelfIntroductions).toHaveBeenCalledWith('', 'next')
  })

  it('이력서가 바뀐 뒤 도착한 이전 이력서의 조회 결과는 버린다', async () => {
    const next = { ...item, id: 'next-intro', question: '다음 이력서 질문' }
    let finishOld!: (value: typeof item[]) => void
    vi.mocked(api.listSelfIntroductions).mockReset()
      .mockReturnValueOnce(new Promise((resolve) => { finishOld = resolve }))
      .mockResolvedValueOnce([next])

    const wrapper = mount(ResumeQuestions, { props: { resumeId: 'resume', questions: null } })
    await wrapper.setProps({ resumeId: 'next', questions: null })
    await flushPromises()

    finishOld([item])
    await flushPromises()

    expect(wrapper.text()).toContain('다음 이력서 질문')
    expect(wrapper.text()).not.toContain('기존 질문')
  })

  it('기존 문항을 먼저 보여주고 그 자리에서 수정한다', async () => {
    const wrapper = mount(ResumeQuestions, { props: { resumeId: 'resume' } })
    await flushPromises()

    const list = wrapper.get('.question-list').element
    expect(list.compareDocumentPosition(wrapper.get('.question-add').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(list.compareDocumentPosition(wrapper.get('.question-search').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    await wrapper.get('.question-list .button').trigger('click')
    const fields = wrapper.findAll<HTMLTextAreaElement>('.question-edit textarea')
    await fields[0].setValue('수정 질문')
    await fields[1].setValue('수정 답변')
    await wrapper.get('.question-edit').trigger('submit')
    await flushPromises()

    expect(api.updateSelfIntroduction).toHaveBeenCalledWith('intro', {
      resumeIds: ['resume', 'other'], question: '수정 질문', answer: '수정 답변',
    })
  })
})
