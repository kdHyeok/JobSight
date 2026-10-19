import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import * as auth from '../api/auth'
import { GOOGLE_LOGIN_URL, ROUTES, googleLoginUrl } from '../routes'
import type { Me } from '../types/auth'

vi.mock('../api/auth', () => {
  class ApiClientError extends Error {
    fieldErrors = {}
  }
  return {
    ApiClientError,
    fetchMe: vi.fn(),
    fetchLoginOptions: vi.fn(),
    updateMyName: vi.fn(),
    logout: vi.fn(),
  }
})

vi.mock('../api/companies', () => {
  class ApiClientError extends Error {
    fieldErrors = {}
  }
  return {
    ApiClientError,
    listCompanies: vi.fn(() => Promise.resolve([])),
    getCompany: vi.fn(),
    createCompany: vi.fn(),
    updateCompany: vi.fn(),
    deleteCompany: vi.fn(),
  }
})

vi.mock('../api/postings', () => {
  class ApiClientError extends Error {
    fieldErrors = {}
  }
  return {
    ApiClientError,
    listPostings: vi.fn(() => Promise.resolve([])),
    listArchivedPostings: vi.fn(() => Promise.resolve([])),
    getPosting: vi.fn(),
    createPosting: vi.fn(),
    updatePosting: vi.fn(),
    changePostingStatus: vi.fn(),
    changeStepResult: vi.fn(),
    setPostingArchived: vi.fn(),
    deletePosting: vi.fn(),
  }
})

vi.mock('../api/positions', () => {
  class ApiClientError extends Error {
    fieldErrors = {}
  }
  return {
    ApiClientError,
    listPositions: vi.fn(() => Promise.resolve([])),
    getPosition: vi.fn(),
    updatePosition: vi.fn(),
    replacePositionReferences: vi.fn(),
    deletePosition: vi.fn(),
  }
})

vi.mock('../api/references', () => {
  class ApiClientError extends Error {
    fieldErrors = {}
  }
  return {
    ApiClientError,
    listReferences: vi.fn(() => Promise.resolve([])),
    createReference: vi.fn(),
    updateReference: vi.fn(),
    deleteReference: vi.fn(),
  }
})

vi.mock('../api/resumes', () => {
  class ApiClientError extends Error {
    fieldErrors = {}
  }
  return {
    ApiClientError,
    listResumes: vi.fn(() => Promise.resolve([])),
    getResume: vi.fn(),
    createResume: vi.fn(),
    updateResume: vi.fn(),
    copyResume: vi.fn(),
    deleteResume: vi.fn(),
  }
})

vi.mock('../api/self-introductions', () => ({
  listSelfIntroductions: vi.fn(() => Promise.resolve([])),
  createSelfIntroduction: vi.fn(),
  updateSelfIntroduction: vi.fn(),
  deleteSelfIntroduction: vi.fn(),
}))

vi.mock('../api/admin', () => ({
  listUsers: vi.fn(() => Promise.resolve([])),
  fetchSettings: vi.fn(() =>
    Promise.resolve({ autoApproveSignup: false, updatedAt: '2026-09-04T00:00:00Z' }),
  ),
  changeUserStatus: vi.fn(),
  changeUserRole: vi.fn(),
  changeUserName: vi.fn(),
  deleteUser: vi.fn(),
  updateAutoApproveSignup: vi.fn(),
}))

const anonymous: Me = { authenticated: false, id: null, email: null, displayName: null, role: null }
const member: Me = {
  authenticated: true,
  id: '20000000-0000-0000-0000-000000000001',
  email: 'member@example.com',
  displayName: '홍길동',
  role: 'USER',
}
const admin: Me = {
  authenticated: true,
  id: '20000000-0000-0000-0000-0000000000ad',
  email: 'admin@example.com',
  displayName: null,
  role: 'ADMIN',
}

function setPath(path: string) {
  window.history.replaceState({}, '', path)
}

