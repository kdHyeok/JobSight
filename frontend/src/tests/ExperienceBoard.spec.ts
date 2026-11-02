import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ExperienceBoard from '../components/ExperienceBoard.vue'
import * as api from '../api/experiences'
import { ApiClientError } from '../api/http'
import type { ExperienceDetail, ExperienceLine, ImportPlan } from '../types/experience'

vi.mock('../api/experiences', () => ({
  listExperiences: vi.fn(),
  getExperience: vi.fn(),
  createExperience: vi.fn(),
  updateExperience: vi.fn(),
  reviewExperience: vi.fn(),
  deleteExperience: vi.fn(),
  getImportPlan: vi.fn(),
  applyImport: vi.fn(),
}))
enableAutoUnmount(afterEach)

const line: ExperienceLine = {
  id: 'e1', kind: 'PROJECT', title: '검색 개선', period: '2024.03~2024.08', revision: 3, reviewed: false,
  skills: ['Kafka'], domains: [], inResumes: [{ id: 'r1', name: '백엔드' }], usedInSelfIntros: 1,
}
const evidence = [{ id: 'ev1', sourceType: 'NOTE', quote: '회의록: p95 0.4초' }]
const detail = (patch: Partial<ExperienceDetail> = {}): ExperienceDetail => ({
  id: 'e1', kind: 'PROJECT', title: '검색 개선', startYm: '2024.03', memo: '메모', version: 5, revision: 3, reviewed: false,
  reviewedRevision: 1, dossierBasedOn: 1, dossierStale: true, skills: ['Kafka'], domains: [],
  facts: {
    period: '2024.03~2024.08', role: '백엔드',
    actions: [{ id: 'a1', text: '색인을 다시 짰다', evidenceIds: ['ev1'] }],
    results: [{ id: 'r1', text: 'p95 0.4초' }],
    evidence, unverified: [{ id: 'u1', text: '트래픽 2배' }],
  },
  dossier: null,
  rows: [{ resumeId: 'r1', resumeName: '백엔드', section: 'projects', index: 0, experienceRevision: 1, needsReview: true }],
  selfIntros: [{ id: 's1', question: '협업 경험', experienceRevision: 2, trust: 'AI 기록', needsReview: false }],
  revisions: [
    { revision: 1, changeKind: 'INIT', source: 'USER', createdAt: '' },
    { revision: 2, changeKind: 'CORRECT', source: 'AI', createdAt: '' },
    { revision: 3, changeKind: 'ADD', source: 'AI', createdAt: '' },
  ],
  ...patch,
})
const saved = { id: 'e1', version: 6, revision: 4, changeKind: 'CORRECT' as const, needsReview: [], normalizedSkills: [], warnings: [] }

async function mountBoard(focus: string | null = null) {
  const wrapper = mount(ExperienceBoard, { props: { focus }, attachTo: document.body })
  await flushPromises()
  return wrapper
}
const drawer = (wrapper: Awaited<ReturnType<typeof mountBoard>>) => wrapper.get('.drawer')
const button = (wrapper: Awaited<ReturnType<typeof mountBoard>>, label: string) =>
  wrapper.findAll('button').find((item) => item.text() === label)!

