import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../api/http'
import { confirmSelfIntroduction, updateSelfIntroduction } from '../api/self-introductions'
import { API } from '../routes'
import type { SelfIntroduction, SelfIntroductionConfirmPayload, SelfIntroductionUpdatePayload } from '../types/self-introduction'

vi.mock('../api/http', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/http')>(), request: vi.fn(),
}))

const payload = (answer: string, expectedVersion: number): SelfIntroductionUpdatePayload =>
  ({ resumeIds: [], question: '지원 동기', answer, expectedVersion })

/** 서버처럼 예전 version 의 쓰기는 거절하고, 받은 쓰기마다 version 을 올린다(docs/self-introductions.md 불변 조건 12). */
describe('같은 자기소개 문항의 연속 저장', () => {
  let stored: SelfIntroduction
  let releaseFirst: () => void
  let writes: number

  beforeEach(() => {
    writes = 0
    stored = { id: 'intro', resumeIds: [], question: '지원 동기', answer: '처음', createdAt: '', updatedAt: '', version: 0 }
    const first = new Promise<void>((resolve) => { releaseFirst = resolve })
    vi.mocked(request).mockReset().mockImplementation(async (path, init) => {
      if (++writes === 1) await first
      if (path === API.selfIntroduction('intro')) {
        const { expectedVersion, question, answer } = JSON.parse(init!.body as string) as SelfIntroductionUpdatePayload
        if (expectedVersion !== stored.version) throw new Error('SELF_INTRO_STALE')
        stored = { ...stored, question, answer }
      }
      if (path === API.selfIntroductionConfirm('intro')) {
        const { expectedVersion } = JSON.parse(init!.body as string) as SelfIntroductionConfirmPayload
        if (expectedVersion !== stored.version) throw new Error('SELF_INTRO_STALE')
      }
      stored = { ...stored, version: stored.version + 1 }
      return { ...stored }
    })
  })

  it('같은 읽기에서 출발한 연속 저장은 앞 저장이 올린 version 을 이어받아 순서대로 저장된다', async () => {
    const first = updateSelfIntroduction('intro', payload('첫 저장', 0))
    const second = updateSelfIntroduction('intro', payload('둘째 저장', 0))
    await Promise.resolve()
    expect(writes).toBe(1)
    releaseFirst()
    await Promise.all([first, second])
    expect(stored.answer).toBe('둘째 저장')
    expect(stored.version).toBe(2)
  })

  it('확정(✓) 직후의 저장은 확정 응답의 version 을 이어받는다', async () => {
    const confirming = confirmSelfIntroduction('intro', { positionIds: ['p'], expectedVersion: 0 })
    const saving = updateSelfIntroduction('intro', payload('확정 뒤 저장', 0))
    await Promise.resolve()
    expect(writes).toBe(1)
    releaseFirst()
    await confirming
    expect((await saving).answer).toBe('확정 뒤 저장')
    const sent = JSON.parse(vi.mocked(request).mock.calls[1][1]!.body as string) as SelfIntroductionUpdatePayload
    expect(sent.expectedVersion).toBe(1)
    expect(stored.version).toBe(2)
  })

  it('다른 곳에서 먼저 저장했으면 예전 version 의 확정(✓)도 version 을 올리지 않는다', async () => {
    releaseFirst()
    stored = { ...stored, answer: 'AI 가 고친 답변', version: 3 }
    await expect(confirmSelfIntroduction('intro', { positionIds: ['p'], expectedVersion: 0 })).rejects.toThrow('SELF_INTRO_STALE')
    expect(stored.version).toBe(3)
  })

  it('다른 곳에서 먼저 저장했으면 예전 version 의 저장은 덮어쓰지 않는다', async () => {
    releaseFirst()
    stored = { ...stored, answer: 'AI 가 고친 답변', version: 3 }
    await expect(updateSelfIntroduction('intro', payload('예전 화면의 저장', 0))).rejects.toThrow('SELF_INTRO_STALE')
    expect(stored.answer).toBe('AI 가 고친 답변')
  })
})
