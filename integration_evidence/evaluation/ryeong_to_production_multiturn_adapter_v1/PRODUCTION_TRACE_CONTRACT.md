# production trace 관측 계약

adapter가 읽는 값은 **전부 production이 실제로 만든 값**이다. 기대값을 입력으로 주입하지 않는다.

## 관측 가능한 것

| 값 | 출처 | 성격 |
|---|---|---|
| dialogue act | `DeterministicTurnRouter.act(session.turnContext(...))` — 커널이 라우팅에 쓰는 그 결정 | DIRECT |
| tool trace | `TurnRecord.executedTools` (커널이 dispatch한 순서) | DIRECT |
| tool arguments | `TurnRecord.toolArguments` (`JsonObject`, 중첩 보존) | DIRECT |
| search ranking | `TracingContactBackend.rankings` — backend가 돌려준 `hits`의 card id 순서 | DIRECT |
| retrieval mode | `TracingContactBackend.modes` (`KEYWORD_ONLY` / `HYBRID`) | DIRECT |
| focus / card id | `session.conversationMemory.selectedContact?.cardId` | DIRECT |
| typed outcome | `TurnRecord.outcomeType` | DIRECT (기록만, 이 축에서는 채점 안 함) |
| 최종 응답 | `TurnRecord.answer` | DIRECT |
| session 격리 | 시나리오 시작 시 `selectedContact`·`transcript` 상태 | DIRECT |

`TracingContactBackend`는 **읽기 전용 decorator**다. 질의를 바꾸지 않고, 결과를 재정렬·절단·치환하지
않으며, 없는 id를 채워 넣지 않는다. 그래서 R@5는 그 아래 production backend를 측정한다.

## 관측 불가능한 것 — `NOT_SCORABLE`

**field filter(names/titles/locations).** production의 `search_contacts`는 인자가 자유 텍스트
`query` 하나뿐이다. 구조화된 조건은 `search-core` 안 `SearchFieldConstraintPlan`에서 계산되는데
이 클래스는 **package-private**이고 밖으로 노출되는 seam이 없다.

따라서 **JGA와 슬롯 P/R/F1은 이번 축에서 `NOT_SCORABLE`이다.** 빈 집합으로 채워 채점하면
"조건을 안 걸었다"와 "조건을 관측할 수 없다"가 같은 값이 되어 점수가 거짓이 된다.

이를 채점하려면 `search-core`에 read-only seam이 필요하다 — `SearchLookupService.retrieve()`가
자신이 적용한 `SearchFieldConstraintPlan`을 진단 콜백으로 내보내면 된다. 순위·결과를 바꾸지 않는
관측 전용이어야 하며, **별도 범위의 변경**이다.

## 주입 금지 — 실제로 지킨 것

* 커널에 넘기는 것은 사용자 문장뿐이다. 기대 route·gold id·기대 slot은 턴이 **끝난 뒤** 채점에만 쓴다.
* gold id를 focus로 넣지 않는다.
* 검색 결과가 없을 때 gold를 후보에 추가하지 않는다.
* 관측 ranking이 매 턴 gold 목록과 정확히 같아지는 상황은 mutation `expected_injected_as_actual`이
  탐지한다.
* case id·문장·이름·card id로 분기하는 코드는 없다.
