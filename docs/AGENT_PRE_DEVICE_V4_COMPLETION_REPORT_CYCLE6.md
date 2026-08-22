# HJP v4 — cycle 6 결과 보고

이 문서는 cycle 6 지시(§1–§8)에 대한 결과다. cycle 5 보고서
(`AGENT_PRE_DEVICE_V4_COMPLETION_REPORT.md`)는 그대로 두고, 그 안의 잘못된 수치는 아래 §1에서
원시 데이터로부터 재계산해 정정한다.

**최종 판정: PARTIALLY COMPLETE.**

---

## 0. 상태 요약 (7개 축을 분리)

| 축 | 상태 | 근거 |
|---|---|---|
| production regression | **PASS** | 신선한 JVM 전체 351건 0 실패. frozen v1/v2/v3 gate 전부 통과 |
| dataset integrity | **PASS (원본 무수정)** | v3 freeze·v2 freeze의 dataset SHA-256 전부 일치. 덮어썼던 `heldout_v3_validation.json`은 원본 바이트로 복구(`14db7bb0…`) |
| evaluator reproducibility | **PASS** | injected `EvaluationClock`. 재현성 테스트 8건 통과 |
| fresh JVM | **PASS** | 61 suite / 351 test / 0 fail / 0 error / 0 skip, `--rerun-tasks`, 이전 XML 전량 삭제 후 |
| deterministic v4 | **NOT DONE** | v4 dataset·runner·gate를 만들지 않았다 |
| actual Gemma | **RUNTIME 확보, 평가 미수행** | 엔진 로드·native tool call 동작 확인. 공식 run 없음 |
| ARM64 physical device | **NOT DONE** | 연결된 기기 없음 |

---

## 1. v3 지표 재조정 (§1)

cycle 5 보고서는 "결함 20건 → 3건"과 "strict scenario 60/80 → 68/80"을 함께 적었다. 이 둘은 서로
맞지 않는다. 원인은 68/80이 exact-first 이름 수정 **이전**의 중간 측정값인데 최종 표에 그대로
옮겨졌기 때문이다. 원시 결과에서 다시 계산한 값은 다음과 같다.

| 항목 | frozen v3 공식 | v4 수정 후 재실행 |
|---|---|---|
| strict scenario | 60/80 | **77/80** |
| strict turn | 209/248 | **244/248** |
| 실패 assertion | 243 | **4** |

남은 4건은 전부 `tool_argument:create_calendar_event.start_time`이고, 값이 정확히 7일 어긋난다
(`exp=2026-08-11T14:00 act=2026-08-18T14:00`). 즉 agent 결함이 아니라 dataset의 기준일과 실행일의
차이다. §3의 clock으로 해소했고, 기준 clock에서 재생하면 **80/80**이다.

산출물: `tools/agent_eval/results/pre_device_v4/v3_failure_reconciliation.json`

### "20건 전부 해소" 라는 표현을 쓰기 위한 4개 전제

| 전제 | 결과 |
|---|---|
| 20건과 수정 사항의 1:1 대응 | 충족 (20 resolved, 0 still failing) |
| 남은 실패가 production 결함이 아님 | 충족 (4건 전부 clock drift, §3에서 해소) |
| missing-slot typed outcome 검증 | **미충족** — §6.2 참조 |
| wrong/stale/unverified target, forbidden value 전부 0 | 충족 (frozen v2·v3 replay에서 zero-tolerance 전 항목 0) |

세 번째가 충족되지 않았으므로 이 보고서는 **"v3 결함 20건 전부 해소" 라고 쓰지 않는다.**

---

## 2. frozen artifact 무결성 (§2)

`heldout_v3_validation.json`은 이전 cycle에서 suite 재실행으로 덮어썼다. git object
`58a8b0b6fbb520b40c8e338e86642b9963716987`에서 원본 바이트를 찾아 복원했고, SHA-256이
`14db7bb0…`로 정확히 일치한다. 따라서 `ORIGINAL ARTIFACT LOST` 가 아니라 **복구 완료**다.

재발 방지가 더 중요하다. replay가 frozen 디렉터리에 보고서를 쓰던 것이 원인이었으므로, 쓰기 경로를
run 단위 경로로 옮겼다.

