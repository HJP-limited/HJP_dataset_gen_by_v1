# Ryeong 원본 멀티턴 evaluator·dataset 계약 감사

**판정: BLOCKED — REFERENCE REPOSITORY NOT PRESENT**

감사 대상인 참조 저장소가 이 기기에 **존재하지 않는다.** 따라서 §7~§9(실행 흐름·dataset·metric·
gate)는 확정할 수 없다. 대신 확정 가능한 것 — production 쪽 계약, 로컬에 실제로 존재하는 멀티턴
자산, adapter가 지켜야 할 요구 — 은 전부 작성했다.

> 이 BLOCKED는 **감사 대상이 없다**는 뜻이다. 원본 evaluator가 잘못됐다는 뜻도, production에
> 문제가 있다는 뜻도 아니다.

---

## 1. 무엇이 없는가

| 항목 | 기대 | 실제 |
|---|---|---|
| 참조 저장소 | `../agent_integration_references_20260817/ryeong-llm-integration` | **ABSENT** |
| 상위 컨테이너 | `agent_integration_references_20260817` | **ABSENT** |
| 참조 branch | `HJP-limited/ryeong: llm-integration-work` | **존재하지 않음** |
| `eval_multiturn.py` | 참조 저장소 내 | **기기 전체에 0개** |

### 수행한 탐색 — 전부 음성

* 예상 경로와 상위 컨테이너 `test -e` → 둘 다 ABSENT
* `/Users/byeol` depth 6 이내 `*agent_integration_reference*`·`*ryeong-llm*`·`*llm-integration*` 디렉터리 → **0**
* `/Users/byeol` depth 8 이내 `*agent_integration*` → **0**
* `eval_multiturn*.py` 전역 → **0**
* `*multiturn*`·`*multi_turn*` 파일 depth 9 (Library·.Trash·node_modules·.git·site-packages 제외) → 23개,
  **전부 이 프로젝트의 두 작업 사본 내부**이며 참조 저장소의 Python evaluator는 없음
* Desktop·Downloads depth 4의 `*agent_integration*`·`*ryeong*llm*`·`*20260817*` 아카이브 → **0**

### 존재하는 유일한 ryeong 체크아웃 — 참조가 아님

```
경로   : ../ryeong
remote : https://github.com/HJP-limited/ryeong.git
HEAD   : e3dd67f6782dae50e8908190ab6debc0f9992056
branch : main
refs   : main, origin/4/29, origin/HEAD, origin/main, origin/search_check
dirty  : ?? .DS_Store   ?? PROJECT_ANALYSIS.md   (2건)
```

`llm-integration-work`는 **local·remote 어느 ref에도 없다.** 이 체크아웃은 Java search-lookup 소스
저장소이고 **Python 파일이 0개**이며 멀티턴 evaluator를 포함하지 않는다. 예상 branch와 실제 branch가
일치하지 않으므로 §17의 "어떤 버전을 감사해야 하는지 결정 불가" 조건에도 해당한다.

---

## 2. 로컬에 실제로 있는 멀티턴 자산 — 원본으로 대체하지 않았다

### 2.1 과거 결과 요약 (dataset도 evaluator도 아님)

`tools/ryeong_search_benchmark/multiturn-final-20260801.json`
(SHA `1d1b1dca6e4df1f0851360fb08e2233023e397c2bcce1a6e3c41880624f2573d`)

이 파일은 **결과**다. 그리고 자신의 출처를 스스로 선언한다.

```
search-core/src/test/java/com/hjp/searchlookup/ContactMultiTurnSessionTest.java
agent-core/src/test/kotlin/com/hjp/agent/core/ContactTurnReferenceResolverTest.kt
tool-contact/src/test/kotlin/com/hjp/tool/contact/ContactPluginsTest.kt
```

셋 다 **이 저장소의 Kotlin/Java JUnit 테스트**이고 현재도 존재한다. 즉 그 수치를 만든 것은 Python
evaluator가 아니다. 이는 "원본 멀티턴 evaluator는 Python `eval_multiturn.py`"라는 전제를 다시
확인할 필요가 있음을 뜻하지만, 그 판단은 원본이 공급돼야 내릴 수 있다.

기록된 값: fixture_scenarios 40, passed 40, failed 0, overall_success_rate 1.0,
focus_maintenance 29/29, focus_switch 5/5, pronoun_and_ellipsis 29/29, numeric_new_search 4/4,
unrelated_context_isolation 2/2, honorific_cases 3/3(focus_switch와 겹침), ordinal_selection 1/1,
duplicate_name_safe_handling 1/1, zero_result_safe_handling 1/1,
three_turn_repeat_stability 10/10(p50 0.220ms, p95 2.213ms),
wrong_person_get_contact 0, stale_id_executions 0.

