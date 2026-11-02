import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeEditor from '../components/ResumeEditor.vue'
import * as api from '../api/experiences'
import { SECTIONS, emptyContent, emptyRow, type Resume, type ResumePayload, type Row, type SectionKey } from '../types/resume'
import type { ExperienceDetail, ExperienceLine } from '../types/experience'

vi.mock('../api/experiences', () => ({
  listExperiences: vi.fn(),
  getExperiences: vi.fn(),
  createExperience: vi.fn(),
}))
enableAutoUnmount(afterEach)

const line = (id: string, title: string, revision: number): ExperienceLine => ({
  id, kind: 'PROJECT', title, period: '2024.03~2024.08', revision, reviewed: true, skills: [], domains: [], inResumes: [], usedInSelfIntros: 0,
})
const detail = (id: string, title: string, kinds: ('INIT' | 'ADD' | 'CORRECT')[]): ExperienceDetail => ({
  id, kind: 'PROJECT', title, startYm: '2024.03', memo: null, version: kinds.length, revision: kinds.length, reviewed: true,
  reviewedRevision: kinds.length, dossierBasedOn: null, dossierStale: false, skills: ['Kafka', 'Spring'], domains: [],
  facts: {
    period: '2024.03~2024.08', role: '백엔드 리드',
    actions: [{ id: 'a1', text: '색인 파이프라인을 다시 짰다' }], results: [{ id: 'r1', text: 'p95 1.2초 → 0.4초' }],
  },
  dossier: null, rows: [], selfIntros: [],
  revisions: kinds.map((changeKind, i) => ({ revision: i + 1, changeKind, source: 'USER', createdAt: '' })),
})

/** e1 은 개정 1 을 쓴 행이 있고 그 뒤에 정정(CORRECT)이 있다 — '재검토 필요'. */
const search = detail('e1', '검색 개선', ['INIT', 'CORRECT'])
const batch = detail('e2', '배치 이관', ['INIT'])

function resume(rows: Record<string, unknown>[], extra: Partial<Resume> = {}): Resume {
  const content = emptyContent()
  content.projects = rows as Resume['content']['projects']
  return { id: 'resume', name: '백엔드', content, positionIds: [], version: 7, createdAt: '', updatedAt: '', ...extra }
}
const anchored = { name: '검색 개선', startYm: '2024.03', endYm: '2024.08', description: '기존 문구', experienceId: 'e1', experienceRevision: 1 }
const bare = { name: '사내 위키', startYm: '2023.01', description: '- 문서 정리\n- 검색 붙임', techStack: 'Vue, Spring' }

async function mountEditor(value: Resume) {
  const wrapper = mount(ResumeEditor, {
    props: { resume: value, saving: false, apiFieldErrors: {}, positions: [], questions: [] },
  })
  await flushPromises()
  return wrapper
}
const projectRows = (wrapper: Awaited<ReturnType<typeof mountEditor>>) => wrapper.findAll('.resume-section')
  .find((section) => section.text().startsWith('프로젝트 경험'))!
const savedPayload = (wrapper: Awaited<ReturnType<typeof mountEditor>>) =>
  wrapper.emitted('save')!.at(-1)![0] as ResumePayload

