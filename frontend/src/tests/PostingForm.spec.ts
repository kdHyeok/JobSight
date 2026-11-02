import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PostingForm from '../components/PostingForm.vue'
import { nowLocalInput, type JobPosting } from '../types/posting'

describe('PostingForm', () => {
  afterEach(() => vi.useRealTimers())

  it('새 공고와 새 절차 일정은 폼을 연 현재 분이 기본값이다', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 8, 6, 14, 37))
    const expected = nowLocalInput()
    const wrapper = mount(PostingForm, {
      props: { posting: null, companies: [], saving: false, apiFieldErrors: {} },
    })

    expect(wrapper.get<HTMLInputElement>('input[type="datetime-local"]').element.value).toBe(expected)
    await wrapper.findAll('.repeater > .button')[1].trigger('click')
    expect(wrapper.findAll<HTMLInputElement>('input[type="datetime-local"]')[1].element.value).toBe(expected)
  })

  // 서버는 positions·steps 가 null 이면 "유지", [] 면 "비움"이다(docs/job-postings.md).
  // 폼에서 행을 다 지웠는데 필드를 빼고 보내면 지운 것이 조용히 남는다.
  it('기존 직무는 id 와 함께, 다 지운 직무·절차는 [] 로 보낸다', async () => {
    const posting: JobPosting = {
      id: 'p1', companyId: 'c1', companyName: '회사', title: '2026 신입 공채', postingUrl: null,
      employmentType: 'FULL_TIME', deadlineAt: null, status: 'INTERESTED', qualifications: null, memo: null,
      targetPositionId: null, archived: false, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z',
      positions: [{ id: 'pos1', name: '백엔드 개발', team: null, headcount: null, workLocation: null }],
      steps: [{ seq: 0, name: '서류', result: 'UPCOMING', scheduledAt: null, memo: null }],
    }
    const wrapper = mount(PostingForm, {
      props: { posting, companies: [], saving: false, apiFieldErrors: {} },
    })

    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')![0][0]).toMatchObject({ positions: [{ id: 'pos1', name: '백엔드 개발' }] })

    await wrapper.get('[aria-label="직무 삭제"]').trigger('click')
    await wrapper.get('[aria-label="단계 삭제"]').trigger('click')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')![1][0]).toMatchObject({ positions: [], steps: [] })
  })

  it('세분화된 아홉 상태만 새 입력 선택지로 보여준다', () => {
    const wrapper = mount(PostingForm, {
      props: { posting: null, companies: [], saving: false, apiFieldErrors: {} },
    })
    const statusSelect = wrapper.findAll('select')[2]
    expect(statusSelect.findAll('option').map((option) => option.text())).toEqual([
      '관심', '작성 중', '제출 완료', '필기 준비', '면접 준비', '합격', '서류 탈락', '필기 탈락', '면접 탈락',
    ])
  })
})
