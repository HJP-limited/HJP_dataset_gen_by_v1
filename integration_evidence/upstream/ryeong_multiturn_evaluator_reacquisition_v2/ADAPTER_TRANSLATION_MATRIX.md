# adapter translation matrix — upstream `/chat` ↔ production `AgentKernel`

**adapter는 이번 작업에서 구현하지 않았다.** 이유는 §4에 있다. 아래는 구현 시 지켜야 할 매핑과,
억지로 맞추면 안 되는 항목의 목록이다.

## 1. request 방향 (upstream → production)

| upstream 필드 | 타입 | production 대응 | 변환 | 손실 |
|---|---|---|---|---|
| `question` | string | `TurnContext.userText` | 없음 | 없음 |
| `history[].q` / `.a` | array | `AgentSession.transcript` turn | 재생 필요 | 없음 |
| `history[].filter_terms` | string\|null | `AgentSession.KEY_LAST_FILTER_TERMS` (upstream 주석이 직접 대응을 밝힘) | 없음 | 없음 |
| `focus` | string\|null | `ConversationMemory.selectedContact.cardId` | **provenance를 만들어야 함** | **있음** — 아래 §3.1 |
| `prev_card_ids` | array\<string\> | `ConversationMemory.candidateContacts` | 이름·회사 없음 | 있음 |
| `conversation_memory` | object (불투명) | `ConversationMemory` (9 필드) | — | **evaluator가 검사하지 않으므로 평가 대상이 아님** |
| `dry_run` | bool | 모델 gateway를 recording으로 둘지 여부 | 개념 대응 | 없음 |

## 2. response 방향 (production → upstream)

| upstream 필드 | evaluator가 읽음 | production 원천 | 변환 |
|---|---|---|---|
| `route` | ✅ 라우팅 정확도 | `DialogueAct` | **부분 매핑만 가능** — §3.2 |
| `field_filters.{name,title,location}` | ✅ JGA·F1 | `search_contacts` 인자 / `SearchFieldConstraintPlan` | 평면화 필요 |
| `card_ids` | ✅ R@5 (`[:5]`) | `search_contacts` 결과 순서 | 없음 |
| `focus` | ✅ (시나리오가 명시할 때만) | `selectedContact.cardId` | 없음 |
| `abstained` | ✅ 체크리스트 버킷 결정 | 기권 판정 | 개념 대응 |
| `answer` | ✅ `must`/`must_not` | 최종 응답 | 없음 |
| `hybrid_top` | ✅ dry-run history 채움 | 결과 카드 이름 | 없음 |
| `gen_ms` | ✅ `--latency` | — | **측정 의미가 다름**, §3.5 |
| `conversation_memory` | 되돌려주기만 함 | `ConversationMemory` | 왕복만 하면 됨 |

## 3. 억지로 맞추면 안 되는 것

### 3.1 focus ↔ selectedContact — provenance가 없다

upstream `focus`는 **검색 1등에서 파생된 문자열**이다. production `ContactReference`는
`provenance`(`TOOL_VERIFIED` 등)·`selection`·`confirmedAtEpochMillis`를 갖고, **이메일·전화를
담지 않으며** 실행 직전 `card_id`로 `get_contact`를 다시 부른다.

upstream 값에서 provenance를 만들어낼 수 없다. 임의로 `TOOL_VERIFIED`를 붙이면
**stale-ID 차단과 fail-closed target 생성이라는 production 안전 계약을 우회**하게 된다.
adapter는 provenance를 **생성하지 말고**, 시나리오가 focus를 명시할 때만 그 값을 비교해야 한다.

### 3.2 route ↔ DialogueAct — 부분 교집합

| upstream route | 가장 가까운 `DialogueAct` | 안전한가 |
|---|---|---|
| `search` | `CONTACT_SEARCH` | 대체로 O |
| `followup` | `CONTACT_DETAIL` | **주의** — production은 후속 필드 질문을 카드 재조회로 처리한다 |
| `empty_result` | `CONTACT_SEARCH` + 결과 0 | route 축이 다름 |
| `abstain` | `CLARIFICATION_REQUIRED` 또는 `UNSUPPORTED` | **1:다** |
| `context_answer` | `ANSWER_FROM_HISTORY` / `QUOTED_RECALL` | **1:다** |
| `filtered_count`·`total_count` | **대응 없음** | production에 집계 route가 없다 |
| `self_reference` | `GENERAL_INFORMATION`? | 불확실 |
| — | `ACTION_COMPOSE`·`ACTION_CALENDAR`·`ACTION_UPDATE`·`DATETIME_QUERY`·`CORRECTION`·`FAILURE_QUESTION` | **upstream에 자리 없음** |

