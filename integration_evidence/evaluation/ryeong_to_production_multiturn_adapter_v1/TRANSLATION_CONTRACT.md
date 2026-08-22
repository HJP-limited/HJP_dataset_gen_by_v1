# translation contract v1 (확정)

`ADAPTER_TRANSLATION_MATRIX.md`(v2 감사본)를 출발점으로, 이번 구현에서 **실제로 확정된** 매핑이다.
기계 판독본은 `TRANSLATION_CONTRACT.json`.

| 항목 | 판정 | 근거 |
|---|---|---|
| route `search` | `DERIVED_FROM_PRODUCTION_TRACE` | `DialogueAct.CONTACT_SEARCH` |
| route `followup` | `DERIVED_FROM_PRODUCTION_TRACE` | `CONTACT_DETAIL` 또는 `CONTACT_SELECTION` — upstream은 둘을 구분하지 않는다 |
| route `empty_result` | `NOT_SCORABLE` | upstream은 "찾았는데 0건"을 route로 표현하고 production은 act(`CONTACT_SEARCH`)를 유지한 채 outcome에 담는다. 두 축이 어긋난다 |
| route `abstain` | `NOT_SCORABLE` | production의 `CLARIFICATION_REQUIRED`와 `UNSUPPORTED`로 갈린다. 1:다 |
| route `context_answer` | `NOT_SCORABLE` | `ANSWER_FROM_HISTORY`와 `QUOTED_RECALL`로 갈린다. 1:다 |
| route `filtered_count` | `NOT_SCORABLE` | production에 집계 route가 없다 |
| route `total_count` | `NOT_SCORABLE` | 위와 같다 |
| route `self_reference` | `NOT_SCORABLE` | production에 대응 route가 없다 |
| typed outcome | `NOT_APPLICABLE` | upstream에 outcome 축이 없다. 기록만 하고 채점하지 않는다 |
| focus / selected card id | `DIRECT` | `ConversationMemory.selectedContact.cardId` |
| previous card ids | `DERIVED_FROM_PRODUCTION_TRACE` | 직전 턴 backend ranking |
| field filter names | `NOT_SCORABLE` | seam 없음 (`PRODUCTION_TRACE_CONTRACT.md`) |
| field filter titles | `NOT_SCORABLE` | 위와 같다 |
| field filter locations | `NOT_SCORABLE` | 위와 같다 |
| top-5 후보 | `DIRECT` | `TracingContactBackend`가 기록한 순위 그대로 |
| generated answer | `NOT_SCORABLE` | actual model 미실행 |
| no-card suppression | `NOT_SCORABLE` | upstream도 생성 후에만 판정한다. actual model 미실행 |
| conversation depth | `DIRECT` | export가 턴 수를 보존한다 |

## 매핑 근거 — 왜 이름이 비슷하다고 잇지 않았나

`abstain`을 `CLARIFICATION_REQUIRED`에 붙이면 "명확화가 필요하다"와 "그건 못 한다"가 한 값이 된다.
production은 이 둘을 다르게 처리하고 다른 후속 턴을 만든다. 어느 쪽으로 접을지는 **평가 정책**이지
adapter가 정할 일이 아니다. `context_answer`도 같은 이유다.

`empty_result`는 방향 자체가 다르다. upstream은 결과 수를 route에 넣었고 production은 act를 유지한
채 outcome에서 표현한다. 억지로 이으면 "검색을 안 했다"와 "검색했는데 0건"이 구분되지 않는다.

## 금지 사항 준수

* 이름 유사성만으로 자동 매핑한 항목 **0**
* 최종 응답 문자열에서 route를 역추론하는 코드 **0**
* 기대 route를 보고 실제 route를 만드는 코드 **0**
* gold id를 focus로 주입 **0**
* 기대 slot을 query state로 주입 **0**
* 결과 0건일 때 gold를 후보에 추가 **0**

## 제외의 효과

`NOT_SCORABLE` 턴은 **분자에도 분모에도 들어가지 않는다.** 실패로도 성공으로도 세지 않으며,
제외 수와 사유를 결과에 남긴다. `RyeongRunGate`가 `scored + excluded == 전체 턴`을 검사해
제외된 턴이 슬그머니 분모에 들어가는 것을 막는다.
