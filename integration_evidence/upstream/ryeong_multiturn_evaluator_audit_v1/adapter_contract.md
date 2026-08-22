# production 멀티턴 adapter 계약

**상태: PARTIALLY DERIVABLE.** 구현하지 않았다.

adapter의 **production 쪽 끝은 확정할 수 있다** — 현재 저장소의 코드에서 직접 읽었다.
**원본 쪽 끝은 확정할 수 없다** — 참조 저장원이 없다. 아래에서 두 부분을 섞지 않고 나눈다.

---

## A. 지금 확정되는 것 — production 쪽

### A.1 이미 존재하는 자산

`production Kotlin 멀티턴 adapter는 아직 없다`가 이번 작업의 전제였는데, 감사 결과 **평가 장치
자체는 이미 상당 부분 존재한다.** 없는 것은 *원본 dataset을 이 장치에 먹이는 adapter*다.

| 경로 | 역할 |
|---|---|
| `app/src/test/java/com/example/hjp/eval/MultiturnSpec.kt` | case·turn schema (`MultiturnSpec`, `TurnSpec`, `EvalCategories`) |
| `app/src/test/java/com/example/hjp/eval/StrictMultiturnEvaluator.kt` | 채점기 (`ScenarioResult`, `TurnObservation`, `AssertionFailure`) |
| `app/src/test/java/com/example/hjp/MultiturnScenarioHarness.kt` | production `AgentKernel`·session·tool 실행 하네스 |
| `app/src/test/java/com/example/hjp/eval/args/JsonArgumentComparator.kt` | tool argument의 **재귀적** 비교 |
| `app/src/test/java/com/example/hjp/eval/clock/EvaluationClock.kt` | 결정적 clock (재현성 테스트 있음) |
| `app/src/test/java/com/example/hjp/eval/EvalOutputPolicy.kt` | 결과 출력 정책 |
| `app/src/test/java/com/example/hjp/eval/contract/OutcomeContract.kt` | typed outcome 계약과 버전 |
| `eval/v2/`, `eval/v3/`, `FrozenHeldoutCases.kt` | 동결된 held-out 세트 v1/v2/v3 |

**adapter는 이 장치를 다시 만들지 말고 재사용해야 한다.**

### A.2 adapter가 반드시 받는 입력

1. 원본 immutable dataset — **읽기 전용으로만 연다.**
2. fixture/contact repository — case가 지정한 카드 집합. 미지정 시 `MultiturnScenarioHarness.DEFAULT_CARDS`.
3. case 초기 상태 — 빈 세션에서 시작하는지, 사전 focus가 있는지.
4. turn sequence — 사용자 발화의 **순서 있는** 목록.
5. 결정적 clock과 seed — `EvaluationClock`을 쓰고 wall clock을 쓰지 않는다.
6. production `AgentKernel` — 기본 `AgentKernelMode.REACT`.
7. recording tool plugin — 실행을 **관찰만** 하고 dispatch 결정에 관여하지 않는다.
8. model gateway — recording gateway든 실제 모델이든 **어느 쪽인지 결과에 명시**한다.

### A.3 adapter가 반드시 내는 출력

run 단위: `run_id`, 원본 evaluator SHA, dataset SHA, **adapter 자신의 SHA**, gate verdict, aggregate metric.

turn 단위: `case_id`, `turn_index`, 사용자 발화, route/`DialogueAct`, **순서 있는** tool sequence,
**재귀 typed** tool arguments, tool result, selected/focused `card_id`, provenance, memory delta,
typed `TurnOutcomeType`, 최종 응답, side-effect 수, latency.

case 단위: pass/fail과 **실패 사유**(어느 필드가 무엇을 기대했고 무엇이 관측됐는지).

### A.4 안전 요구 — 협상 대상 아님

