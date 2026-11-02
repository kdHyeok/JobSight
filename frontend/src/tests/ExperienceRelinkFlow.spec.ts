import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ExperienceBoard from '../components/ExperienceBoard.vue'
import ResumeEditor from '../components/ResumeEditor.vue'
import * as api from '../api/experiences'
import { ApiClientError } from '../api/http'
import { emptyContent, type Resume, type ResumePayload, type SectionKey } from '../types/resume'
import { SECTION_KINDS, type ExperienceDetail, type ExperienceKind } from '../types/experience'

/**
 * 설계 7절 4b 검증 "다른 경험에 연결 → 쓰지 않는 경험 삭제 흐름이 끝까지".
 * 두 화면이 같은 가짜 서버를 본다. 이력서 저장은 서버 규칙(ResumeExperienceLinker)대로 앵커를 확정하고,
 * 경험 상세의 rows·삭제 판정은 그 저장 결과에서 계산한다 — 한쪽만 맞아서는 통과하지 못한다.
 */
vi.mock('../api/experiences', () => ({
  listExperiences: vi.fn(),
  getExperience: vi.fn(),
  getExperiences: vi.fn(),
  createExperience: vi.fn(),
  updateExperience: vi.fn(),
  reviewExperience: vi.fn(),
  deleteExperience: vi.fn(),
  getImportPlan: vi.fn(),
  applyImport: vi.fn(),
}))
enableAutoUnmount(afterEach)

/** 화면이 넘긴 값은 Vue 프록시라 structuredClone 이 안 된다. JSON 왕복이 곧 HTTP 직렬화다. */
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))

interface FakeExperience { id: string; kind: ExperienceKind; title: string; revision: number; selfIntros: string[] }

function fakeServer() {
  const experiences = new Map<string, FakeExperience>([
    ['e1', { id: 'e1', kind: 'PROJECT', title: '검색 개선', revision: 3, selfIntros: ['협업 경험'] }],
    ['e2', { id: 'e2', kind: 'PROJECT', title: '검색 개선(중복)', revision: 1, selfIntros: [] }],
  ])
  const content = emptyContent()
  content.projects = [{ name: '검색 개선', description: '기존 문구', experienceId: 'e1', experienceRevision: 3 }]
  const resume: Resume = { id: 'r1', name: '백엔드', content, positionIds: [], version: 7, createdAt: '', updatedAt: '' }

  const anchoredSections = Object.keys(SECTION_KINDS) as SectionKey[]
  const rowsOf = (id: string) => anchoredSections
    .flatMap((section) => resume.content[section].map((row, index) => ({ row, section, index })))
    .filter(({ row }) => row.experienceId === id)

  const detail = (item: FakeExperience): ExperienceDetail => ({
    id: item.id, kind: item.kind, title: item.title, startYm: null, memo: null, version: item.revision, revision: item.revision,
    reviewed: true, reviewedRevision: item.revision, dossierBasedOn: null, dossierStale: false, skills: [], domains: [],
    facts: null, dossier: null,
    rows: rowsOf(item.id).map(({ row, section, index }) => ({
      resumeId: resume.id, resumeName: resume.name, section, index, experienceRevision: row.experienceRevision as number, needsReview: false,
    })),
    selfIntros: item.selfIntros.map((question, i) => ({ id: `s${i}`, question, experienceRevision: 1, trust: '직접 입력', needsReview: false })),
    revisions: [],
  })

  /** PUT /resumes/{id} 의 앵커 확정(ResumeExperienceLinker 1차 규칙). */
  function saveResume(payload: ResumePayload) {
    const kept = new Map(anchoredSections.flatMap((section) => resume.content[section])
      .filter((row) => row.experienceId).map((row) => [row.experienceId as string, row.experienceRevision as number]))
    const seen = new Set<string>()
    const next = clone(payload.content)
    for (const section of anchoredSections) {
      for (const row of next[section]) {
        const kind = SECTION_KINDS[section]
        const id = row.experienceId as string | undefined
        if (!id) continue
        const head = experiences.get(id)
        if (!head) throw new ApiClientError(404, 'NOT_FOUND', id)
        if (head.kind !== kind) throw new ApiClientError(400, 'EXPERIENCE_SECTION_MISMATCH', id)
        if (seen.has(id)) throw new ApiClientError(400, 'EXPERIENCE_ROW_DUPLICATE', id)
        seen.add(id)
        const sent = row.experienceRevision as number | undefined
        const stored = kept.get(id)
        if (payload.refreshExperienceIds?.includes(id)) row.experienceRevision = head.revision
        else if (sent === undefined) row.experienceRevision = stored ?? head.revision
        else if (stored !== undefined ? sent !== stored : sent < 1 || sent > head.revision) {
          throw new ApiClientError(400, 'EXPERIENCE_REVISION_MISMATCH', id)
        }
      }
    }
    resume.content = next
    resume.version += 1
    return { ...resume, content: clone(next) }
  }

  vi.mocked(api.listExperiences).mockImplementation(async () => ({
    experiences: [...experiences.values()].map((item) => ({
      id: item.id, kind: item.kind, title: item.title, period: null, revision: item.revision, reviewed: true,
      skills: [], domains: [], inResumes: rowsOf(item.id).length ? [{ id: resume.id, name: resume.name }] : [], usedInSelfIntros: item.selfIntros.length,
    })),
    unlinked: [], index: { counts: { unlinkedRows: 0 } } as never,
  }))
  vi.mocked(api.getExperience).mockImplementation(async (id) => {
    const item = experiences.get(id)
    if (!item) throw new ApiClientError(404, 'NOT_FOUND', id)
    return detail(item)
  })
  vi.mocked(api.getExperiences).mockImplementation(async (ids) => ids.flatMap((id) => {
    const item = experiences.get(id)
    return item ? [detail(item)] : []
  }))
  vi.mocked(api.deleteExperience).mockImplementation(async (id, detach) => {
    const item = experiences.get(id)
    if (!item) throw new ApiClientError(404, 'NOT_FOUND', id)
    if (rowsOf(id).length) throw new ApiClientError(409, 'EXPERIENCE_IN_USE', `이력서가 쓰고 있는 경험입니다: [${resume.name}].`)
    if (item.selfIntros.length && !detach) throw new ApiClientError(409, 'EXPERIENCE_IN_USE', '자기소개 작성 근거로 쓰인 경험입니다.')
    experiences.delete(id)
    return { id, detachedSelfIntros: detach ? item.selfIntros.length : 0 }
  })

  return { experiences, resume: () => ({ ...resume, content: clone(resume.content) }), saveResume }
}

