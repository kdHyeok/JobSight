import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount, shallowMount } from '@vue/test-utils'
import ResumeSection from '../components/ResumeSection.vue'
import ResumeEditor from '../components/ResumeEditor.vue'
import { SECTIONS, emptyContent, emptyRow, fillContent } from '../types/resume'
import type { Resume, ResumeContent } from '../types/resume'
import type { Position } from '../types/position'

enableAutoUnmount(afterEach)

/** 백엔드 ResumeContent 와 키가 어긋나면 값이 조용히 사라진다. 여기서 모양을 고정한다. */
describe('이력서 문서 모양', () => {
  it('빈 문서는 모든 섹션을 빈 배열로 갖는다', () => {
    const content = emptyContent()
    expect(Object.keys(content).sort()).toEqual(['basic', ...SECTIONS.map((s) => s.key)].sort())
    for (const section of SECTIONS) expect(content[section.key]).toEqual([])
    expect(content.basic.name).toBe('')
  })

  it('서버의 null 칸을 빈 문자열로 채우고 빠진 키를 보탠다', () => {
    const partial = {
      basic: { name: '테스트', phone: null },
      educations: [{ school: '테스트 대학교', gpa: null }],
    } as unknown as ResumeContent
    const filled = fillContent(partial)
    expect(filled.basic.phone).toBe('')
    expect(filled.educations[0]).toEqual({ ...emptyRow(SECTIONS[0]), school: '테스트 대학교' })
    expect(filled.projects).toEqual([])
  })
})

/**
 * 경험 앵커(experienceId·experienceRevision)는 화면에 칸이 없지만 저장 payload 로 그대로 돌아가야 한다(설계 6.1).
 * fillRow 가 SECTIONS 의 칸만 복사하면 웹 저장마다 앵커가 지워져 서버 링커가 연결을 잃는다.
 */
describe('경험 앵커 통과', () => {
  const anchored = {
    ...emptyContent(),
    projects: [{ name: 'Planetory', role: '', experienceId: 'exp-1', experienceRevision: 3 }],
    experiences: [{ company: 'A사', experienceId: 'exp-2', experienceRevision: 1 }],
  } as unknown as ResumeContent

  it('fillContent 가 앵커 키를 그대로 남기고 앵커가 없는 행에는 키를 만들지 않는다', () => {
    const filled = fillContent(anchored)
    expect(filled.projects[0]).toMatchObject({ experienceId: 'exp-1', experienceRevision: 3 })
    expect(filled.experiences[0]).toMatchObject({ experienceId: 'exp-2', experienceRevision: 1 })
    expect(fillContent({ ...emptyContent(), projects: [{ name: 'x' }] }).projects[0]).not.toHaveProperty('experienceId')
  })

  it('로드 → 다른 칸 수정 → 저장 payload 에 두 키가 그대로 실린다', async () => {
    const wrapper = shallowMount(ResumeEditor, {
      props: {
        resume: { id: 'resume', name: '이력서', content: anchored, positionIds: [], createdAt: '', updatedAt: '' } as unknown as Resume,
        positions: [], saving: false, apiFieldErrors: {},
      },
    })
    const projects = wrapper.findAllComponents(ResumeSection).find((section) => section.props('section').key === 'projects')!
    const rows = projects.props('rows') as Record<string, unknown>[]
    projects.vm.$emit('update:rows', rows.map((row) => ({ ...row, role: '백엔드' })))
    await wrapper.vm.$nextTick()
    await wrapper.get('.page-tools .button.primary').trigger('click')

    const saved = wrapper.emitted('save')?.[0]?.[0] as { content: ResumeContent }
    expect(saved.content.projects[0]).toMatchObject({ role: '백엔드', experienceId: 'exp-1', experienceRevision: 3 })
    expect(saved.content.experiences[0]).toMatchObject({ experienceId: 'exp-2', experienceRevision: 1 })
  })
})