* 원본 dataset을 수정하지 않는다.
* runner와 gate를 **실행 전에 동결**하고 SHA로 고정한다.
* output은 `CREATE_NEW`로 쓴다. 기존 run을 덮어쓰지 않는다.
* run마다 고유 경로를 쓴다.
* **결과를 본 뒤 같은 version을 수정해 재실행하지 않는다.** 고치려면 새 version이다.
* production Kotlin 경로를 실제로 실행한다.
* standalone Python 모사 결과와 production 결과를 **같은 표에 섞지 않는다.**
* actual semantic 실행과 keyword fallback을 구분해 기록한다.
* synthetic·deterministic vector를 actual EmbeddingGemma라고 쓰지 않는다.
* **검색 ranking 수치를 멀티턴 성공으로 재사용하지 않는다.**

### A.5 이미 드러난 번역 위험 세 가지

`production_schema_comparison.json`에서 나온 것 중, 원본을 보기 전에도 확정적으로 말할 수 있는
것들이다.

1. **side-effect의 의미.** production의 `expectedSideEffects`는 **작성 화면을 연 횟수**다.
   "메일을 보냈다"·"일정을 저장했다"를 뜻하는 원본 필드를 여기에 매핑하면 안 된다.
2. **tool argument의 중첩.** `TurnSpec.expectedArgs`는 `Map<String, Map<String, String>>`로
   평면이다. 원본이 중첩 객체나 배열을 쓰면 그대로 담기지 않는다. `JsonArgumentComparator`가
   재귀 비교를 이미 하므로 중첩은 그쪽으로 넘겨야 한다.
3. **미지정과 부정의 구분.** 원본에 focus 필드가 없다는 것은 "focus가 없어야 한다"가 아니라
   "주장하지 않는다"이다. 없는 필드를 `expectNoSelectedContact = true`로 바꾸면 **원본에 없던
   기대를 만들어내는 것**이다.

추가로, typed outcome이 원본에 없다면 adapter는 `expectedOutcome`을 **비워 둬야** 한다.
boolean pass/fail에서 typed outcome을 복원할 수 없다.

---

## B. 지금 확정할 수 없는 것 — 원본 쪽

* 원본 case schema의 실제 필드와 타입
* 원본 category 이름과 그 정의
* 원본 turn 길이 분포와 case 수
* 원본 metric의 분자·분모·제외 조건·partial credit·rounding
* 원본 gate threshold와 그 소재(코드 고정인지 외부 파일인지)
* 원본 tool 이름과 argument key
* 원본이 production Kotlin을 호출하는지, standalone Python 모사인지

**category 매핑은 특히 미정이다.** 로컬에 남은 과거 결과 요약의 분류
(`focus_maintenance`, `focus_switch`, `pronoun_and_ellipsis`, `numeric_new_search`,
`unrelated_context_isolation`, `honorific_cases`, `ordinal_selection`,
`duplicate_name_safe_handling`, `zero_result_safe_handling`, `three_turn_repeat_stability`)와
현재 `EvalCategories`의 8개 분류
(`reference_resolution`, `slot_and_correction`, `tool_and_workflow`, `action_vs_information`,
`ambiguity_and_grounding`, `failure_and_idempotency`, `unsupported_and_adversarial`,
`result_response_consistency`)는 **이름이 하나도 겹치지 않는다.** 게다가 전자는 서로 겹치는
분류(`honorific_cases.overlaps_focus_switch = true`)이고 후자의 `primaryCategory`는 분모를 한 번만
세려고 **단일값으로 설계**돼 있다. 이 매핑은 추측이 아니라 원본 정의를 보고 정해야 한다.

---

## C. 원본이 공급되면 이 순서로 진행한다

1. 원본 evaluator·dataset SHA 고정, `__pycache__` 없이 정적 감사(§7·§8).
2. metric·gate를 **코드에서** 읽어 `metric_definitions.json`·`gate_definitions.json` 완성.
3. category 매핑표를 원본 정의 기준으로 작성 — 겹침 처리 규칙 명시.
4. `production_schema_comparison.json`의 20개 행 중 reference 열을 채움.
5. adapter를 **동결 후** 구현하고, red/green으로 검증한 뒤 실행.
6. 실행은 고유 경로에 `CREATE_NEW`로 1회.

이번 작업은 1번 이전에서 멈춘다.