const button = (wrapper: ReturnType<typeof mount>, label: string) => wrapper.findAll('button').find((item) => item.text() === label)!

describe('중복 경험 정리: 다른 경험에 연결 → 쓰지 않는 경험 삭제', () => {
  let server: ReturnType<typeof fakeServer>
  beforeEach(() => {
    vi.clearAllMocks()
    server = fakeServer()
  })

  async function deleteFromBoard(id: string) {
    const board = mount(ExperienceBoard, { props: { focus: id }, attachTo: document.body })
    await flushPromises()
    await button(board, '삭제').trigger('click')
    await board.get('.confirm-card .button.danger').trigger('click')
    await flushPromises()
    return board
  }

  it('행을 옮기기 전에는 이력서 이름으로 막히고, 옮겨 저장하면 근거를 끊고 지울 수 있다', async () => {
    const blocked = await deleteFromBoard('e1')
    expect(blocked.get('.drawer').text()).toContain('이력서가 쓰고 있는 경험입니다: [백엔드].')
    blocked.unmount()

    const editor = mount(ResumeEditor, {
      props: { resume: server.resume(), saving: false, apiFieldErrors: {}, positions: [], questions: [] },
    })
    await flushPromises()
    await editor.get('select[aria-label="1행 다른 경험에 연결"]').setValue('e2')
    await editor.get('.resume-head .button.primary').trigger('click')
    const payload = editor.emitted('save')!.at(-1)![0] as ResumePayload
    // 서버 규칙으로 저장한다. 개정 키가 남아 있으면 e2 에 없는 개정 3 이라 여기서 400 이 난다.
    await editor.setProps({ resume: server.saveResume(payload) })
    expect(server.resume().content.projects[0]).toMatchObject({ experienceId: 'e2', experienceRevision: 1, description: '기존 문구' })
    editor.unmount()

    const board = await deleteFromBoard('e1')
    expect(board.get('.drawer').text()).toContain('이 경험을 쓰는 이력서 행이 없습니다.')
    expect(board.get('.confirm-card').text()).toContain('자기소개 작성 근거를 끊고 삭제할까요?')
    await board.get('.confirm-card .button.danger').trigger('click')
    await flushPromises()
    expect(api.deleteExperience).toHaveBeenLastCalledWith('e1', true)
    expect(server.experiences.has('e1')).toBe(false)
    expect(board.text()).toContain('"검색 개선" 경험을 삭제했습니다.')
    expect(board.findAll('.card').map((card) => card.text())).toEqual([expect.stringContaining('검색 개선(중복)')])
  })
})