> **이 수치를 현재 코드의 멀티턴 성적으로 인용하면 안 된다.** 세 출처 중
> `ContactTurnReferenceResolverTest`가 대상으로 삼는 `ContactTurnReferenceResolver`는 `CLAUDE.md`
> 기준 **deprecated이며 커널에 연결돼 있지 않다.** 지금의 reference 해소 권한은
> `DeterministicTurnRouter` 단독이다.

### 2.2 이 저장소의 Python 스크립트 — 참조 원본이 아님

`tools/agent_eval/run_desktop_gemma_multiturn.py` (24,672 B),
`run_desktop_gemma_multiturn_v3.py` (31,773 B). 이 저장소 소유이며, 참조 저장소의 원본으로
**대체하지 않았다.** 정적 감사도 `--help`도 이들에 대해 수행하지 않았다 — 감사 대상이 아니기 때문이다.

### 2.3 이미 존재하는 Kotlin 멀티턴 평가 장치

전제는 "production Kotlin 멀티턴 adapter가 아직 없다"였고 그 자체는 맞다. 다만 **평가 장치는 이미
상당 부분 있다.** 없는 것은 *원본 dataset을 이 장치에 먹이는 adapter*다.

`eval/MultiturnSpec.kt`(case·turn schema), `eval/StrictMultiturnEvaluator.kt`(채점기),
`MultiturnScenarioHarness.kt`(production kernel 하네스), `eval/args/JsonArgumentComparator.kt`
(재귀 argument 비교), `eval/clock/EvaluationClock.kt`(결정적 clock),
`eval/contract/OutcomeContract.kt`, 그리고 동결된 held-out v1/v2/v3.

`TurnSpec`은 §8 체크리스트의 대부분을 이미 갖고 있다 — `expectedAct`, `expectedOutcome`,
`expectedTools`(순서 있는 정확한 trace), `forbiddenTools`, `expectedArgs`, `expectedSelectedCardId`,
`expectNoSelectedContact`, `expectedSelectedNotCardId`, `expectedCandidateIds`,
`expectedSideEffects`, `expectedComposeTo`, `answerMustContain/NotContain`, `resetBefore`.

---

## 3. 확정하지 않은 것 (§7·§8·§9)

원본이 없으므로 다음은 전부 **NOT DETERMINABLE**이며, 추측으로 채우지 않았다.

* entry point, argument parser, 기본 인자, dataset/fixture load 지점
* model/agent 생성, turn 실행, session reset, tool simulation
* case 채점, aggregate 계산, gate 적용, stdout/stderr, result write, exit code
* import 시점 자동 실행 여부, `__main__` 보호, 무거운 import 시점
* output이 `CREATE_NEW`인지 truncate인지, 고정 경로인지 인자인지, partial write 여부
* 실패해도 exit 0일 가능성, seed 고정, 실행 순서 determinism, 사후 gate 변경 가능성
* dataset 파일·SHA·schema·case 수·category 분포·turn 길이 분포·fixture 수
* 중복 ID·중복 발화·누락 기대값·미존재 card ID 참조·상충 fixture
* 모든 metric의 분자·분모·제외 조건·partial credit·rounding·empty denominator 처리
* gate threshold와 그 소재

`metric_coverage_matrix.json`의 20개 metric 행에서 **reference 열이 채워진 행은 0개**다.

`--help` smoke는 **NOT RUN**이다. 실행할 파일이 없다. `NOT SAFE TO RUN`이 아니라 대상 부재이며,
가짜 module이나 대체 script로 우회하지 않았다.

---

## 4. 확정한 것 — production 쪽 계약

`production_schema_comparison.json`에 20개 행을 만들고 **production 열을 전부 채웠다.** 참조 열은
전부 UNKNOWN이다. 나중에 원본이 오면 production 쪽을 다시 유도할 필요가 없다.

production contract 파일 9개와 평가 소스 7개의 경로·SHA를 고정했다.
등록 tool 6개(`search_contacts`, `get_contact`, `create_calendar_event`, `open_compose`,
`update_business_card`, `get_current_datetime`)와 `DialogueAct` 15값, `TurnOutcomeType` 10값을 기록했다.

### 원본을 보기 전에도 확정적인 번역 위험 3가지

1. **side-effect 의미.** production의 `expectedSideEffects`는 **작성 화면을 연 횟수**다.
   "보냈다"·"저장했다"를 뜻하는 원본 필드를 여기에 매핑하면 안 된다.
