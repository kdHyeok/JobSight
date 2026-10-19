import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PositionBoard from '../components/PositionBoard.vue'
import * as api from '../api/positions'
import type { Position } from '../types/position'

vi.mock('../api/positions', () => ({
  ApiClientError: class extends Error { fieldErrors = {} },
  listPositions: vi.fn(),
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
})
