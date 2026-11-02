import { enableAutoUnmount, flushPromises, shallowMount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../api/http'
import { getResume, updateResume } from '../api/resumes'
import ResumeBoard from '../components/ResumeBoard.vue'
import ResumeEditor from '../components/ResumeEditor.vue'
import { API } from '../routes'
import { emptyContent, type Resume, type ResumePayload } from '../types/resume'

vi.mock('../api/http', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/http')>(), request: vi.fn(),
}))
vi.mock('../api/positions', () => ({ listPositions: vi.fn(async () => []), listPositionOptions: vi.fn(async () => []) }))
vi.mock('../api/self-introductions', () => ({ listSelfIntroductions: vi.fn(async () => []) }))
enableAutoUnmount(afterEach)

function deferred() {
  let resolve!: () => void
  let reject!: (reason: Error) => void
  const promise = new Promise<void>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
const payload = (name: string): ResumePayload => ({ name, content: emptyContent(), positionIds: [] })

describe('저장 중인 이력서 재조회', () => {
  let firstSave: ReturnType<typeof deferred>
  let stored: Record<string, Resume>
  let writes: number

  beforeEach(() => {
    firstSave = deferred()
    writes = 0
    stored = Object.fromEntries(['a', 'b'].map((id) => [id, {
      id, ...payload(id.toUpperCase()), version: 0, createdAt: '', updatedAt: '',
    }]))
    vi.mocked(request).mockReset().mockImplementation(async (path, init) => {
      const id = path === API.resume('a') ? 'a' : 'b'
      if (init?.method === 'PUT') {
        const { expectedVersion, ...body } = JSON.parse(init.body as string) as ResumePayload
        if (++writes === 1) await firstSave.promise
        // 서버처럼 예전 버전으로 온 저장은 거절하고, 받은 저장마다 버전을 올린다(docs/resumes.md 불변 조건 8).
        if (expectedVersion !== undefined && expectedVersion !== stored[id]!.version) throw new Error('RESUME_STALE')
        stored[id] = { ...stored[id]!, ...body, version: stored[id]!.version + 1 }
      }
      return copy(stored[id])
    })
  })

  it('A 저장 중 B 조회는 진행하고, A 복귀와 재저장은 첫 수정이 반영된 문서로 한다', async () => {
    const wrapper = shallowMount(ResumeBoard, { props: { focus: 'a' } })
    await flushPromises()
    wrapper.findComponent(ResumeEditor).vm.$emit('save', payload('A 첫 수정'))
    await wrapper.setProps({ focus: 'b' })
    await flushPromises()
    expect(wrapper.findComponent(ResumeEditor).props('resume').id).toBe('b')

    await wrapper.setProps({ focus: 'a' })
    await flushPromises()
    expect(wrapper.findComponent(ResumeEditor).exists()).toBe(false)
    expect(vi.mocked(request).mock.calls.filter(([path, init]) => path === API.resume('a') && !init)).toHaveLength(1)

    firstSave.resolve()
    await flushPromises()
    const editor = wrapper.findComponent(ResumeEditor)
    expect(editor.props('resume').name).toBe('A 첫 수정')
    const second = copy(editor.props('resume')) as Resume
    second.content.projects = [{ name: '새 프로젝트' }]
    editor.vm.$emit('save', second)
    await flushPromises()
    expect(stored.a!.name).toBe('A 첫 수정')
    expect(stored.a!.content.projects[0]?.name).toBe('새 프로젝트')
  })

  it('편집 컴포넌트가 다시 만들어져도 같은 id의 저장 완료 후에 읽는다', async () => {
    const first = shallowMount(ResumeBoard, { props: { focus: 'a' } })
    await flushPromises()
    first.findComponent(ResumeEditor).vm.$emit('save', payload('재진입 전 저장'))
    first.unmount()
    const next = shallowMount(ResumeBoard, { props: { focus: 'a' } })
    await flushPromises()
    expect(next.findComponent(ResumeEditor).exists()).toBe(false)
    expect(vi.mocked(request).mock.calls.filter(([path, init]) => path === API.resume('a') && !init)).toHaveLength(1)
    firstSave.resolve()
    await flushPromises()
    expect(next.findComponent(ResumeEditor).props('resume').name).toBe('재진입 전 저장')
  })

  it('저장이 실패해도 재조회와 다음 저장을 막지 않는다', async () => {
    const saving = updateResume('a', payload('실패할 저장'))
    const failed = expect(saving).rejects.toThrow('저장 실패')
    const reading = getResume('a')
    firstSave.reject(new Error('저장 실패'))
    await failed
    expect((await reading).name).toBe('A')
    expect((await updateResume('a', payload('다시 저장'))).name).toBe('다시 저장')
    expect((await getResume('a')).name).toBe('다시 저장')
  })

  it('같은 id의 연속 저장과 그 뒤 조회는 요청 순서를 유지한다', async () => {
    // 두 저장 모두 같은 읽기(version 0)에서 출발해도, 둘째 저장은 첫 저장이 올린 버전을 이어받아 거절되지 않는다.
    const first = updateResume('a', { ...payload('첫 저장'), expectedVersion: 0 })
    const second = updateResume('a', { ...payload('둘째 저장'), expectedVersion: 0 })
    const reading = getResume('a')
    await flushPromises()
    expect(writes).toBe(1)
    firstSave.resolve()
    await Promise.all([first, second])
    expect((await reading).name).toBe('둘째 저장')
    expect(stored.a!.name).toBe('둘째 저장')
    expect(stored.a!.version).toBe(2)
  })

  it('다른 곳에서 먼저 저장했으면 예전 버전의 저장은 덮어쓰지 않는다', async () => {
    firstSave.resolve()
    stored.a = { ...stored.a!, name: 'AI 가 고친 이름', version: 3 }
    await expect(updateResume('a', { ...payload('예전 화면의 저장'), expectedVersion: 0 })).rejects.toThrow('RESUME_STALE')
    expect(stored.a!.name).toBe('AI 가 고친 이름')
  })
})
