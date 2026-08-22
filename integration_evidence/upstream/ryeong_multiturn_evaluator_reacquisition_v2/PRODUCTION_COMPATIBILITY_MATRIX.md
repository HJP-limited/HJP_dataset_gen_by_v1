# `hybrid_server.py`가 실제로 실행하는 것 vs 현재 production 경로

## 1. 판정: **분류 2 + 4** — 독립 LiteRT LLM 서버 + 검색 로직, 일부 검색 개념만 공유

`hybrid_server.py`의 헤더 주석이 스스로 밝힌다.

```
로컬 완전 하이브리드 채팅 백엔드.
앱과 동일한 파이프라인을 노트북에서 재현한다:
  질문 → 대명사 치환 → [키워드(KeywordSearchRanker 포팅) + 시맨틱(EmbeddingGemma)] → RRF → Gemma(serve) 답변
eval_search.py의 키워드/벡터/RRF 로직을 그대로 재사용한다(드리프트 방지).
```

**"포팅"이고 "재현"이다 — 호출이 아니다.** 이 서버는 Kotlin 코드를 한 줄도 실행하지 않는다.
JVM도, `AgentKernel`도, Room도 띄우지 않는다. Python으로 다시 구현한 **평행 구현체**이며,
공유하는 것은 알고리즘 의도이지 코드가 아니다.

따라서 **분류 3(현재 Android/Kotlin `AgentKernel`과 동일한 production path 실행)은 아니다.**

> **원본 Ryeong 서버만 실행한 결과를 현재 Android 에이전트의 멀티턴 성능으로 보고할 수 없다.**

## 2. 결정적 차이 — upstream에는 도구 개념 자체가 없다

`hybrid_server.py`(1,434행)와 `eval_multiturn.py`(724행) 전체에서:

| 개념 | server | evaluator |
|---|---|---|
| `compose` | **0** | **0** |
| `calendar` | **0** | **0** |
| `tool_call` | **0** | **0** |
| `outcome` | **0** | **0** |

upstream의 route enum은 **전부 검색 관련**이다. 130 시나리오 377턴의 기대 route 분포:

| route | 턴 수 |
|---|---|
| `search` | 300 |
| `followup` | 28 |
| `context_answer` | 9 |
| `empty_result` | 9 |
| `abstain` | 8 |
| `filtered_count` | 8 |
| `total_count` | 1 |
| `self_reference` | 1 |
| (route 미검사) | 13 |

시나리오 유형 중 `도구 범위 밖`(4개)이 있지만, 이는 **도구 실행을 검사하는 것이 아니라
"범위 밖 요청을 거절하는가"를 검사한다.**

**즉 원본 evaluator는 RAG 검색 채팅을 평가한다. 도구를 쓰는 에이전트를 평가하지 않는다.**

## 3. 축별 비교

| 축 | upstream `/chat` | 현재 production | 판정 |
|---|---|---|---|
| **세션** | `history` 배열을 매 요청에 통째로 전송 (stateless server) | `AgentSession` + `ConversationMemory`가 앱 정본, native는 실행 캐시(`APP_CANONICAL_BOOTSTRAP`) | 개념 대응 O, 전송 모델 상이 |
| **새 대화/reset** | **없음** — 계약에 reset 개념 자체가 없다 | `AgentSession.generation` + `TurnLease` | **대응 없음** |
| **메모리** | `conversation_memory` 하나의 **불투명 객체**. evaluator는 내용을 검사하지 않고 그대로 되돌려준다 | schemaVersion 2: `topic`/`confirmedFacts`/`preferences`/`constraints`/`corrections`/`actions`/`selectedContact`/`candidateContacts`/`contactMentions` | upstream이 **검사하지 않음** → 평가 불가 |
| **target** | `focus`: 문자열 하나. 검색 1등에서 파생 | `ContactReference`: `cardId`+`provenance`+`selection`+`confirmedAtEpochMillis`, 이메일·전화 **미포함** | provenance·stale-ID 개념 **없음** |
| **ambiguity** | 별도 표현 없음 (동명이인 시나리오 6개는 route/slot으로만 판정) | `CLARIFICATION_REQUIRED` + `candidateContacts` | 부분 대응 |
| **routing** | 8개 검색 route | `DialogueAct` 15값 (검색 3 + 액션 3 + 기타 9) | **부분 교집합만** |
| **검색** | keyword + EmbeddingGemma semantic + RRF, `card_ids`/`hybrid_top`/`field_filters` 노출 | ryeong 하이브리드(`search_contacts`), keyword fallback 존재 | 개념 대응 O |
| **tool** | **없음** | 6개 등록 (`search_contacts`/`get_contact`/`create_calendar_event`/`open_compose`/`update_business_card`/`get_current_datetime`) | **대응 없음** |
| **side effect** | **없음** | 화면 열기 횟수 + `SideEffectGuard`(턴당 1회) | **대응 없음** |
| **outcome** | **없음** | `TurnOutcomeType` 10값 | **대응 없음** |
| **final response** | `answer` 문자열 + `must`/`must_not` 부분문자열 검사 | 동일 방식 사용 가능 | 대응 O |
| **tool trace** | **없음** | `TurnRecord.executedTools` 순서 있는 trace + 인자 | **대응 없음** |
| **multi-tool** | **없음** | `search → get → terminal action` | **대응 없음** |

## 4. 결론

upstream 계약으로 평가할 수 있는 것은 현재 에이전트의 **검색·라우팅·focus 유지 부분집합**뿐이다.
`open_compose`·`create_calendar_event`·`update_business_card`, typed outcome, side-effect admission,
tool trace, 새 대화 폐기 — **production 계약의 절반 이상이 upstream 계약에 표현 자리가 없다.**

없는 것을 있는 것처럼 매핑하면 "평가했다"는 착시만 만든다. 이것이 이번 작업에서 adapter를
구현하지 **않은** 이유이며, 상세는 `ADAPTER_TRANSLATION_MATRIX.md`에 있다.