2. **argument 중첩.** `expectedArgs`는 `Map<String, Map<String, String>>`로 평면이라 중첩 객체·배열을
   담지 못한다. `JsonArgumentComparator`가 재귀 비교를 하므로 중첩은 그쪽으로 넘겨야 한다.
3. **미지정 ≠ 부정.** 원본에 focus 필드가 없다는 것은 "focus가 없어야 한다"가 아니라 "주장하지
   않는다"이다. 이를 `expectNoSelectedContact = true`로 바꾸면 원본에 없던 기대를 만들어내는 것이다.

추가로 typed outcome이 원본에 없다면 `expectedOutcome`을 **비워 둬야** 한다. boolean pass/fail에서
typed outcome은 복원되지 않는다.

### category 매핑은 미정

과거 결과 요약의 분류(`focus_maintenance`, `focus_switch`, `pronoun_and_ellipsis`,
`numeric_new_search`, `unrelated_context_isolation`, `honorific_cases`, `ordinal_selection`,
`duplicate_name_safe_handling`, `zero_result_safe_handling`, `three_turn_repeat_stability`)와
현재 `EvalCategories`의 8개(`reference_resolution` 36, `slot_and_correction` 36,
`tool_and_workflow` 36, `action_vs_information` 36, `ambiguity_and_grounding` 28,
`failure_and_idempotency` 24, `unsupported_and_adversarial` 12, `result_response_consistency` 12
— 합계 floor 220)는 **이름이 하나도 겹치지 않는다.** 전자는 서로 겹치는 분류이고 후자의
`primaryCategory`는 분모를 한 번만 세려고 단일값으로 설계됐다. 매핑은 원본 정의를 보고 정해야 한다.

---

## 5. 실행 가능성 판정

| 판정 | 해당 | 근거 |
|---|---|---|
| RUNNABLE NOW | ✗ | evaluator·dataset이 없다 |
| RUNNABLE WITH ISOLATED OUTPUT PATCH OR WRAPPER | ✗ | 감쌀 대상이 없다 |
| **BLOCKED BY DEPENDENCY/RESOURCE** | **✓** | 필요한 resource가 참조 저장소 자체다. dependency 질문을 포함한다 |
| NOT A PRODUCTION EVALUATOR | **미정** | 원본을 읽지 못했으므로 어느 쪽으로도 주장하지 않는다 |

### 해소 조건 (택1)

1. `../agent_integration_references_20260817/ryeong-llm-integration` 체크아웃을 제공한다.
2. ryeong `llm-integration-work` 체크아웃의 실제 경로와 branch를 알려준다.
3. 원본 멀티턴 evaluator가 애초에 Python이 아니었고
   `multiturn-final-20260801.json`이 가리키는 Kotlin JUnit suite가 그 원본임을 확인해 준다.
   (이 경우 §7~§9은 Python 감사가 아니라 Kotlin 감사로 다시 정의된다.)

---

## 6. 무결성

| 항목 | 값 |
|---|---|
| 참조 저장소 수정 | **0** (감사 대상 부재. `../ryeong` 체크아웃 9개 파일 SHA **불변**, `git status` 2건 그대로) |
| 참조 저장소 `__pycache__` 또는 출력 파일 생성 | **0** |
| production/test source (217개 `.kt`/`.java`) | **불변** |
| 보호 evidence (741개) | **불변** |
| run_1 / run_2 / run_3 | **불변** |
| run_3 invocation count | **1 → 1** |
| debug APK SHA | **불변** (`e4bfa60a…`) |
| JVM XML | **85개 그대로**, 실행하지 않음 |
| 금지 git 명령 | **0회** |
| dependency 설치·model 다운로드 | **0** |
| Gradle·JVM·APK·emulator·run_3 실행 | **전부 하지 않음** |

모든 Python 호출에 `PYTHONDONTWRITEBYTECODE=1`을 사용했다. 작성한 파일은 이번 evidence 경로
안에만 있다.

---

## 7. 이 판정이 의미하지 않는 것

* production 코드에 문제가 있다는 뜻이 아니다.
* 원본 evaluator가 잘못됐다는 뜻이 아니다 — **읽지 못했다.**
* 로컬 결과 요약(40/40)이 현재 코드의 멀티턴 성적이라는 뜻이 아니다.
* 검색 run_3 수치를 멀티턴 수치로 쓸 수 있다는 뜻이 아니다.
* actual Gemma·actual EmbeddingGemma 평가와 무관하다 — 둘 다 여전히 미실행이다.

**감사에서 종료하고 다음 작업으로 넘어가지 않았다.** adapter를 구현하지 않았고, 원본 evaluator를
실행하지 않았으며, production을 한 줄도 수정하지 않았다.