describe('경험 원본 화면', () => {
  beforeEach(() => {
    vi.mocked(api.listExperiences).mockReset().mockResolvedValue({
      experiences: [line], unlinked: [], index: { counts: { unlinkedRows: 2 } } as never,
    })
    vi.mocked(api.getExperience).mockReset().mockResolvedValue(detail())
    vi.mocked(api.updateExperience).mockReset().mockResolvedValue(saved)
    vi.mocked(api.reviewExperience).mockReset().mockResolvedValue(detail({ reviewed: true, reviewedRevision: 3 }))
    vi.mocked(api.deleteExperience).mockReset().mockResolvedValue({ id: 'e1', detachedSelfIntros: 0 })
    vi.mocked(api.getImportPlan).mockReset()
    vi.mocked(api.applyImport).mockReset()
  })

  it('목록은 AI 기록을, 상세는 소재집 갱신 필요·재검토 필요를 보이고 검토 확정은 보던 개정을 보낸다', async () => {
    const wrapper = await mountBoard()
    expect(wrapper.get('.card').text()).toContain('AI 기록')
    expect(wrapper.text()).toContain('이력서에서 가져오기 (2행)')
    await wrapper.get('.card').trigger('click')
    await flushPromises()
    expect(drawer(wrapper).text()).toContain('소재집 갱신 필요')
    expect(drawer(wrapper).text()).toContain('재검토 필요')
    expect(drawer(wrapper).text()).toContain('트래픽 2배')
    await button(wrapper, '검토 확정').trigger('click')
    await flushPromises()
    expect(api.reviewExperience).toHaveBeenCalledWith('e1', 3)
    expect(wrapper.findAll('button').some((item) => item.text() === '검토 확정')).toBe(false)
  })

  it('focus 로 연 경험의 이력서 행을 더블클릭하면 그 이력서를 연다', async () => {
    const wrapper = await mountBoard('e1')
    await drawer(wrapper).get('.mini-row').trigger('dblclick')
    expect(wrapper.emitted('openResume')).toEqual([['r1']])
  })

  it('메모만 고친 저장은 facts 를 보내지 않는다(AI 기록을 확정하지 않음)', async () => {
    const wrapper = await mountBoard('e1')
    await button(wrapper, '수정').trigger('click')
    await wrapper.get('#experience-form textarea[maxlength="2000"]:not([aria-label])').setValue('새 메모')
    await wrapper.get('#experience-form').trigger('submit')
    await flushPromises()
    const payload = vi.mocked(api.updateExperience).mock.calls[0][1]
    expect(payload.facts).toBeUndefined()
    expect(payload).toMatchObject({ memo: '새 메모', expectedVersion: 5 })
  })

  it('사실을 고치면 기존 항목 id·근거 id 를 지키고 새 항목은 id 없이, 근거·미확인은 받은 그대로 보낸다', async () => {
    const wrapper = await mountBoard('e1')
    await button(wrapper, '수정').trigger('click')
    await wrapper.get('[aria-label="한 일 1"]').setValue('색인 파이프라인을 다시 짰다')
    await button(wrapper, '+ 결과 추가').trigger('click')
    await wrapper.get('[aria-label="결과 2"]').setValue('장애 0건')
    await wrapper.get('#experience-form').trigger('submit')
    await flushPromises()
    const payload = vi.mocked(api.updateExperience).mock.calls[0][1]
    expect(payload.expectedVersion).toBe(5)
    expect(payload.facts).toMatchObject({
      actions: [{ id: 'a1', text: '색인 파이프라인을 다시 짰다', evidenceIds: ['ev1'] }],
      results: [{ id: 'r1', text: 'p95 0.4초' }, { text: '장애 0건' }],
      evidence, unverified: [{ id: 'u1', text: '트래픽 2배' }],
    })
    expect(payload.facts!.results![1].id).toBeUndefined()
  })

  it('중복 처리 흐름: 이력서가 쓰면 409 사유를 보여 주고, 자소서 근거만 남으면 끊고 지울지 물어 detach=true 로 지운다', async () => {
    const inUse = new ApiClientError(409, 'EXPERIENCE_IN_USE', '이력서가 쓰고 있는 경험입니다: [백엔드].')
    vi.mocked(api.deleteExperience).mockRejectedValueOnce(inUse)
    const wrapper = await mountBoard('e1')
    await button(wrapper, '삭제').trigger('click')
    await wrapper.get('.confirm-card .button.danger').trigger('click')
    await flushPromises()
    expect(drawer(wrapper).text()).toContain('이력서가 쓰고 있는 경험입니다')
    expect(api.deleteExperience).toHaveBeenLastCalledWith('e1', false)

    // 이력서 편집기에서 그 행을 "다른 경험에 연결"하고 저장했다. 이제 자소서 작성 근거만 남았다.
    vi.mocked(api.deleteExperience).mockRejectedValueOnce(new ApiClientError(409, 'EXPERIENCE_IN_USE', '자기소개 작성 근거로 쓰인 경험입니다.'))
    vi.mocked(api.getExperience).mockResolvedValue(detail({ rows: [] }))
    await button(wrapper, '삭제').trigger('click')
    await wrapper.get('.confirm-card .button.danger').trigger('click')
    await flushPromises()
    expect(wrapper.get('.confirm-card').text()).toContain('자기소개 작성 근거를 끊고 삭제할까요?')
    await wrapper.get('.confirm-card .button.danger').trigger('click')
    await flushPromises()
    expect(api.deleteExperience).toHaveBeenLastCalledWith('e1', true)
    expect(wrapper.find('.drawer').exists()).toBe(false)
    expect(wrapper.text()).toContain('"검색 개선" 경험을 삭제했습니다.')
  })
})

