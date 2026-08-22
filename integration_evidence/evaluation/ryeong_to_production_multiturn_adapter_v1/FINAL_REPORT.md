# Ryeong 멀티턴 계약의 production Kotlin 이식 — 공식 실행 직전 준비

## 1. 최종 판정 — 세 축

| 축 | 판정 |
|---|---|
| **RYEONG SCENARIO EXPORT** | **PASS** |
| **PRODUCTION ADAPTER READINESS** | **PARTIALLY READY** |
| **OFFICIAL RUN READINESS** | **PARTIALLY READY** |

이 완료는 **"현재 에이전트의 멀티턴 성능이 좋다"는 뜻이 아니다.** 어떤 Ryeong 수치도 측정하지 않았다.

---

## 2. Ryeong scenario export — PASS

upstream `build_scenarios()`를 **재구현하지 않고 그대로 호출**해 중립 JSON manifest를 만들었다.
서버·모델 호출 0회.

```
scenarios=130  turns=377  kinds=21  known_gap=0  generate_only=6
depth 1=3  2=62  3-5=55  6-10=10  11+=0
sha256 c5c238884652ab7351b7384bef0ac6ba0eaa85de3428b29b2499372dfd563f42  (284,097 bytes)
```

exact gate **전부 통과**. 중복 scenario index 0, 중복 card ID 0, 미존재 gold card ID 0.

### 결정성 — 처음엔 실패했다

첫 시도에서 두 실행의 SHA가 달랐다. 원인은 upstream이 일부 `must` 대안 목록을 **set으로** 만들고
(`address_variants`, `company_variants`) CPython이 프로세스마다 문자열 해시를 무작위화하기 때문이었다.
exporter가 자기 자신을 한 번 re-exec하며 `PYTHONHASHSEED=0`을 고정해 해결했다.
**upstream은 한 글자도 고치지 않았다.** 채점은 `any()`라 대안 순서에 의미가 없으므로 안전하다.

이후 **독립 프로세스 4개에서 byte-identical**. upstream에 `__pycache__` 0개.

Kotlin `RyeongScenarioLoader`가 **같은 파일에서 같은 수치를 독립적으로 재확인**한다 —
130/377/21, depth 3/62/55/10, slot 턴 288, gold 턴 335, route 미검사 턴 13.

---

## 3. translation contract — 확정

| 판정 | 개수 | 항목 |
|---|---|---|
| `DIRECT` | 6 | focus/card ID, top-5 후보, conversation depth 등 |
| `DERIVED_FROM_PRODUCTION_TRACE` | 5 | route `search`·`followup`, DialogueAct, previous card ids, follow-up |
| `NOT_SCORABLE` | 14 | route 6종, field filter 3종, generation 관련 |
| `NOT_APPLICABLE` | 1 | typed outcome |

**이름이 비슷하다는 이유로 자동 매핑한 항목은 0개다.**

`abstain`은 production의 `CLARIFICATION_REQUIRED`와 `UNSUPPORTED`로 갈리고 `context_answer`는
`ANSWER_FROM_HISTORY`와 `QUOTED_RECALL`로 갈린다 — 1:다를 억지로 접으면 정책을 adapter가 결정하게 된다.
`empty_result`는 축 자체가 다르다(upstream은 결과 수를 route에, production은 outcome에 담는다).

**금지 사항 준수:** 응답 문자열에서 route 역추론 0, 기대 route로 실제 route 생성 0, gold를 focus로
주입 0, 기대 slot을 query state로 주입 0, 결과 0건일 때 gold 추가 0, case ID·문장·이름·card ID 분기 0.

---

## 4. production adapter — 실제 경로를 쓴다

```
frozen JSON → RyeongScenarioLoader → RyeongCompatibilityRunner
  → 실제 AgentKernel · 실제 DeterministicTurnRouter · 실제 InMemoryAgentSessionStore
  · 실제 ConversationMemory · 실제 DefaultToolRegistry(6 plugin)
  · 실제 RyeongContactSearchBackend → 실제 SearchLookupService
  → TracingContactBackend(read-only) → RyeongCompatibilityScorer → RyeongRunGate
```

`MultiturnScenarioHarness`에 **기본값 있는 선택 인자 하나**(`searchBackendFactory`)를 더해
production 검색을 주입했다. 기존 호출자는 전부 그대로다. `TracingContactBackend`는 질의를 바꾸지 않고
결과를 재정렬·절단·치환하지 않으며 없는 ID를 채우지 않는다 — 그래서 R@5가 그 아래 production
backend를 측정한다.