describe('App 접근 제어', () => {
  it('플러그인 메뉴는 비로그인 상태에도 연결 가이드를 연다', async () => {
    vi.mocked(auth.fetchMe).mockResolvedValue(anonymous)
    const wrapper = mount(App, { global: { stubs: { PluginGuide: { template: '<main data-guide>연결 가이드</main>' } } } })
    await flushPromises()
    await wrapper.get('[aria-controls="site-menu"]').trigger('click')
    expect(wrapper.get('#site-menu').text()).not.toContain('관리자')
    const button = wrapper.findAll('#site-menu button').find(b => b.text() === '플러그인 연결 가이드')!
    await button.trigger('click')
    expect(window.location.pathname).toBe(ROUTES.plugin)
    expect(wrapper.find('[data-guide]').exists()).toBe(true)
    expect(wrapper.find('#site-menu').exists()).toBe(false)
    expect(wrapper.find('.auth-card').exists()).toBe(false)
  })
  beforeEach(() => {
    setPath(ROUTES.home)
    vi.mocked(auth.fetchLoginOptions).mockReset().mockResolvedValue({
      googleEnabled: true,
      autoApproveSignup: false,
    })
    vi.mocked(auth.fetchMe).mockReset()
    vi.mocked(auth.updateMyName).mockReset()
  })

  it('비로그인 상태에서는 Google 로그인 버튼만 보여준다', async () => {
    vi.mocked(auth.fetchMe).mockResolvedValue(anonymous)

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.find('.auth-card').exists()).toBe(true)
    expect(wrapper.find('.google-button').attributes('href')).toBe(GOOGLE_LOGIN_URL)
    // 비밀번호 로그인은 존재하지 않는다.
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(wrapper.find('.avatar').exists()).toBe(false)
  })

  it('Google 자격증명이 없으면 안내만 보여주고 버튼을 감춘다', async () => {
    vi.mocked(auth.fetchMe).mockResolvedValue(anonymous)
    vi.mocked(auth.fetchLoginOptions).mockResolvedValue({
      googleEnabled: false,
      autoApproveSignup: false,
    })

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.find('.google-button').exists()).toBe(false)
    expect(wrapper.text()).toContain('Google 로그인이 아직 설정되지 않았습니다.')
  })

  it('로그인 옵션 조회 실패를 Google 미설정으로 오인하지 않고 재시도한다', async () => {
    vi.mocked(auth.fetchMe).mockResolvedValue(anonymous)
    vi.mocked(auth.fetchLoginOptions)
      .mockRejectedValueOnce(new Error('Failed to fetch'))
      .mockResolvedValueOnce({ googleEnabled: true, autoApproveSignup: false })

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('로그인 설정을 확인하지 못했습니다.')
    expect(wrapper.text()).not.toContain('Google 로그인이 아직 설정되지 않았습니다.')
    expect(wrapper.find('.google-button').exists()).toBe(false)

    await wrapper.get('.auth-card .notice.error button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.google-button').exists()).toBe(true)
  })

  it('세션 확인이 실패하면 로그인 대신 연결 실패와 재시도만 보여준다', async () => {
    vi.mocked(auth.fetchMe).mockRejectedValueOnce(new Error('Failed to fetch')).mockResolvedValueOnce(anonymous)

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('서비스에 연결할 수 없습니다.')
    expect(wrapper.get('.notice.error button').text()).toBe('다시 시도')
    expect(wrapper.find('.auth-card').exists()).toBe(false)
    expect(wrapper.find('.google-button').exists()).toBe(false)

    await wrapper.get('.notice.error button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.auth-card').exists()).toBe(true)
  })

  it('localhost:8088 로그인은 세션 호스트인 127.0.0.1:8088에서 시작한다', () => {
    expect(googleLoginUrl({ hostname: 'localhost', port: '8088' })).toBe(
      'http://127.0.0.1:8088/oauth2/authorization/google',
    )
    expect(googleLoginUrl({ hostname: 'job.donhse.duckdns.org', port: '' })).toBe(GOOGLE_LOGIN_URL)
  })

  it('로그인하면 워크스페이스와 아바타를 보여준다', async () => {
    vi.mocked(auth.fetchMe).mockResolvedValue(member)

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.find('.auth-card').exists()).toBe(false)
    // 홈은 채용공고. 상단에서 일반 작업 화면을 전환한다.
    expect(wrapper.find('.page-head').text()).toContain('채용공고')
    expect(wrapper.findAll('.segmented button').map((b) => b.text())).toEqual(['채용공고', '모집 직무', '기업', '이력서', '자기소개'])
    // 아바타는 이름 첫 글자, 툴팁은 이메일
    const avatar = wrapper.get('.avatar')
    expect(avatar.text()).toBe('홍')
    expect(avatar.attributes('title')).toBe('member@example.com')
  })

  it('아바타를 누르면 이름 수정 팝오버가 열리고 저장하면 me 가 갱신된다', async () => {
    vi.mocked(auth.fetchMe).mockResolvedValue(member)
    vi.mocked(auth.updateMyName).mockResolvedValue({ ...member, displayName: '김철수' })

    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.find('.popover').exists()).toBe(false)

    await wrapper.get('.avatar').trigger('click')
    expect(wrapper.find('.popover').exists()).toBe(true)
    expect(wrapper.find('.popover').text()).toContain('member@example.com')
    expect(wrapper.find('.popover').text()).toContain('닉네임')

    await wrapper.get('.popover input').setValue('김철수')
    await wrapper.get('.popover form').trigger('submit')
    await flushPromises()

    expect(auth.updateMyName).toHaveBeenCalledWith('김철수')
    expect(wrapper.find('.popover').exists()).toBe(false)
    expect(wrapper.get('.avatar').text()).toBe('김')
  })

  it('이름이 없으면 아바타에 이메일 첫 글자를 대문자로 보여준다', async () => {
    vi.mocked(auth.fetchMe).mockResolvedValue(admin)

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.get('.avatar').text()).toBe('A')
  })

  /** 관리자는 성격이 달라 기업/채용공고 세그먼트에 넣지 않는다 — docs/design-system.md 규칙 1. */
  it('관리자 버튼은 세그먼트 밖에 있고 일반 계정에게는 보이지 않는다', async () => {
    vi.mocked(auth.fetchMe).mockResolvedValue(admin)
    const asAdmin = mount(App)
    await flushPromises()

    expect(asAdmin.find('.segmented').text()).not.toContain('관리자')
    await asAdmin.get('[aria-controls="site-menu"]').trigger('click')
    expect(asAdmin.get('#site-menu').text()).toContain('관리자')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(asAdmin.find('#site-menu').exists()).toBe(false)

    vi.mocked(auth.fetchMe).mockResolvedValue(member)
    const asMember = mount(App)
    await flushPromises()

    await asMember.get('[aria-controls="site-menu"]').trigger('click')
    expect(asMember.get('#site-menu').text()).not.toContain('관리자')
  })

  it('/positions 는 모집 직무, /companies 는 기업 화면을 보여준다', async () => {
    vi.mocked(auth.fetchMe).mockResolvedValue(member)

    setPath(ROUTES.positions)
    const positions = mount(App)
    await flushPromises()
    expect(positions.find('.page-head').text()).toContain('모집 직무')

    setPath(ROUTES.companies)
    const companies = mount(App)
    await flushPromises()
    expect(companies.find('.page-head').text()).toContain('기업')
  })

  /** 일반 계정에게는 관리자 링크도, /admin 화면도 주지 않는다. */
  it('일반 계정이 /admin 에 들어오면 권한 안내만 보여준다', async () => {
    setPath(ROUTES.admin)
    vi.mocked(auth.fetchMe).mockResolvedValue(member)

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('관리자 권한이 필요합니다.')
    expect(wrapper.find('.table-wrap').exists()).toBe(false)
    expect(wrapper.find('.admin-panel').exists()).toBe(false)
  })

  it('관리자 계정이 /admin 에 들어오면 관리자 콘솔을 보여준다', async () => {
    setPath(ROUTES.admin)
    vi.mocked(auth.fetchMe).mockResolvedValue(admin)

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.find('.page-head').text()).toContain('관리자')
    expect(wrapper.find('.admin-panel').exists()).toBe(true)
  })

  /** Google 로그인 실패는 리다이렉트 쿼리로 돌아온다. */
  it('구 도메인의 콜백은 새 주소에서 재로그인하도록 안내한다', async () => {
    setPath('/?authError=DOMAIN_CHANGED')
    vi.mocked(auth.fetchMe).mockResolvedValue(anonymous)
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.text()).toContain('이 페이지에서 Google 로그인을 다시 시작')
    expect(wrapper.get('a.google-button').attributes('href')).toBe(GOOGLE_LOGIN_URL)
    expect(window.location.search).toBe('')
  })

  it('authError 쿼리를 사람이 읽을 문구로 바꿔 보여준다', async () => {
    setPath('/?authError=ACCOUNT_NOT_APPROVED')
    vi.mocked(auth.fetchMe).mockResolvedValue(anonymous)

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('관리자 승인 후 로그인할 수 있습니다.')
    expect(window.location.search).toBe('')
  })
})