describe('지원 직무 선택', () => {
  const positions = [
    { id: 'old', name: '이전 직무', companyName: 'A기업', postingTitle: '백엔드 공고', updatedAt: '2026-09-01T00:00:00Z' },
    { id: 'selected', name: '선택 직무', companyName: 'B기업', postingTitle: '공고', updatedAt: '2026-09-16T00:00:00Z' },
    { id: 'new', name: '최신 직무', companyName: 'C기업', postingTitle: '데이터 공고', updatedAt: '2026-09-15T00:00:00Z' },
  ] as Position[]
  const mountEditor = (available = positions, selected = ['selected']) => shallowMount(ResumeEditor, {
    props: {
      resume: { id: 'resume', name: '이력서', content: emptyContent(), positionIds: selected, createdAt: '', updatedAt: '' } as Resume,
      positions: available, saving: false, apiFieldErrors: {},
    },
    attachTo: document.body,
  })

  it('처음에는 접혀 있고 검색창 클릭·포커스 시 미선택 직무 전체를 최근 수정순으로 펼친다', async () => {
    const wrapper = mountEditor()
    const input = wrapper.get('[role="combobox"]')

    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    expect(input.attributes('aria-expanded')).toBe('false')
    expect(wrapper.findAll('.selected-positions .chip')).toHaveLength(1)
    await input.trigger('click')

    expect(wrapper.findAll('.position-option strong').map((item) => item.text())).toEqual(['최신 직무', '이전 직무'])
    expect(input.attributes('aria-controls')).toBe(wrapper.get('[role="listbox"]').attributes('id'))
    await input.trigger('keydown', { key: 'Escape' })
    await input.trigger('focus')
    expect(input.attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('[role="option"]')).toHaveLength(2)
    expect(positions.map((item) => item.id)).toEqual(['old', 'selected', 'new'])
  })

  it('직무·기업·공고명으로 검색하고 결과 없음 상태를 구분한다', async () => {
    const wrapper = mountEditor()
    const input = wrapper.get('[role="combobox"]')
    for (const query of ['최신', 'c기업', '데이터']) {
      await input.setValue(query)
      expect(wrapper.findAll('.position-option strong').map((item) => item.text())).toEqual(['최신 직무'])
    }
    await input.setValue('찾을 수 없는 직무')
    expect(wrapper.find('[role="option"]').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toBe('검색 결과가 없습니다.')
    await input.setValue('')
    expect(wrapper.findAll('[role="option"]')).toHaveLength(2)
  })

  it('선택 후 목록을 열어 두고 검색어를 비워 연속 연결하며 칩 해제 결과를 저장한다', async () => {
    const wrapper = mountEditor()
    const input = wrapper.get<HTMLInputElement>('[role="combobox"]')
    await input.setValue('최신')
    await wrapper.get('[role="option"]').trigger('click')
    expect(input.element.value).toBe('')
    expect(input.attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).toBe(input.element)
    expect(wrapper.findAll('.selected-positions .chip')).toHaveLength(2)
    expect(wrapper.findAll('.position-option strong').map((item) => item.text())).toEqual(['이전 직무'])
    expect(wrapper.emitted('save')).toBeUndefined()

    await wrapper.get('[aria-label="선택 직무 연결 해제"]').trigger('click')
    await wrapper.get('.page-tools .button.primary').trigger('click')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ positionIds: ['new'] })
    expect(wrapper.props('resume').positionIds).toEqual(['selected'])
  })

  it('바깥 클릭·Esc·Tab·바깥으로 포커스 이동 시 닫고 다시 클릭하거나 입력하면 열린다', async () => {
    const wrapper = mountEditor()
    const input = wrapper.get('[role="combobox"]')
    await input.trigger('focus')
    await wrapper.get('.resume-section__head').trigger('pointerdown')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    for (const key of ['Escape', 'Tab']) {
      await input.trigger('click')
      await input.trigger('keydown', { key })
      expect(input.attributes('aria-expanded')).toBe('false')
    }
    await input.setValue('최신')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(true)
    await wrapper.get('input[aria-label="이력서 이름"]').trigger('focusin')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
  })

  it('키보드로 탐색·연결하고 한글 조합 중 Enter는 연결하지 않는다', async () => {
    const wrapper = mountEditor()
    const input = wrapper.get('[role="combobox"]')
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(input.attributes('aria-activedescendant')).toBe(wrapper.get('[role="option"]').attributes('id'))
    await input.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(wrapper.findAll('.selected-positions .chip')).toHaveLength(1)
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.get('.position-option.active strong').text()).toBe('이전 직무')
    await input.trigger('keydown', { key: 'ArrowUp' })
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.find('[aria-label="최신 직무 연결 해제"]').exists()).toBe(true)
    expect(input.attributes('aria-activedescendant')).toBeUndefined()
  })

  it('직무 없음·모두 선택함을 구분하고 빈 목록에서도 키보드 조작이 안전하다', async () => {
    const wrapper = mountEditor([], [])
    const input = wrapper.get('[role="combobox"]')
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.get('[role="status"]').text()).toBe('연결할 모집 직무가 없습니다.')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.findAll('.selected-positions .chip')).toHaveLength(0)
    await wrapper.setProps({ positions: [positions[1]], resume: { ...wrapper.props('resume'), positionIds: ['selected'] } })
    await input.trigger('click')
    expect(wrapper.get('[role="status"]').text()).toBe('추가할 모집 직무가 없습니다.')
  })

  it('스크롤로 끝까지 찾을 수 있도록 결과 개수를 자르지 않는다', async () => {
    const manyPositions = Array.from({ length: 40 }, (_, index) => ({ ...positions[0], id: `position-${index}`, name: `직무 ${index}` }))
    const wrapper = mountEditor(manyPositions, [])
    await wrapper.get('[role="combobox"]').trigger('focus')
    expect(wrapper.findAll('[role="option"]')).toHaveLength(40)
    await wrapper.findAll('[role="option"]').at(-1)!.trigger('click')
    expect(wrapper.find('[aria-label="직무 39 연결 해제"]').exists()).toBe(true)
  })

  it('다른 이력서를 받으면 검색어와 열린 목록을 초기화한다', async () => {
    const wrapper = mountEditor()
    const input = wrapper.get<HTMLInputElement>('[role="combobox"]')
    await input.setValue('최신')
    await wrapper.setProps({ resume: { ...wrapper.props('resume'), id: 'other', positionIds: [] } })
    expect(input.element.value).toBe('')
    expect(input.attributes('aria-expanded')).toBe('false')
    expect(wrapper.findAll('.selected-positions .chip')).toHaveLength(0)
  })
})