**production `src/main` 변경 0.** side-effect backend는 기록 가능한 fake로 격리했다.

### 관측 불가 항목은 `NOT_SCORABLE`

production `search_contacts`의 인자는 자유 텍스트 `query` 하나뿐이고, 구조화된 조건은 `search-core`의
package-private `SearchFieldConstraintPlan`에서 계산된다. 노출 seam이 없다.

따라서 **JGA와 슬롯 P/R/F1은 `NOT_SCORABLE`**이다. 빈 집합으로 채워 채점하지 않았다 —
그러면 "조건을 안 걸었다"와 "관측할 수 없다"가 같은 값이 된다. 필요한 seam은
`PRODUCTION_TRACE_CONTRACT.md`에 적었고 **별도 범위**다.

---

## 5. evaluator self-test와 mutation — 24/24 탐지

합성 fixture(이름·ID·문장 모두 frozen 130에 없음)로 24개 mutation을 전부 탐지했다.

* **관측 결함 6종**: 잘못된 route, focus 누락, 잘못된 focus ID, stale focus, gold가 top-5 밖,
  top-5에 잘못된 ID 삽입
* **격리 결함 5종**: focus leakage, memory leakage, session reset 누락, turn 순서 변경,
  **기대값을 실제값으로 주입**
* **side-effect 4종**: `open_compose`·`create_calendar_event`·`update_business_card` 실행,
  side effect 2회 — 전부 `unexpected_side_effect_tool`로 **route 실패와 분리**해 집계
* **결과 계약 5종**: 실패인데 exit 0, `result.partial`, scalar type 불일치, 제외 턴이 분모에 포함,
  모델 없이 generation 채점
* **slot 4종**: 해당 metric을 `NOT_SCORABLE`로 **이름을 붙여 선언**해 처리했다. 관측 seam이 없어
  손상시킬 slot 자체가 없고, scorer가 slot 수치를 아예 내지 않는다. 이것이 정직한 처리다.

`RyeongRunGate`가 upstream의 "실패해도 exit 0"을 가져오지 않는다 — 9개 거부 조건 중 하나라도 걸리면
non-zero다.

---

## 6. synthetic canary — green

frozen 130에 없는 합성 카드 4장·시나리오 8개로 배관만 확인했다(13개 assertion 통과).

loader → session lifecycle → production 경로 진입 → trace 수집 → focus 관측 → top-5 관측 →
structured result → exit code 계약. **cross-scenario leakage 0**, unexpected action tool 0,
같은 시나리오를 두 번 돌려도 동일, **실행 순서를 뒤집어도 시나리오별 결과 동일**.

retrieval mode는 `KEYWORD_ONLY`로 기록됐고 `semanticAvailable=false`다 —
**keyword fallback을 semantic으로 표기하지 않는다.**

### canary가 드러낸 것 — 공식 run 전에 반드시 알아야 할 사실

> `"제갈민씨 찾아줘"` → `DialogueAct.OTHER`, 도구 0회
> `"제갈민씨 명함 찾아줘"` → `CONTACT_SEARCH`, `search_contacts` 1회, ranking 반환

**Ryeong의 질문은 대부분 전자 형태다**(`"{이름}씨 회사가 어디야?"`). 즉 첫 공식 run의 routing 수치는
낮게 나올 것으로 예상해야 한다. 이는 **두 계약의 차이이지 adapter 결함이 아니며**, 이 예상을 근거로
production을 고치거나 frozen scenario를 고쳐 쓰면 안 된다.

canary는 배관 검증이 routing 점수를 겸하지 않도록 production이 실제로 처리하는 문구를 의도적으로 썼다.
**canary 결과는 공식 점수가 아니다.**

---

## 7. 전체 JVM — green, 정확히 1회

```
JAVA_HOME=… ./gradlew --offline test --rerun-tasks --continue
start 2026-08-22T12:04:32+0900 → end 12:04:48+0900, exit 0, stderr 0 bytes
87 / 87 actionable tasks executed
```

| 항목 | 값 |
|---|---|
| suites / tests | **88 / 659** |
| failures / errors / skipped | **0 / 0 / 0** |
| stale XML | **0** |
| 중복 testcase | **0** |
| 이전 대비 | **+3 suites / +51 tests** |

증가분은 신규 3개 클래스와 정확히 일치한다 — `RyeongScenarioLoaderTest` 14,
`RyeongEvaluatorMutationTest` 24, `RyeongAdapterCanaryTest` 13. 제거·rename 0.
모듈별로 `app`만 +3/+51이고 나머지 6개 모듈은 변화 없다.

