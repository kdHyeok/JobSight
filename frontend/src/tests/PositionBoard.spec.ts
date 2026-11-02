import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PositionBoard from '../components/PositionBoard.vue'
import * as api from '../api/positions'
import type { Position } from '../types/position'

vi.mock('../api/positions', () => ({
  ApiClientError: class extends Error { fieldErrors = {} },
  listPositions: vi.fn(),
  getPosition: vi.fn(),
  updatePosition: vi.fn(),
  replacePositionReferences: vi.fn(),
  deletePosition: vi.fn(),
}))

vi.mock('../api/references', () => ({
  listReferences: vi.fn(() => Promise.resolve([])),
  createReference: vi.fn(),
  updateReference: vi.fn(),
  deleteReference: vi.fn(),
}))

function position(name: string, status: Position['status'], archived = false): Position {
  return {
    id: name,
    postingId: `posting-${name}`,
    postingTitle: `${name} 공고`,
    companyId: `company-${name}`,
    companyName: `${name} 회사`,
    deadlineAt: null,
    status,
    archived,
    name,
    team: null,
    role: null,
    responsibilities: null,
    impact: null,
    growth: null,
    experience: null,
    requiredSkills: null,
    preferredSkills: null,
    headcount: null,
    workLocation: null,
    memo: null,
    techStack: [],
    references: [],
    createdAt: '2026-09-16T00:00:00Z',
    updatedAt: '2026-09-16T00:00:00Z',
  }
}

describe('PositionBoard', () => {
  beforeEach(() => {
    vi.mocked(api.listPositions).mockReset().mockResolvedValue([
      position('관심 백엔드', 'INTERESTED'),
      position('작성 프론트', 'DRAFTING'),
      position('진행 데이터', 'SUBMITTED'),
      position('보관 인프라', 'INTERESTED', true),
    ])
  })

  it('부모 공고 상태로 탭을 나누고 현재 탭 안에서만 검색한다', async () => {
    const wrapper = mount(PositionBoard, { props: { focus: null } })
    await flushPromises()

    const tabs = wrapper.findAll('.tabs button')
    expect(tabs.map((button) => button.text())).toEqual([
      '관심 직무 2',
      '진행 중 1',
      '보관함 1',
    ])
    expect(wrapper.findAll('.card__title').map((title) => title.text()))
      .toEqual(['관심 백엔드', '작성 프론트'])

    await tabs[1].trigger('click')
    expect(wrapper.findAll('.card__title').map((title) => title.text())).toEqual(['진행 데이터'])

    await wrapper.get('input[type="search"]').setValue('보관')
    expect(wrapper.find('.card').exists()).toBe(false)
    expect(wrapper.text()).toContain('검색 결과가 없습니다.')

    await tabs[2].trigger('click')
    expect(wrapper.findAll('.card__title').map((title) => title.text())).toEqual(['보관 인프라'])
  })

  it('다른 화면에서 연 직무의 탭으로 함께 이동한다', async () => {
    const wrapper = mount(PositionBoard, { props: { focus: '보관 인프라' } })
    await flushPromises()

    const tabs = wrapper.findAll('.tabs button')
    expect(tabs[2].attributes('aria-selected')).toBe('true')
    expect(wrapper.findAll('.card__title').map((title) => title.text())).toEqual(['보관 인프라'])
  })

  /** 직무를 지우면 그 직무로 쓴 자소서의 직접 출처가 CASCADE 로 사라진다. 목록에는 수가 없어 상세를 한 번 불러 알린다. */
  it('삭제 확인창은 이 직무로 쓴 자소서 수를 알리고, 0 이면 문구를 붙이지 않는다', async () => {
    vi.mocked(api.getPosition).mockReset()
      .mockResolvedValueOnce({ ...position('관심 백엔드', 'INTERESTED'), linkedSelfIntroCount: 2 })
      .mockResolvedValueOnce({ ...position('관심 백엔드', 'INTERESTED'), linkedSelfIntroCount: 0 })
    const wrapper = mount(PositionBoard, { props: { focus: '관심 백엔드' } })
    await flushPromises()

    await wrapper.get('.drawer__actions .danger').trigger('click')
    await flushPromises()
    expect(api.getPosition).toHaveBeenCalledWith('관심 백엔드')
    expect(wrapper.get('.confirm-card').text())
      .toContain('이 직무로 쓴 자소서 2개의 직접 출처 연결이 함께 사라집니다(답변은 남습니다).')

    await wrapper.get('.confirm-card .button.secondary').trigger('click')
    await wrapper.get('.drawer__actions .danger').trigger('click')
    await flushPromises()
    expect(wrapper.get('.confirm-card').text()).not.toContain('자소서')
  })

  /** 수를 받기 전에 확정하면 경고 없이 지워진다. 못 받으면 0 이 아니라 "확인하지 못했다"를 알린다. */
  it('자소서 수를 받기 전에는 삭제를 확정할 수 없고, 못 받으면 일반 경고를 붙인다', async () => {
    let fail!: (error: Error) => void
    vi.mocked(api.getPosition).mockReset().mockReturnValueOnce(new Promise((_, reject) => { fail = reject }))
    const wrapper = mount(PositionBoard, { props: { focus: '관심 백엔드' } })
    await flushPromises()

    await wrapper.get('.drawer__actions .danger').trigger('click')
    expect(wrapper.get('.confirm-card .button.danger').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.confirm-card .button.secondary').attributes('disabled')).toBeUndefined()

    fail(new Error('네트워크'))
    await flushPromises()
    expect(wrapper.get('.confirm-card .button.danger').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('.confirm-card').text()).toContain('이 직무로 쓴 자소서 수를 확인하지 못했습니다')
  })
})