- `HeldoutV3ValidationTest.V3_RESULT_DIR` → `pre_device_v4/replay/heldout_v3`
- `HeldoutV2ValidationTest.V2_RESULT_DIR` → `pre_device_v4/replay/heldout_v2`
- frozen 경로는 `V3_FROZEN_DIR`로 남겨 두되 아무도 쓰지 않는다.

전체 suite를 `--rerun-tasks`로 다시 돌린 뒤에도 v2·v3 freeze manifest의 dataset SHA는 **전부
일치**한다(불일치 0건).

산출물: `pre_device_v4/frozen_artifact_integrity.json`,
`pre_device_v4/replay/evaluator_sha_before_clock_change.json`

---

## 3. evaluation clock (§3)

`app/src/test/java/com/example/hjp/eval/clock/EvaluationClock.kt`.

- production은 system clock을 그대로 쓴다(`EvaluationClock.system()`이 기본값이므로 기존 호출자는
  바이트 단위로 동일하게 동작한다).
- frozen replay는 dataset이 선언한 기준 instant를 주입한다
  (`EvaluationClock.fixedAt(REFERENCE_DATE, zoneId = Asia/Seoul)`).
- harness가 `GetCurrentDateTimePlugin(clock.millis)`로 도구까지 같은 clock을 흘린다.

`EvaluationClockReproducibilityTest` 8건이 이 성질을 고정한다. 그중 하나는 오늘 실행하면서도
"내일 = 2026-08-11"을 주장한다 — clock이 없으면 2026-08-18이 나오므로, 이 테스트가 통과한다는 것
자체가 재현성의 증거다. 시간대도 clock의 일부로 고정한다(같은 instant가 UTC에서는 전날이다).

**주의:** 토큰 수와 마찬가지로 이 clock은 평가용이다. production 경로에는 fixed clock이 들어가지
않으며, `production keeps the system clock` 테스트가 그것을 강제한다.

---

## 4. v2 자기모순 fixture (§4)

원본은 고치지 않았다. 일반 규칙으로 탐지한다.

`app/src/test/java/com/example/hjp/eval/v2/DatasetConsistency.kt`는 required∩forbidden 충돌을
tool·value·card_id·target 축에서 찾는다. 특정 시나리오 id 목록이 아니다.

- 전체 91건 중 **4건이 `DATASET_INVALID`** (`required_and_forbidden_value`)
- **유효 denominator 87**, 이 87건은 전부 통과
- 4건의 id와 사유는 `pre_device_v4/replay/heldout_v2/heldout_v2_dataset_consistency.json`에 공개

### v2.1 errata — 의도가 유일하게 결정되는가

결정된다. 세 시나리오가 같은 family다: 연락처를 focus에 넣고, 참석자 없는 일정을 요청해, 그 일정이
그 사람을 조용히 상속하지 않는지 본다. `update`·`detail` 변형은 모순이 없고 `compose` 변형만
모순이다. 차이는 focus를 만드는 턴이 그 주소를 **쓰는지** 뿐이다. 따라서 `forbiddenValues`는
검사 대상 턴을 지키려고 쓴 것이고, 수정은 하나로 정해진다: **값을 요구하지 않는 턴에서만 금지한다.**

이 규칙이 느슨해진 것이 아니라는 근거는 테스트로 고정했다: 만족 가능한 87건 전부에서 원래 규칙과
**동일하게 판정**한다(`the corrected rule changes nothing for any scenario the original could
satisfy`). 4건은 corrected 규칙에서 전부 통과한다(leak 0).

산출물: `pre_device_v4/v2_corrected/v2_errata.json`,
`pre_device_v4/v2_corrected/heldout_v2_corrected_results.json`

---

## 5. JVM 테스트 재구성 (§5)

| 종류 | 대상 |
|---|---|
| historical replay (frozen 계약 그대로) | `HeldoutV2RunnerTest`, `HeldoutV3RunnerTest`, `FrozenHeldoutRunnerTest` |
| dataset validation | `HeldoutV2ValidationTest`, `HeldoutV3ValidationTest`, `DatasetConsistency` |
| versioned replay (fixed clock) | 위 replay가 기준 clock으로 재생. `EvaluationClockReproducibilityTest` |
| corrected v2.1 regression | `HeldoutV2CorrectedRunnerTest` |

`HeldoutV2RunnerTest`·`HeldoutV3RunnerTest`의 변경 내역과 사전 SHA는
`pre_device_v4/replay/evaluator_sha_before_clock_change.json`에 있다. 변경의 성격은 다음과 같다.