describe('이력서 편집기의 경험 연결', () => {
  beforeEach(() => {
    vi.mocked(api.listExperiences).mockReset().mockResolvedValue({
      experiences: [line('e1', '검색 개선', 2), line('e2', '배치 이관', 1)], unlinked: [], index: {} as never,
    })
    vi.mocked(api.getExperiences).mockReset().mockImplementation(async (ids) => [search, batch].filter((d) => ids.includes(d.id)))
    vi.mocked(api.createExperience).mockReset()
  })

  it('다른 칸만 고쳐 저장하면 앵커(experienceId·experienceRevision)가 payload 에 그대로 간다', async () => {
    const wrapper = await mountEditor(resume([anchored, bare]))
    const section = projectRows(wrapper)
    await section.findAll('.resume-row')[0].find('textarea').setValue('고친 개요')
    await wrapper.get('.resume-head .button.primary').trigger('click')
    const row = savedPayload(wrapper).content.projects[0]
    expect(row.experienceId).toBe('e1')
    expect(row.experienceRevision).toBe(1)
    expect(row.summary).toBe('고친 개요')
    // 미연결 행에는 앵커 키를 만들지 않는다.
    expect('experienceId' in savedPayload(wrapper).content.projects[1]).toBe(false)
    expect(savedPayload(wrapper).refreshExperienceIds).toBeUndefined()
  })

  it('정정 뒤의 행은 "재검토 필요", "원본으로 갱신"은 refreshExperienceIds 로 나가고 저장 응답 뒤 표시가 사라진다', async () => {
    const wrapper = await mountEditor(resume([anchored]))
    expect(projectRows(wrapper).text()).toContain('재검토 필요')
    await projectRows(wrapper).get('.resume-row__exp .button').trigger('click')
    expect(projectRows(wrapper).text()).toContain('저장하면 원본 개정으로 갱신')
    await wrapper.get('.resume-head .button.primary').trigger('click')
    expect(savedPayload(wrapper).refreshExperienceIds).toEqual(['e1'])
    // 개정은 클라이언트가 바꾸지 않는다 — 행에는 저장된 개정이 그대로 간다.
    expect(savedPayload(wrapper).content.projects[0].experienceRevision).toBe(1)

    await wrapper.setProps({ resume: resume([{ ...anchored, experienceRevision: 2 }], { version: 8, unlinkedWarnings: [] }) })
    await flushPromises()
    expect(projectRows(wrapper).text()).not.toContain('재검토 필요')
    expect(projectRows(wrapper).text()).not.toContain('저장하면 원본 개정으로 갱신')
  })

  it('"다른 경험에 연결"은 경험 id 를 바꾸고 개정을 비워 보낸다', async () => {
    const wrapper = await mountEditor(resume([anchored]))
    await projectRows(wrapper).get('select[aria-label="1행 다른 경험에 연결"]').setValue('e2')
    await wrapper.get('.resume-head .button.primary').trigger('click')
    const row = savedPayload(wrapper).content.projects[0]
    expect(row.experienceId).toBe('e2')
    expect('experienceRevision' in row).toBe(false)
    expect(row.description).toBe('기존 문구')
  })

  it('"경험에서 불러오기"는 사실로 중립 행을 만들고 experienceId 만 단다', async () => {
    const wrapper = await mountEditor(resume([]))
    await projectRows(wrapper).get('select[aria-label="경험에서 불러오기"]').setValue('e2')
    await flushPromises()
    await wrapper.get('.resume-head .button.primary').trigger('click')
    const row = savedPayload(wrapper).content.projects[0]
    expect(row).toMatchObject({
      name: '배치 이관', startYm: '2024.03', endYm: '2024.08', role: '백엔드 리드', techStack: 'Kafka, Spring',
      description: '- 색인 파이프라인을 다시 짰다', outcome: '- p95 1.2초 → 0.4초', experienceId: 'e2',
    })
    expect('experienceRevision' in row).toBe(false)
  })

  it('"경험으로 등록"은 행으로 경험 원본을 만들고 그 행에 앵커를 단다', async () => {
    vi.mocked(api.createExperience).mockResolvedValue({
      id: 'e3', version: 0, revision: 1, changeKind: 'INIT', needsReview: [], normalizedSkills: [], warnings: [],
    })
    const wrapper = await mountEditor(resume([bare]))
    await projectRows(wrapper).get('.resume-row__exp .button').trigger('click')
    await flushPromises()
    expect(api.createExperience).toHaveBeenCalledWith({
      kind: 'PROJECT', title: '사내 위키', startYm: '2023.01',
      facts: { period: '2023.01~', role: null, actions: [{ text: '문서 정리' }, { text: '검색 붙임' }], results: [] },
      skills: ['Vue', 'Spring'],
    })
    expect(wrapper.text()).toContain('이력서를 저장하면 이 행이 연결됩니다')
    await wrapper.get('.resume-head .button.primary').trigger('click')
    expect(savedPayload(wrapper).content.projects[0]).toMatchObject({ name: '사내 위키', experienceId: 'e3' })
  })

  it('저장 응답의 unlinkedWarnings 는 끊긴 경험 이름으로 알린다', async () => {
    const wrapper = await mountEditor(resume([anchored]))
    await wrapper.setProps({ resume: resume([], { version: 8, unlinkedWarnings: ['e1'] }) })
    await flushPromises()
    expect(wrapper.get('.page-notice .notice').text()).toContain('경험 연결이 끊긴 행이 있습니다: 검색 개선')
  })

  it('저장이 실패해 응답이 오지 않으면 "원본으로 갱신"이 남아 다시 저장할 때도 refreshExperienceIds 로 나간다', async () => {
    const value = resume([anchored])
    const wrapper = await mountEditor(value)
    await projectRows(wrapper).get('.resume-row__exp .button').trigger('click')
    await wrapper.get('.resume-head .button.primary').trigger('click')
    // 부모(ResumeBoard)는 실패하면 saving 만 되돌리고 resume 을 바꾸지 않는다.
    await wrapper.setProps({ saving: true })
    await wrapper.setProps({ saving: false })
    expect(projectRows(wrapper).text()).toContain('저장하면 원본 개정으로 갱신')
    await wrapper.get('.resume-head .button.primary').trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(2)
    expect(savedPayload(wrapper).refreshExperienceIds).toEqual(['e1'])
  })

  it('경험 원본을 못 불러와도 편집기는 열리고 경험 조작만 숨긴다', async () => {
    vi.mocked(api.listExperiences).mockRejectedValue(new Error('일시 오류'))
    const wrapper = await mountEditor(resume([anchored]))
    expect(wrapper.find('.resume-row__exp').exists()).toBe(false)
    expect(wrapper.find('.resume-name').exists()).toBe(true)
  })
})

