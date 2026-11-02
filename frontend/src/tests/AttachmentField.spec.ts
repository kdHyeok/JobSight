import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AttachmentField from '../components/AttachmentField.vue'
import ResumeEditor from '../components/ResumeEditor.vue'
import { ApiClientError, request } from '../api/http'
import { API } from '../routes'
import type { Resume } from '../types/resume'
import { emptyContent } from '../types/resume'

// 컴포넌트가 어떤 API 함수를 거치든 실제로 나가는 요청을 본다. 삭제 요청이 다시 생기면 여기서 잡힌다.
vi.mock('../api/http', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/http')>(), request: vi.fn(),
}))

const NEW_ID = '11111111-1111-1111-1111-111111111111'
const OLD_ID = '22222222-2222-2222-2222-222222222222'

const meta = (id: string, filename = '정보처리기사.pdf') =>
  ({ id, filename, contentType: 'application/pdf', size: 20480 })

/** jsdom 의 file input 은 files 를 직접 못 넣는다. 선택된 상태를 흉내 낸다. */
async function choose(wrapper: ReturnType<typeof mount>, file: File) {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

const png = () => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'x.png', { type: 'image/png' })

const calls = () => vi.mocked(request).mock.calls.map(([path, init]) => `${init?.method ?? 'GET'} ${path}`)

describe('AttachmentField', () => {
  beforeEach(() => {
    vi.mocked(request).mockReset().mockImplementation(async (path, init) => {
      if (init?.method === 'POST') return meta(NEW_ID)
      if (path === API.attachmentMeta(OLD_ID)) return meta(OLD_ID, '이전.pdf')
      return undefined
    })
  })

  it('파일을 고르면 바로 올리고 새 id 를 알린다', async () => {
    const wrapper = mount(AttachmentField, { props: { id: '', label: '자격증 사본' } })
    await choose(wrapper, png())

    expect(calls()).toEqual([`POST ${API.attachments}`])
    expect(wrapper.emitted('update:id')?.[0]).toEqual([NEW_ID])
    expect(wrapper.text()).toContain('정보처리기사.pdf')
  })

  /** 복제한 이력서는 같은 첨부 id 를 가리킨다. 교체하면서 이전 파일을 지우면 원본 이력서의 파일이 사라진다(설계 1.4 T7). */
  it('교체해도 이전 파일을 지우지 않는다', async () => {
    const wrapper = mount(AttachmentField, { props: { id: OLD_ID, label: '졸업증' } })
    await flushPromises()
    await choose(wrapper, png())

    expect(wrapper.emitted('update:id')?.[0]).toEqual([NEW_ID])
    expect(calls().filter((call) => call.startsWith('DELETE'))).toEqual([])
  })

  it('제거는 값만 비우고 파일은 지우지 않는다', async () => {
    const wrapper = mount(AttachmentField, { props: { id: OLD_ID, label: '졸업증' } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '제거')!.trigger('click')
    await flushPromises()

    expect(wrapper.emitted('update:id')?.[0]).toEqual([''])
    expect(calls().filter((call) => call.startsWith('DELETE'))).toEqual([])
  })

  /** 이력서 응답에 함께 온 메타가 있으면 칸마다 다시 부르지 않는다. 공개 도메인에서 왕복 하나가 약 0.3초다(설계 1.3 P5). */
  it('받은 메타가 있으면 메타를 부르지 않는다', async () => {
    const wrapper = mount(AttachmentField, { props: { id: OLD_ID, label: '졸업증', preloaded: meta(OLD_ID, '받은.pdf') } })
    await flushPromises()

    expect(calls()).toEqual([])
    expect(wrapper.text()).toContain('받은.pdf')
  })

  it('받은 메타가 없으면 직접 부른다', async () => {
    const wrapper = mount(AttachmentField, { props: { id: OLD_ID, label: '졸업증' } })
    await flushPromises()

    expect(calls()).toEqual([`GET ${API.attachmentMeta(OLD_ID)}`])
    expect(wrapper.text()).toContain('이전.pdf')
  })

  it('서버가 거부하면 이유를 보여주고 값을 바꾸지 않는다', async () => {
    vi.mocked(request).mockRejectedValue(
      new ApiClientError(415, 'UNSUPPORTED_FILE_TYPE', 'jpg, png, pdf 만 첨부할 수 있습니다.'))
    const wrapper = mount(AttachmentField, { props: { id: '', label: '이수증' } })
    await choose(wrapper, png())

    expect(wrapper.text()).toContain('jpg, png, pdf 만 첨부할 수 있습니다.')
    expect(wrapper.emitted('update:id')).toBeUndefined()
  })
})

describe('이력서 편집기의 첨부 칸', () => {
  beforeEach(() => {
    vi.mocked(request).mockReset().mockResolvedValue(undefined)
  })

  it('이력서 응답의 attachments 로 기본정보·섹션 첨부 칸을 그리고 메타를 다시 부르지 않는다', async () => {
    const content = emptyContent()
    content.basic.portfolioFileId = OLD_ID
    content.certificates = [{ name: '정보처리기사', fileId: NEW_ID }]
    const resume: Resume = {
      id: 'resume', name: '이력서', content, positionIds: [], version: 0, createdAt: '', updatedAt: '',
      attachments: { [OLD_ID]: meta(OLD_ID, '포트폴리오.pdf'), [NEW_ID]: meta(NEW_ID, '자격증.pdf') },
    }

    const wrapper = mount(ResumeEditor, {
      props: { resume, saving: false, apiFieldErrors: {}, positions: [], questions: [] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('포트폴리오.pdf')
    expect(wrapper.text()).toContain('자격증.pdf')
    expect(calls()).toEqual([])
  })
})