- 기대값·floor·zero-tolerance 항목을 **하나도 낮추지 않았다**.
- 추가된 것은 (a) dataset이 선언한 기준 clock으로 재생, (b) 자기모순 fixture의 탐지·제외·**공개 집계**,
  (c) 보고서 쓰기 경로를 frozen 밖으로 이동 — 셋 다 재현성·무결성 강화다.
- 원래 공식 결과를 pass로 다시 쓰지 않았다. frozen 결과 파일은 손대지 않았다.
- 무효 fixture를 조용히 빼지 않았다. 개수(4)와 유효 denominator(87)를 결과 JSON과 이 문서에 적었다.

`./gradlew --offline test --rerun-tasks --continue`, 이전 XML 전량 삭제 후:
**61 suite / 351 test / 0 failure / 0 error / 0 skipped.**
산출물: `pre_device_v4/jvm_test_suites.json`

---

## 6. 미검증 production 영역 (§6)

### 6.1 target fail-closed — **결함 1건 발견·수정**

`TargetFreshResolutionTest` 5건 추가. 과거에 언급됐거나 이미 `get_contact`한 사람이라도, 이번 턴의
대상은 이번 턴에 다시 해소해야 한다는 계약이다.

발견한 결함: `PersonNameMask.nameSpans`는 이름이 `에게/한테/님/씨` 또는 명함 명사 앞에 올 때만
이름으로 본다. `남지후도`, `남지후는`, `남지후라는` 처럼 **보조사·주제격·인용형 뒤에 오는 언급을
놓쳤고**, 그 결과 stale focus가 회수되지 않아 다음 턴의 지시대명사가 **이전 사람**에게 도달했다.
캘린더 채널에서도 같은 경로로 이전 사람이 참석자로 붙었다.

수정: 마스킹용 탐지기와 안전 가드용 탐지기를 분리했다. 마스킹은 보수적이어야 하고(아닌 것을 지우면
키워드가 사라진다) 가드는 넓어야 한다(놓치면 잘못된 수신자에게 메시지가 간다). `mentionSpans`를
추가해 가드만 그것을 쓴다. 회귀 없음.

### 6.2 missing-slot typed outcome — **부분 구현, 나머지는 채택하지 않음**

행위 계약은 테스트로 고정했다(`MissingSlotOutcomeTest` 6건): action tool 0회, draft 0건, 응답이
빠진 슬롯을 지목, 다음 턴에 넘길 target을 남기지 않음, 슬롯을 채우면 통과.

typed outcome을 `CLARIFICATION_REQUIRED`로 바꾸는 부분은 **채택하지 않았다.** 구현해서 측정한 뒤
되돌렸다. v1·v2·v3 frozen dataset과 visible suite가 이 턴들의 `outcome_type`을
`GENERAL_INFORMATION`으로 고정하고 있어, 채택하면 frozen gate가 깨진다.

| suite | 채택 시 관측값 | floor | 결과 |
|---|---|---|---|
| FrozenHeldout | `heldout_required_slot` 19/23 | 0.95 | FAIL |
| Heldout v2 | `formal_contract_turn` 243/250 = 0.9720 | 0.975 | FAIL |
| Heldout v3 | `formal_turn` 237/248 = 0.9556 | 0.975 | FAIL |
| Visible | strict 실패 10/252 | — | FAIL |
| KnownRegression | strict 실패 1/10 | — | FAIL |

frozen dataset을 고치는 것도, floor를 낮추는 것도 금지된 선택지다. 따라서 이 변경은 v1/v2/v3를
다시 baseline 잡겠다는 결정이 있어야 들어갈 수 있다. 참고로 **frozen 기대값 자체가 자기 설명과
어긋난다**: 해당 시나리오의 category는 `required_slot_and_clarification`이고 `answer_contains`는
빠진 슬롯을 지목하라고 요구하는데, typed outcome만 clarification이 아니라고 말한다.

측정 근거: `pre_device_v4/contract_change/missing_slot_outcome_typing.json`

### 6.3 이름 충돌 oracle 독립화 — **결함 1건 발견·수정**

기존 `PersonNameCollisionTest`는 `PersonNameMask`의 domain token·마스킹·조사 규칙을 정답 근거로
쓴다. 그러면 마스크가 스스로와 모순될 때만 실패하고, 공통 맹점은 보이지 않는다.

