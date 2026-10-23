import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PluginGuide from '../components/PluginGuide.vue'
import { MCP_ENDPOINTS } from '../routes'

describe('플러그인 연결 가이드', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('서버 canonical URL과 GitHub 설치 명령만 표시하고 복사한다', async () => {
    const config = { mcpUrl: 'https://example.com/mcp' }
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => config })
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('fetch', fetch)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const page = mount(PluginGuide)
    await flushPromises()
    expect(fetch).toHaveBeenCalledWith(MCP_ENDPOINTS.config)
    expect(page.get('input').element.value).toBe(config.mcpUrl)
    expect(page.text()).toContain('위 MCP URL로 새 연결을 만들고 다시 인증')
    await page.get('button').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(config.mcpUrl)
    expect(page.get('[aria-live]').text()).toContain('복사했습니다')
    expect(page.find(`a[href="${MCP_ENDPOINTS.download}"]`).exists()).toBe(false)
    expect(page.text()).not.toContain('CIMD')
    expect(page.text()).not.toContain('jobsight.read')
    expect(page.text()).not.toContain('수동 OAuth')
    const command = page.get('.guide-command').text()
    expect(command).toContain('codex plugin marketplace add kdHyeok/JobSight --ref main')
    expect(command).toContain('codex plugin add jobsight@jobsight')
    expect(command).not.toContain('Invoke-Expression')
    await page.findAll('button').find(b => b.text() === '설치 명령 복사')!.trigger('click')
    expect(writeText).toHaveBeenLastCalledWith(command)
  })
  it('서버 오류는 URL을 추측하지 않고 재시도 안내한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
    const page = mount(PluginGuide)
    await flushPromises()
    expect(page.get('[role="alert"]').text()).toContain('배포 상태')
    expect(page.find('input').exists()).toBe(false)
  })
})