describe('ResumeSection', () => {
  const section = SECTIONS.find((s) => s.key === 'certificates')!
  const rows = [
    { name: 'SQLD', issuer: '기관 A', acquiredYm: '2026.03' },
    { name: '정보처리기사', issuer: '기관 B', acquiredYm: '2025.06' },
  ]
  const mountSection = () => mount(ResumeSection, { props: { section, rows } })

  it('행 추가는 빈 행을 뒤에 붙인 새 배열을 올린다', async () => {
    const wrapper = mountSection()
    await wrapper.get('.resume-section__head button').trigger('click')

    const next = wrapper.emitted('update:rows')?.[0]?.[0] as Record<string, string>[]
    expect(next).toHaveLength(3)
    expect(next[2]).toEqual(emptyRow(section))
    expect(rows).toHaveLength(2) // 원본은 건드리지 않는다
  })

  it('↓ 는 순서를 바꾸고 × 는 행을 지운다', async () => {
    const wrapper = mountSection()
    const firstRowButtons = wrapper.findAll('.resume-row')[0].findAll('.repeater__move button')

    await firstRowButtons[1].trigger('click') // ↓
    expect((wrapper.emitted('update:rows')?.[0]?.[0] as typeof rows).map((r) => r.name))
      .toEqual(['정보처리기사', 'SQLD'])

    await firstRowButtons[2].trigger('click') // ×
    expect((wrapper.emitted('update:rows')?.[1]?.[0] as typeof rows).map((r) => r.name))
      .toEqual(['정보처리기사'])
  })

  it('칸을 고치면 그 행만 바뀐 새 배열을 올린다', async () => {
    const wrapper = mountSection()
    await wrapper.findAll('.resume-row')[1].get('input').setValue('ADsP')

    const next = wrapper.emitted('update:rows')?.[0]?.[0] as typeof rows
    expect(next[1].name).toBe('ADsP')
    expect(next[0]).toEqual(rows[0])
  })
})

/** 설계 1.3 P11: 키 입력마다 직렬화하는 것은 편집 중인 문서 하나뿐이다. 저장본은 prop 이 바뀔 때만 직렬화한다. */
describe('변경됨 판정 비용', () => {
  it('행을 고칠 때 JSON.stringify 는 한 번이고, 원래 값으로 돌리면 변경됨이 사라진다', async () => {
    const wrapper = shallowMount(ResumeEditor, {
      props: {
        resume: { id: 'resume', name: '이력서', content: { ...emptyContent(), projects: [{ name: 'A' }] }, positionIds: ['b', 'a'], createdAt: '', updatedAt: '' } as unknown as Resume,
        positions: [], saving: false, apiFieldErrors: {},
      },
    })
    expect(wrapper.find('.resume-dirty').exists()).toBe(false)
    const projects = () => wrapper.findAllComponents(ResumeSection).find((section) => section.props('section').key === 'projects')!
    const original = projects().props('rows') as Record<string, unknown>[]

    const stringify = vi.spyOn(JSON, 'stringify')
    projects().vm.$emit('update:rows', original.map((row) => ({ ...row, role: '백엔드' })))
    await wrapper.vm.$nextTick()
    const calls = stringify.mock.calls.length
    stringify.mockRestore()

    expect(wrapper.find('.resume-dirty').exists()).toBe(true)
    expect(calls).toBe(1)

    projects().vm.$emit('update:rows', original)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.resume-dirty').exists()).toBe(false)
  })
})