`PersonNameIndependentOracleTest` 8건은 production 이름 로직을 **전혀 호출하지 않는다.**
문장을 읽고 사람이 판단한 기대값만 쓰고, production 진입점은 `DeterministicTurnRouter.act` 하나다.
희귀 성씨(남궁·황보·독고·선우·제갈), 음차 외국 이름, 띄어 쓴 이름, 긴 이름, 조사로 끝나는 이름
(…도/은/이/라/만), 동작어와 충돌하는 이름, 회사명·직함을 덮는다.

발견한 결함: `메일리 명함 보여줘.`가 `CLARIFICATION_REQUIRED`로 갔다. 라우터의 attribute-starter
검사만 마스킹되지 않은 원문을 읽고 있어서, **속성어로 시작하는 이름**(메일리, 주소연 …)은 자기
조회가 기억된 연락처에 대한 질문으로 바뀌고, 기억이 비어 있으면 "누구를 말하냐"고 되물었다. 즉 그런
이름을 가진 사람은 검색 자체가 불가능했다. 다른 키워드 검사와 동일하게 마스킹된 문장을 읽도록 고쳤다.

---

## 7. 이월 작업 (§7) — 대부분 미수행

| 항목 | 상태 |
|---|---|
| actual Gemma runtime | **확보.** litert_lm 0.16.0 설치, 엔진 로드 0.3s, `get_current_datetime` native tool call 발행, 주입한 clock 값으로 응답. 모델 SHA-256이 frozen v3 기록과 일치 |
| production runtime의 multi-tool continuation | 미수행 |
| evaluator 중첩 인자 재귀 비교 | 미수행 |
| tool-semantic false-completion 탐지 | 미수행 |
| evaluator mutation/self-test | 기존 `MutationTest`·`HeldoutV3EvaluatorSelfTest`는 통과하나, 이번에 추가한 항목에 대한 mutation은 미작성 |
| Gemma 7개 gate | 미수행 |
| v4 dataset·runner·gate 사전 freeze | 미수행 |
| 첫 공식 deterministic v4 run | 미수행 |
| Gemma 사전 freeze·공식 run·사후 SHA 재검사 | 미수행 |
| ARM64 실기기 | 미수행 (기기 없음) |

산출물: `pre_device_v4/actual_gemma/runtime_availability.json`
(이 파일은 **평가가 아니다.** 시나리오를 채점하지 않았고 gate를 판정하지 않았다.)

---

## 8. 쓰지 않은 표현

증거가 없으므로 이 보고서는 다음을 주장하지 않는다: "v3 결함 20건 전부 해소", "일반화 증명",
"JVM green" 을 넘어선 완결 주장, "production ready", "fully complete".

`frozen 원본 무손상`에 대해서는 사실만 적는다: 이전 cycle에서 `heldout_v3_validation.json` 1건을
덮어썼고, git object에서 원본 바이트를 SHA-256 일치로 복구했으며, 이후 재실행에서 frozen dataset
불일치는 0건이다.

**최종 판정: PARTIALLY COMPLETE.** §1–§6은 수행했고, §7은 runtime 확보를 제외하면 미수행이다.

---

## 부록 — 변경한 production 파일

| 파일 | 변경 |
|---|---|
| `agent-core/.../TurnIntentLexicon.kt` | `PersonNameMask.mentionSpans` 추가(가드 전용 넓은 탐지기), `RequiredSlotIntent` 추가 |
| `agent-core/.../TurnContactTargetResolver.kt` | fail-closed 가드가 `mentionSpans`를 읽도록 변경 |
| `agent-core/.../DeterministicTurnRouter.kt` | attribute-starter 검사가 마스킹된 문장을 읽도록 수정 |

## 부록 — 추가한 테스트

| 파일 | 건수 |
|---|---|
| `app/src/test/.../eval/clock/EvaluationClockReproducibilityTest.kt` | 8 |
| `app/src/test/.../eval/v2/HeldoutV2CorrectedRunnerTest.kt` | 4 |
| `app/src/test/.../v4/TargetFreshResolutionTest.kt` | 5 |
| `app/src/test/.../v4/MissingSlotOutcomeTest.kt` | 6 |
| `agent-core/src/test/.../PersonNameIndependentOracleTest.kt` | 8 |

새 지원 코드: `eval/clock/EvaluationClock.kt`, `eval/v2/DatasetConsistency.kt`, `eval/v2/V2Errata.kt`