---

## 8. coverage matrix

Ryeong 축은 **검색·문맥 추적의 하위 평가**다. 19개 행 중 대부분이 `NOT COVERED`다.

`NOT COVERED`: JGA, memory, target provenance, stale ID, clarification, get_contact, compose,
calendar, update, side effect, typed outcome, nested args, false completion.
`NOT RUN`: actual Gemma continuation.

**Ryeong 130개는 Production Agent Multiturn V4를 대신하지 않는다.** 두 축의 수치를 합치지 않는다.
기존 production 평가 계약은 하나도 약화하지 않았다.

---

## 9. freeze readiness

동결한 것: upstream commit, `eval_multiturn.py`·`cards_eval1000.json`·exporter·exported scenario·
loader·translation·adapter·scorer·gate schema·harness seam·self-test·canary SHA,
production source SHA map(217), JVM evidence SHA, seed 42, exact inventory, exact command.

아직 동결 못 한 것: **performance threshold**(사전 등록 필요, 결과를 본 뒤 정하지 않는다),
`RyeongOfficialCompatibilityRunTest`(아직 없음).

**공식 run invocation = 0.** frozen 130은 실행하지 않았다.

---

## 10. 무결성

| 항목 | 값 |
|---|---|
| 보호 evidence | **825 → 825**, changed 0 / removed 0 / added 0 |
| production `src/main` | **변경 0** |
| test `src/test` 변경 | **1** — `MultiturnScenarioHarness.kt` (기본값 있는 선택 인자 추가, 기존 호출자 불변) |
| 신규 test source | **8** |
| upstream clone | `git status` **clean**, 파일 수정 0, `__pycache__` 0, 필수 SHA 3개 불변 |
| `../ryeong` checkout | 손대지 않음 |
| run_3 invocation | **1 → 1** |
| debug APK SHA | **불변** (`e4bfa60a…`) |
| 공식 Ryeong result 디렉터리 | **생성 0** |
| 금지 git 명령 | **0회** |
| v1/v2/v3 dataset·결과·freeze·보고서 | **변경 0** |

---

## 11. 질문별 답

| 질문 | 답 |
|---|---|
| upstream 130개를 byte-deterministic JSON으로 export했는가? | **YES** (4 프로세스 동일) |
| 130/377/21 count가 정확히 일치하는가? | **YES** (Python gate + Kotlin loader 이중 확인) |
| current production `AgentKernel`을 실제로 사용하는가? | **YES** |
| search ranking과 focus를 production trace에서 관측하는가? | **YES** (read-only decorator, session memory) |
| evaluator가 gold/expected 값을 production 입력으로 주입하지 않는가? | **YES** — 주입하지 않는다 (mutation `expected_injected_as_actual`이 감시) |
| scenario 사이 session leakage가 없는가? | **YES** (canary 0건) |
| unexpected action Tool을 탐지하는가? | **YES** (route 실패와 분리 집계) |
| mutation 24개를 전부 탐지하는가? | **YES** (24/24, slot 4종은 `NOT_SCORABLE` 선언으로) |
| synthetic canary가 통과하는가? | **YES** (13/13) |
| 전체 JVM이 green인가? | **YES** (88 / 659 / 0 / 0 / 0) |
| actual Gemma를 실행했는가? | **NO** → generation metric = **NOT RUN** |
| frozen Ryeong 130개 공식 run을 실행했는가? | **NO** (invocation 0) |
| 공식 run을 실행할 준비가 됐는가? | **BLOCKED** — `RyeongOfficialCompatibilityRunTest` 미작성, threshold 미등록 |

---

## 12. 이 결과가 의미하지 않는 것

* 현재 에이전트의 멀티턴 성능에 대해 **아무것도 말하지 않는다.**
* 어떤 Ryeong metric도 측정하지 않았다.
* actual Gemma·actual EmbeddingGemma를 실행하지 않았다. 이번 검색은 **keyword-only**다.
* 원본 Ryeong 서버 재현과 무관하다 — 그 축은 여전히 dependency·모델 부재로 BLOCKED다.
* production readiness가 아니다.

완료 조건은 충족했다: **Ryeong의 검색 멀티턴 계약이 현재 production 경로에 정직하게 연결됐고,
evaluator 자체가 24개 mutation으로 검증됐으며, 공식 frozen run을 수정 없이 한 번 실행할 준비가
threshold 등록과 runner test 추가만 남긴 상태로 정리됐다.**
