import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SelfIntroductionBoard from '../components/SelfIntroductionBoard.vue'
import * as api from '../api/self-introductions'
import { listResumes } from '../api/resumes'
import { listPositions } from '../api/positions'
import { highlightMatches } from '../utils/highlight'
import type { Position } from '../types/position'
import type { SelfIntroduction } from '../types/self-introduction'

enableAutoUnmount(afterEach)

vi.mock('../api/self-introductions', () => ({
  listSelfIntroductions: vi.fn(),
  createSelfIntroduction: vi.fn(),
  updateSelfIntroduction: vi.fn(),
  deleteSelfIntroduction: vi.fn(),
  confirmSelfIntroduction: vi.fn(),
}))

vi.mock('../api/resumes', () => ({ listResumes: vi.fn() }))
vi.mock('../api/positions', () => ({ listPositions: vi.fn() }))

const positions = [
  { id: 'p-backend', name: '백엔드', companyName: '에이회사', postingTitle: '공채' },
  { id: 'p-data', name: '데이터', companyName: '에이회사', postingTitle: '공채' },
  { id: 'p-front', name: '프론트', companyName: '비회사', postingTitle: '수시' },
] as unknown as Position[]

const item = {
  id: '70000000-0000-0000-0000-000000000001',
  resumeIds: ['80000000-0000-0000-0000-000000000001'],
  question: '협업 경험을 설명해 주세요.',
  answer: '역할을 나누고 진행했습니다.',
  createdAt: '2026-09-16T00:00:00Z',
  updatedAt: '2026-09-16T00:00:00Z',
}
const resumes = [
  { id: item.resumeIds[0], name: '백엔드 이력서', createdAt: item.createdAt, updatedAt: item.updatedAt },
  { id: 'frontend', name: '프런트 이력서', createdAt: item.createdAt, updatedAt: '2026-09-17T00:00:00Z' },
  { id: 'data', name: '데이터 이력서', createdAt: item.createdAt, updatedAt: '2026-09-18T00:00:00Z' },
]
const mountBoard = async () => {
  const wrapper = mount(SelfIntroductionBoard, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('자기소개 문항과 연결 이력서', () => {
  beforeEach(() => {
    vi.mocked(api.listSelfIntroductions).mockReset().mockResolvedValue([item])
    vi.mocked(listResumes).mockReset().mockResolvedValue(resumes)
    vi.mocked(listPositions).mockReset().mockResolvedValue(positions)
    vi.mocked(api.confirmSelfIntroduction).mockReset()
    vi.mocked(api.createSelfIntroduction).mockReset().mockResolvedValue(item)
    vi.mocked(api.updateSelfIntroduction).mockReset().mockResolvedValue(item)
  })

  it('검색어만 표시하고 HTML은 문자열로 남긴다', () => {
    expect(highlightMatches('<b>협업</b> 경험', '협업')).toEqual([
      { text: '<b>', matched: false },
      { text: '협업', matched: true },
      { text: '</b> 경험', matched: false },
    ])
  })

  it('문항을 더블클릭하면 기존 값으로 편집기를 연다', async () => {
    const wrapper = mount(SelfIntroductionBoard)
    await flushPromises()

    await wrapper.get('.intro-card h2').trigger('dblclick')

    expect(wrapper.get('.intro-editor h2').text()).toBe('문항 수정')
    expect(wrapper.get<HTMLTextAreaElement>('.intro-editor textarea').element.value).toBe(item.question)
  })

  it('처음에는 목록을 접고 검색창 클릭·포커스 시 미선택 이력서를 최근 수정순으로 펼친다', async () => {
    const wrapper = await mountBoard()
    await wrapper.get('.page-head > button').trigger('click')
    const input = wrapper.get('[role="combobox"]')
    expect(input.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    expect(wrapper.get('.selected-resumes .chip').text()).toContain('백엔드 이력서')
    await input.trigger('click')
    expect(wrapper.findAll('[role="option"]').map((option) => option.text())).toEqual(['데이터 이력서', '프런트 이력서'])
    expect(input.attributes('aria-controls')).toBe(wrapper.get('[role="listbox"]').attributes('id'))
    await input.trigger('keydown', { key: 'Escape' })
    await input.trigger('focus')
    expect(input.attributes('aria-expanded')).toBe('true')
  })

  it('이름으로 검색하고 선택 칩은 검색 결과와 무관하게 유지한다', async () => {
    const wrapper = await mountBoard()
    await wrapper.get('.page-head > button').trigger('click')
    const input = wrapper.get('[role="combobox"]')
    await input.setValue('데이터')
    expect(wrapper.findAll('[role="option"]').map((option) => option.text())).toEqual(['데이터 이력서'])
    expect(wrapper.get('.selected-resumes .chip').text()).toContain('백엔드 이력서')
    await input.setValue('없는 이름')
    expect(wrapper.get('[role="status"]').text()).toBe('검색 결과가 없습니다.')
    await input.setValue('')
    expect(wrapper.findAll('[role="option"]')).toHaveLength(2)
  })

  it('이력서를 연속 연결·해제해도 초안만 바꾸고 신규 저장에 선택된 ID를 보낸다', async () => {
    const wrapper = await mountBoard()
    await wrapper.get('.page-head > button').trigger('click')
    const input = wrapper.get<HTMLInputElement>('[role="combobox"]')
    await input.setValue('데이터')
    await wrapper.get('[role="option"]').trigger('click')
    expect(input.element.value).toBe('')
    expect(input.attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).toBe(input.element)
    expect(wrapper.findAll('.selected-resumes .chip')).toHaveLength(2)
    expect(wrapper.findAll('[role="option"]').map((option) => option.text())).toEqual(['프런트 이력서'])
    expect(api.createSelfIntroduction).not.toHaveBeenCalled()
    expect(api.updateSelfIntroduction).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="백엔드 이력서 연결 해제"]').trigger('click')
    const fields = wrapper.findAll('.intro-editor textarea')
    await fields[0].setValue('  지원 동기  ')
    await fields[1].setValue('작성한 답변')
    await wrapper.get('.intro-editor form').trigger('submit')
    await flushPromises()
    expect(api.createSelfIntroduction).toHaveBeenCalledWith({
      resumeIds: ['data'], question: '지원 동기', answer: '작성한 답변', positionIds: [], questionTypes: [],
    })
    expect(wrapper.find('.intro-editor').exists()).toBe(false)
  })

  it('수정 저장도 기존 연결·질문·답변을 보존하고, 수정은 연결 0개도 저장할 수 있다', async () => {
    const wrapper = await mountBoard()
    await wrapper.get('.intro-card .button').trigger('click')
    await wrapper.get('[aria-label="백엔드 이력서 연결 해제"]').trigger('click')
    // 생성은 이력서 또는 직무 1개 이상이지만 수정은 0개도 된다(직무가 지워져 연결이 없어진 문항도 고칠 수 있게).
    expect(wrapper.get('.intro-editor button[type="submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[role="combobox"]').setValue('데이터')
    await wrapper.get('[role="option"]').trigger('click')
    await wrapper.get('.intro-editor form').trigger('submit')
    await flushPromises()
    expect(api.updateSelfIntroduction).toHaveBeenCalledWith(item.id, {
      resumeIds: ['data'], question: item.question, answer: item.answer, positionIds: [], questionTypes: [],
    })
    expect(item.resumeIds).toEqual([resumes[0].id])
  })

  it('직무만 골라도 새 문항을 만들고, 이력서도 직무도 없으면 생성을 막는다', async () => {
    const wrapper = await mountBoard()
    await wrapper.get('.page-head > button').trigger('click')
    await wrapper.get('[aria-label="백엔드 이력서 연결 해제"]').trigger('click')
    await wrapper.get('.intro-editor textarea').setValue('지원 동기')
    expect(wrapper.get('.intro-editor button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('select[aria-label="지원 직무 추가"]').setValue('p-front')
    expect(wrapper.get('.intro-editor button[type="submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('select[aria-label="문항 유형 추가"]').setValue('MOTIVATION')
    expect(wrapper.get('.intro-links').text()).toContain('비회사 · 프론트')
    await wrapper.get('.intro-editor form').trigger('submit')
    await flushPromises()
    expect(api.createSelfIntroduction).toHaveBeenCalledWith({
      resumeIds: [], question: '지원 동기', answer: '', positionIds: ['p-front'], questionTypes: ['MOTIVATION'],
    })
  })

  it('출처 없는 답변에는 연결 이력서들의 직무 전부를 "어느 지원용?" 후보로 보여 주고 한 번에 고른다', async () => {
    vi.mocked(listResumes).mockResolvedValue([
      { ...resumes[0], positionIds: ['p-backend', 'p-data'] },
      { ...resumes[1], positionIds: ['p-front'] },
      resumes[2],
    ])
    const wrapper = await mountBoard()
    await wrapper.get('.intro-card .button').trigger('click')
    const candidates = () => wrapper.findAll('.link-candidates button').map((button) => button.text())
    expect(wrapper.get('.link-candidates').text()).toContain('어느 지원용?')
    expect(candidates()).toEqual(['+ 에이회사 · 백엔드', '+ 에이회사 · 데이터'])
    await wrapper.get('[role="combobox"]').setValue('프런트')
    await wrapper.get('[role="option"]').trigger('click')
    expect(candidates()).toEqual(['+ 에이회사 · 백엔드', '+ 에이회사 · 데이터', '+ 비회사 · 프론트'])
    await wrapper.findAll('.link-candidates button')[1].trigger('click')
    expect(wrapper.find('.link-candidates').exists()).toBe(false)
    await wrapper.get('.intro-editor form').trigger('submit')
    await flushPromises()
    expect(api.updateSelfIntroduction).toHaveBeenCalledWith(item.id, expect.objectContaining({ positionIds: ['p-data'] }))
  })

  it('AI 기록 칩은 ✓로 바로 확정하고 ✗로 연결에서 뺀다', async () => {
    const aiRecorded: SelfIntroduction = {
      ...item,
      positionLinks: [{ positionId: 'p-backend', source: 'AI' }],
      questionTypes: [{ code: 'MOTIVATION', source: 'AI' }],
    }
    vi.mocked(api.listSelfIntroductions).mockResolvedValue([aiRecorded])
    vi.mocked(api.confirmSelfIntroduction).mockResolvedValue({
      ...aiRecorded, positionLinks: [{ positionId: 'p-backend', source: 'USER' }],
    })
    const wrapper = await mountBoard()
    expect(wrapper.get('.intro-card').text()).toContain('에이회사 · 백엔드 · AI 기록')
    await wrapper.get('.intro-card .button').trigger('click')
    expect(wrapper.find('.link-candidates').exists()).toBe(false)
    await wrapper.get('[aria-label="에이회사 · 백엔드 확정"]').trigger('click')
    await flushPromises()
    expect(api.confirmSelfIntroduction).toHaveBeenCalledWith(item.id, { positionIds: ['p-backend'] })
    expect(wrapper.find('[aria-label="에이회사 · 백엔드 확정"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="지원동기 확정"]').exists()).toBe(true)
    await wrapper.get('[aria-label="지원동기 연결 해제"]').trigger('click')
    await wrapper.get('.intro-editor form').trigger('submit')
    await flushPromises()
    expect(api.updateSelfIntroduction).toHaveBeenCalledWith(item.id, expect.objectContaining({
      positionIds: ['p-backend'], questionTypes: [],
    }))
  })

  it('바깥 클릭·포커스·Esc·Tab으로 닫고 다시 입력해 연다', async () => {
    const wrapper = await mountBoard()
    await wrapper.get('.page-head > button').trigger('click')
    const input = wrapper.get('[role="combobox"]')
    await input.trigger('focus')
    await wrapper.get('.intro-editor textarea').trigger('pointerdown')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    for (const key of ['Escape', 'Tab']) {
      await input.trigger('click')
      await input.trigger('keydown', { key })
      expect(input.attributes('aria-expanded')).toBe('false')
    }
    await input.setValue('데이터')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(true)
    await wrapper.get('.intro-editor textarea').trigger('focusin')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
  })

  it('키보드 선택을 지원하고 검색 Enter가 문항 저장이나 한글 조합 중 선택을 일으키지 않는다', async () => {
    const wrapper = await mountBoard()
    await wrapper.get('.intro-card .button').trigger('click')
    const input = wrapper.get('[role="combobox"]')
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    input.element.dispatchEvent(enter)
    await flushPromises()
    expect(enter.defaultPrevented).toBe(true)
    expect(api.updateSelfIntroduction).not.toHaveBeenCalled()
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(input.attributes('aria-activedescendant')).toBe(wrapper.get('[role="option"]').attributes('id'))
    await input.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(wrapper.findAll('.selected-resumes .chip')).toHaveLength(1)
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.get('.resume-option.active').text()).toBe('프런트 이력서')
    await input.trigger('keydown', { key: 'ArrowUp' })
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.find('[aria-label="데이터 이력서 연결 해제"]').exists()).toBe(true)
    expect(api.updateSelfIntroduction).not.toHaveBeenCalled()
  })

  it('모두 선택함·이력서 없음 상태를 구분하고 삭제된 연결도 칩에서 해제한다', async () => {
    vi.mocked(listResumes).mockResolvedValue([resumes[0]])
    const wrapper = await mountBoard()
    await wrapper.get('.page-head > button').trigger('click')
    await wrapper.get('[role="combobox"]').trigger('click')
    expect(wrapper.get('[role="status"]').text()).toBe('추가할 이력서가 없습니다.')
    await wrapper.get('.intro-editor .button.secondary').trigger('click')
    vi.mocked(listResumes).mockResolvedValue([])
    vi.mocked(listPositions).mockResolvedValue([])
    const missing = await mountBoard()
    expect(missing.get('.page-head > button').attributes('disabled')).toBeDefined()
    await missing.get('.intro-card .button').trigger('click')
    await missing.get('[role="combobox"]').trigger('click')
    expect(missing.get('[role="status"]').text()).toBe('연결할 이력서가 없습니다.')
    await missing.get('[aria-label="삭제된 이력서 연결 해제"]').trigger('click')
    expect(missing.find('.selected-resumes').exists()).toBe(false)
  })

  it('취소·다른 문항 수정으로 전환하면 검색어와 열린 목록을 초기화한다', async () => {
    const wrapper = await mountBoard()
    await wrapper.get('.page-head > button').trigger('click')
    await wrapper.get('[role="combobox"]').setValue('데이터')
    await wrapper.get('.intro-editor .button.secondary').trigger('click')
    expect(wrapper.find('.intro-editor').exists()).toBe(false)
    await wrapper.get('.intro-card .button').trigger('click')
    const input = wrapper.get<HTMLInputElement>('[role="combobox"]')
    expect(input.element.value).toBe('')
    expect(input.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.selected-resumes .chip').text()).toContain('백엔드 이력서')
  })

  it('목록 개수를 자르지 않아 아래쪽 이력서도 선택할 수 있다', async () => {
    const many = Array.from({ length: 40 }, (_, index) => ({ ...resumes[0], id: `resume-${index}`, name: `이력서 ${index}` }))
    vi.mocked(listResumes).mockResolvedValue(many)
    const wrapper = await mountBoard()
    await wrapper.get('.page-head > button').trigger('click')
    await wrapper.get('[role="combobox"]').trigger('focus')
    expect(wrapper.findAll('[role="option"]')).toHaveLength(39)
    await wrapper.findAll('[role="option"]').at(-1)!.trigger('click')
    expect(wrapper.find('[aria-label="이력서 39 연결 해제"]').exists()).toBe(true)
  })
})
