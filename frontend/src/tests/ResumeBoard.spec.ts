import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeBoard from '../components/ResumeBoard.vue'
import ResumeEditor from '../components/ResumeEditor.vue'
import * as resumes from '../api/resumes'
import * as positions from '../api/positions'
import * as introductions from '../api/self-introductions'
import type { Resume } from '../types/resume'

vi.mock('../api/resumes', () => {
  class ApiClientError extends Error {
    fieldErrors = {}
  }
  return {
    ApiClientError,
    listResumes: vi.fn(() => Promise.resolve([])),
    getResume: vi.fn(),
    createResume: vi.fn(),
    updateResume: vi.fn(),
    copyResume: vi.fn(),
    deleteResume: vi.fn(),
  }
})
vi.mock('../api/positions', () => ({ listPositions: vi.fn() }))
vi.mock('../api/self-introductions', () => ({ listSelfIntroductions: vi.fn() }))

const resume = { id: 'resume', name: '이력서', content: {}, positionIds: [], createdAt: '', updatedAt: '' } as unknown as Resume
const linked = [{ id: 'intro', resumeIds: ['resume'], question: '지원 동기', answer: '', createdAt: '', updatedAt: '' }]

describe('이력서 편집기 열기', () => {
  beforeEach(() => {
    vi.mocked(positions.listPositions).mockReset().mockResolvedValue([])
    vi.mocked(introductions.listSelfIntroductions).mockReset().mockResolvedValue(linked)
  })

  it('연결 문항 요청이 이력서 응답을 기다리지 않고 함께 나가 편집기에 넘겨진다', async () => {
    // 공개 도메인에서 요청 하나가 약 0.3초다. 문항을 편집기가 그려진 뒤에 부르면 그만큼 더 기다린다.
    let finishResume!: (value: Resume) => void
    vi.mocked(resumes.getResume).mockReset().mockReturnValue(new Promise((resolve) => { finishResume = resolve }))

    const wrapper = shallowMount(ResumeBoard, { props: { focus: 'resume' } })
    await flushPromises()

    expect(introductions.listSelfIntroductions).toHaveBeenCalledWith('', 'resume')
    expect(wrapper.findComponent(ResumeEditor).exists()).toBe(false)

    finishResume(resume)
    await flushPromises()

    expect(wrapper.findComponent(ResumeEditor).props('questions')).toEqual(linked)
    expect(introductions.listSelfIntroductions).toHaveBeenCalledTimes(1)
  })

  it('A 를 연 뒤 B 를 열면, 늦게 도착한 A 응답이 B 와 B 의 문항을 덮지 않는다', async () => {
    const resumeA = { ...resume, id: 'a', name: 'A' } as Resume
    const resumeB = { ...resume, id: 'b', name: 'B' } as Resume
    const questionsA = [{ ...linked[0], id: 'qa', question: 'A 문항' }]
    const questionsB = [{ ...linked[0], id: 'qb', question: 'B 문항' }]
    let finishA!: (value: Resume) => void
    vi.mocked(resumes.getResume).mockReset()
      .mockReturnValueOnce(new Promise((resolve) => { finishA = resolve }))
      .mockResolvedValueOnce(resumeB)
    vi.mocked(introductions.listSelfIntroductions)
      .mockImplementation((_query, resumeId) => Promise.resolve(resumeId === 'a' ? questionsA : questionsB))

    const wrapper = shallowMount(ResumeBoard, { props: { focus: 'a' } })
    await flushPromises()
    await wrapper.setProps({ focus: 'b' })
    await flushPromises()

    finishA(resumeA)
    await flushPromises()

    const editor = wrapper.findComponent(ResumeEditor)
    expect(editor.props('resume')).toEqual(resumeB)
    expect(editor.props('questions')).toEqual(questionsB)
  })

  it('문항 요청이 실패해도 이력서는 열리고, 문항 칸이 직접 다시 부르도록 null 을 넘긴다', async () => {
    vi.mocked(resumes.getResume).mockReset().mockResolvedValue(resume)
    vi.mocked(introductions.listSelfIntroductions).mockRejectedValue(new Error('일시 오류'))

    const wrapper = shallowMount(ResumeBoard, { props: { focus: 'resume' } })
    await flushPromises()

    const editor = wrapper.findComponent(ResumeEditor)
    expect(editor.exists()).toBe(true)
    expect(editor.props('questions')).toBeNull()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})