describe('경험 가져오기', () => {
  const plan: ImportPlan = {
    resumeVersions: { r1: 7 },
    groups: [
      { key: 'PROJECT|검색개선|202403', kind: 'PROJECT', title: '검색 개선', period: '2024.03~', mergeCandidates: ['PROJECT|검색개선|202404'],
        rows: [{ resumeId: 'r1', resumeName: '백엔드', section: 'projects', index: 0, resumeVersion: 7 }] },
      { key: 'PROJECT|검색개선|202404', kind: 'PROJECT', title: '검색 개선(2)', period: '2024.04~', mergeCandidates: [],
        rows: [{ resumeId: 'r1', resumeName: '백엔드', section: 'projects', index: 1, resumeVersion: 7 }] },
      { key: 'ACTIVITY|동아리|2023', kind: 'ACTIVITY', title: '동아리', period: null, mergeCandidates: [],
        rows: [{ resumeId: 'r1', resumeName: '백엔드', section: 'activities', index: 0, resumeVersion: 7 }] },
    ],
  }

  beforeEach(() => {
    vi.mocked(api.listExperiences).mockReset().mockResolvedValue({
      experiences: [line], unlinked: [], index: { counts: { unlinkedRows: 3 } } as never,
    })
    vi.mocked(api.getImportPlan).mockReset().mockResolvedValue(plan)
    vi.mocked(api.applyImport).mockReset().mockResolvedValue({ created: 1, linked: 1, skipped: 1, experienceIds: ['n1'] })
  })

  it('묶음마다 결정을 보내고 같은 경험일 수 있는 묶음을 알린다', async () => {
    const wrapper = await mountBoard()
    await button(wrapper, '이력서에서 가져오기 (3행)').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('같은 경험일 수 있음: 검색 개선(2)')
    await wrapper.get('[aria-label="검색 개선(2) 처리"]').setValue('LINK_TO')
    // 연결할 경험을 고르기 전에는 적용할 수 없다.
    expect(button(wrapper, '적용').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="검색 개선(2) 연결할 경험"]').setValue('e1')
    await wrapper.get('[aria-label="동아리 처리"]').setValue('SKIP')
    await button(wrapper, '적용').trigger('click')
    await flushPromises()
    expect(api.applyImport).toHaveBeenCalledWith({ r1: 7}, [
      { key: 'PROJECT|검색개선|202403', action: 'CREATE' },
      { key: 'PROJECT|검색개선|202404', action: 'LINK_TO', experienceId: 'e1' },
      { key: 'ACTIVITY|동아리|2023', action: 'SKIP' },
    ])
    expect(wrapper.text()).toContain('가져오기 완료: 새 경험 1개, 연결 1묶음, 건너뜀 1묶음.')
    expect(wrapper.find('[aria-label="경험 가져오기"]').exists()).toBe(false)
  })

  it('계획 뒤에 이력서가 바뀌어 409 면 계획을 다시 받는다', async () => {
    vi.mocked(api.applyImport).mockRejectedValueOnce(new ApiClientError(409, 'IMPORT_PLAN_STALE', '계획이 낡았습니다.'))
    const wrapper = await mountBoard()
    await button(wrapper, '이력서에서 가져오기 (3행)').trigger('click')
    await flushPromises()
    await button(wrapper, '적용').trigger('click')
    await flushPromises()
    expect(api.getImportPlan).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('새 계획으로 다시 골라 주세요')
    expect(wrapper.find('[aria-label="경험 가져오기"]').exists()).toBe(true)
  })
})
