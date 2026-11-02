import { describe, expect, it, vi } from 'vitest'
import { request } from '../api/http'
import { listExperiences } from '../api/experiences'

vi.mock('../api/http', () => ({ request: vi.fn(), ApiClientError: class extends Error {} }))

const page = (n: number, complete: boolean) => ({
  experiences: [{ id: `e${n}` }], unlinkedRows: [], counts: {}, complete, nextCursor: complete ? null : n + 1,
})

describe('listExperiences', () => {
  it('쪽 수와 상관없이 complete 까지 이어 부른다(잘린 목록을 전체로 돌려주지 않는다)', async () => {
    vi.mocked(request).mockReset().mockImplementation(async (url) => {
      const n = Number(new URL(url, 'http://x').searchParams.get('cursor') ?? 0)
      return page(n, n === 24)
    })
    const result = await listExperiences()
    expect(result.experiences).toHaveLength(25)
    expect(result.index.complete).toBe(true)
  })

  it('complete 가 아닌데 커서가 나아가지 않으면 실패로 알린다', async () => {
    vi.mocked(request).mockReset().mockResolvedValue({ ...page(0, false), nextCursor: null })
    await expect(listExperiences()).rejects.toThrow('끝까지')
  })
})
