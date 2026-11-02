/**
 * 공고·직무·기업을 지우면 그 직무로 쓴 자소서의 직접 출처(written_for) 연결이 CASCADE 로 함께 사라진다.
 * 삭제 확인창이 그 수를 알린다 — docs/self-introductions.md 함정.
 */
interface WithLinkedCount {
  linkedSelfIntroCount?: number | null
}

/** 상세 응답에만 수가 있다. 목록에서 고른 대상(null)이면 상세를 한 번 불러 얻고, 못 얻으면 0 이다(삭제 자체는 막지 않는다). */
export async function linkedSelfIntroCount(target: WithLinkedCount, loadDetail: () => Promise<WithLinkedCount>): Promise<number> {
  if (target.linkedSelfIntroCount != null) return target.linkedSelfIntroCount
  try {
    return (await loadDetail()).linkedSelfIntroCount ?? 0
  } catch {
    return 0
  }
}

/** 확인창 설명 뒤에 붙는 문구. scope 예: '이 직무로'. 0 이면 붙이지 않는다. */
export const writtenForNotice = (count: number, scope: string) =>
  count > 0 ? ` ${scope} 쓴 자소서 ${count}개의 직접 출처 연결이 함께 사라집니다(답변은 남습니다).` : ''