`abstain`과 `context_answer`가 1:다인 이상, 매핑 방향을 정하는 것은 **정책 결정**이지
adapter가 임의로 정할 사항이 아니다.

### 3.3 side effect — upstream에 개념이 없다

이전 감사에서 경고한 "`expectedSideEffects`가 화면 열기인가 실제 발송인가" 문제는 **해소됐다 —
upstream에는 side-effect 필드가 아예 없다.** 따라서 매핑 위험이 아니라 **평가 공백**이다.

production의 계약은 명확하다.

* `open_compose`는 **작성 화면을 여는 것**이지 발송이 아니다.
* `create_calendar_event`는 **작성 화면을 여는 것**이지 저장이 아니다.
* `update_business_card`는 확인 절차를 거친 **로컬 저장**이며 이것만 실제 완료다.
* `SideEffectGuard`가 턴당 1회로 제한한다.

upstream 평가를 아무리 통과해도 **이 셋 중 어느 것도 검증되지 않는다.**

### 3.4 미지정 ≠ 부정

upstream 턴 중 route 미검사 13개, slots 미검사 89개(377−288), gold 미검사 42개가 있다.
이는 "비어 있어야 한다"가 아니라 **"이 턴에서는 주장하지 않는다"**이다.
`None`을 `false`/`empty`로 바꾸면 원본에 없던 기대를 만들어내는 것이다.

단 **예외가 하나 있다**: `slot_sets`는 `names`/`titles`/`locations` 세 축을 **항상** 비교한다.
원본 주석이 "안 적었으면 비어 있어야 한다는 뜻"이라고 명시하므로, 이 세 축에 한해
미지정은 **빈 집합 기대**가 맞다. adapter는 이 예외를 지켜야 한다.

### 3.5 latency — 이식 금지

upstream `gen_ms`는 데스크톱 LLM 서버 왕복 시간이다. 원본 주석이 **"실기기 지연은 이 도구로
측정할 수 없다"**고 못박는다. adapter가 JVM 턴 시간을 같은 필드에 담으면 두 수치가 한 열에
섞인다. 담더라도 **다른 이름**을 써야 한다.

### 3.6 upstream이 검사하지 않는 production 안전 계약

adapter가 upstream 계약만 구현하면 다음이 **전부 미평가로 남는다.**

* 새 대화(`TurnLease`) 후 이전 generation 도구 차단
* side-effect 턴당 1회 제한
* 실행 직전 `get_contact` 재조회(stale ID 차단)
* `selectedContact`에 PII를 넣지 않는 계약
* typed `TurnOutcomeType`
* 순서 있는 tool trace와 인자
* `search → get → terminal action` 다중 도구 워크플로

## 4. 그래서 왜 구현하지 않았는가

upstream 계약을 그대로 구현하면 production 계약의 절반 이상이 평가되지 않는데, 결과물은
"멀티턴 평가를 통과했다"처럼 보인다. 그 착시가 이 프로젝트에서 반복적으로 경계해 온 실패 양식이다.

구현 전에 **결정이 필요하다.**

1. **upstream 부분집합만 평가**한다 — 검색·라우팅·focus·R@5. 그렇다면 결과 보고서에
   "도구·outcome·side-effect는 평가 범위 밖"을 항상 명시해야 한다.
2. **production 전체 계약을 평가**한다 — 그렇다면 기준은 upstream이 아니라 이미 저장소에 있는
   `MultiturnSpec`/`StrictMultiturnEvaluator`이고, upstream에서 가져올 것은 **시나리오 생성
   아이디어와 130개 케이스의 언어적 다양성**이지 채점 계약이 아니다.
3. **둘 다** 한다 — 서로 다른 두 run으로 분리하고 수치를 절대 합치지 않는다.

이 결정은 평가 정책이며 adapter 구현자가 임의로 내릴 수 없다.

## 5. 이미 존재하는 production 쪽 자산

2번을 택하면 새로 만들 것이 많지 않다.

`app/src/test/java/com/example/hjp/eval/MultiturnSpec.kt`(schema),
`StrictMultiturnEvaluator.kt`(채점), `MultiturnScenarioHarness.kt`(실제 `AgentKernel` 하네스),
`eval/args/JsonArgumentComparator.kt`(재귀 인자 비교), `eval/clock/EvaluationClock.kt`(결정적 clock),
`eval/contract/OutcomeContract.kt`, 동결 held-out v1/v2/v3.

`TurnSpec`은 `expectedAct`·`expectedOutcome`·`expectedTools`(순서)·`forbiddenTools`·`expectedArgs`·
`expectedSelectedCardId`·`expectNoSelectedContact`·`expectedCandidateIds`·`expectedSideEffects`·
`answerMustContain/NotContain`·`resetBefore`를 이미 갖고 있다.
