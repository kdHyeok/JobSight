/**
 * 공고·직무·기업을 지우면 그 직무로 쓴 자소서의 직접 출처(written_for) 연결이 CASCADE 로 함께 사라진다.
 * 삭제 확인창이 그 수를 알린다 — docs/self-introductions.md 함정.
 */
interface WithLinkedCount {
  linkedSelfIntroCount?: number | null
}

/** 상세 응답에만 수가 있다. 목록에서 고른 대상(null)이면 상세를 한 번 불러 얻고, 못 얻으면 null(모름)이다 — 0 으로 치지 않는다. */
export async function linkedSelfIntroCount(target: WithLinkedCount, loadDetail: () => Promise<WithLinkedCount>): Promise<number | null> {
  if (target.linkedSelfIntroCount != null) return target.linkedSelfIntroCount
  try {
    return (await loadDetail()).linkedSelfIntroCount ?? null
  } catch {
    return null
  }
}

/** 확인창 설명 뒤에 붙는 문구. scope 예: '이 직무로'. 0 이면 붙이지 않고, 모르면(null) 있을 수 있다고 알린다. */
export const writtenForNotice = (count: number | null, scope: string) =>
  count === null
    ? ` ${scope} 쓴 자소서 수를 확인하지 못했습니다. 있다면 그 직접 출처 연결이 함께 사라집니다(답변은 남습니다).`
    : count > 0 ? ` ${scope} 쓴 자소서 ${count}개의 직접 출처 연결이 함께 사라집니다(답변은 남습니다).` : ''