describe('경험 앵커 섹션 4개', () => {
  const cases = [
    { key: 'projects', label: '프로젝트 경험', kind: 'PROJECT', titleKey: 'name', id: 'p1', title: '검색 개선' },
    { key: 'experiences', label: '경력사항', kind: 'CAREER', titleKey: 'company', id: 'c1', title: '잡사이트(인턴)' },
    { key: 'activities', label: '대내외활동', kind: 'ACTIVITY', titleKey: 'name', id: 'a1', title: '오픈소스 동아리' },
    { key: 'trainings', label: '교육이수', kind: 'TRAINING', titleKey: 'name', id: 't1', title: '백엔드 부트캠프' },
  ] as const
  const details = cases.map((item) => ({ ...detail(item.id, item.title, ['INIT']), kind: item.kind }))

  function resumeWith(key: SectionKey, rows: Row[]): Resume {
    const content = emptyContent()
    content[key] = rows
    return { id: 'resume', name: '백엔드', content, positionIds: [], version: 7, createdAt: '', updatedAt: '' }
  }
  const sectionOf = (wrapper: Awaited<ReturnType<typeof mountEditor>>, label: string) =>
    wrapper.findAll('.resume-section').find((section) => section.find('h2').text().startsWith(label))!

  beforeEach(() => {
    vi.mocked(api.listExperiences).mockReset().mockResolvedValue({
      experiences: cases.map((item) => ({ ...line(item.id, item.title, 1), kind: item.kind })), unlinked: [], index: {} as never,
    })
    vi.mocked(api.getExperiences).mockReset().mockImplementation(async (ids) => details.filter((d) => ids.includes(d.id)))
    vi.mocked(api.createExperience).mockReset().mockResolvedValue({
      id: 'new', version: 0, revision: 1, changeKind: 'INIT', needsReview: [], normalizedSkills: [], warnings: [],
    })
  })

  it.each(cases)('$label: 이 섹션 종류의 경험만 고르고, 불러온 행은 제목·사실·experienceId 만 갖는다', async (item) => {
    const wrapper = await mountEditor(resumeWith(item.key, []))
    const section = sectionOf(wrapper, item.label)
    const options = section.get('select[aria-label="경험에서 불러오기"]').findAll('option').map((option) => option.text())
    expect(options.slice(1)).toEqual([`${item.title} (2024.03~2024.08)`])
    await section.get('select[aria-label="경험에서 불러오기"]').setValue(item.id)
    await flushPromises()
    await wrapper.get('.resume-head .button.primary').trigger('click')
    const row = savedPayload(wrapper).content[item.key][0]
    expect(row[item.titleKey]).toBe(item.title)
    expect(row.experienceId).toBe(item.id)
    expect('experienceRevision' in row).toBe(false)
    expect(row.description).toContain('- 색인 파이프라인을 다시 짰다')
  })

  it.each(cases)('$label: 경험으로 등록은 이 섹션 종류로 만들고, 연결된 행은 다른 칸을 고쳐도 앵커를 지킨다', async (item) => {
    const bareRow: Row = { [item.titleKey]: '새 행', startYm: '2023.01', description: '- 한 일' }
    const wrapper = await mountEditor(resumeWith(item.key, [bareRow]))
    await sectionOf(wrapper, item.label).get('.resume-row__exp .button').trigger('click')
    await flushPromises()
    expect(api.createExperience).toHaveBeenCalledWith(expect.objectContaining({
      kind: item.kind, title: '새 행', facts: expect.objectContaining({ actions: [{ text: '한 일' }] }),
    }))

    const anchoredRow: Row = { [item.titleKey]: item.title, startYm: '2024.03', description: '기존', experienceId: item.id, experienceRevision: 1 }
    const other = await mountEditor(resumeWith(item.key, [anchoredRow]))
    await sectionOf(other, item.label).get('.resume-row textarea').setValue('고친 설명')
    await other.get('.resume-head .button.primary').trigger('click')
    expect(savedPayload(other).content[item.key][0]).toMatchObject({ experienceId: item.id, experienceRevision: 1 })
  })

  it('앵커 없는 섹션(학력·수상·자격·SW 역량)에는 경험 조작을 그리지 않는다', async () => {
    const content = emptyContent()
    for (const section of SECTIONS) content[section.key] = [emptyRow(section)]
    const wrapper = await mountEditor({ id: 'resume', name: '백엔드', content, positionIds: [], version: 7, createdAt: '', updatedAt: '' })
    for (const label of ['학력사항', '수상내역', '자격증', 'SW 역량']) {
      const section = sectionOf(wrapper, label)
      expect(section.find('select[aria-label="경험에서 불러오기"]').exists()).toBe(false)
      expect(section.find('.resume-row__exp').exists()).toBe(false)
    }
    expect(wrapper.findAll('select[aria-label="경험에서 불러오기"]')).toHaveLength(4)
  })
})
