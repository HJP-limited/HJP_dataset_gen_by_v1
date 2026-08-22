# HJP — cycle 7 결과 보고

cycle 5·6 보고서와 v1/v2/v3 frozen 자료는 수정하지 않았다. cycle 7 산출물은 전부
`tools/agent_eval/results/pre_device_v5_cycle7/` 아래에 있다.

**최종 판정: PARTIALLY COMPLETE.**

---

## 0. 상태 요약

| 축 | 상태 | 근거 |
|---|---|---|
| 기존 결과 보존 | **PASS** | frozen dataset SHA 불일치 0건, `heldout_v3_validation.json` = `14db7bb0…` 유지 |
| 결과 표기 분리 | **PASS** | v3의 세 결과를 SHA·clock·공식여부까지 분리 기록 |
| typed outcome v4 계약 | **PASS** | production이 항상 v4. legacy는 번역 계층으로 해석 |
| 계약 버전 분리 | **PASS** | `LEGACY_V1_V3` / `V4`, 번역 사용처 전량 기록 |
| legacy migration 자료 | **PASS** | 55건(v1 3, v2 29, v3 23) |
| v2 invalid 4건 | **부분** | 보존·탐지·v2.1 검증은 완료. 케이스별 문맥 서술은 미완 |
| mentionSpans 안전성 | **PASS** | 안전 문장 9/9 탐지, 오탐률 40%→**10%** 측정 |
| actual Gemma continuation | **PASS** | production kernel/workflow에 구현, 12건 테스트 |
| evaluator 강화 | **부분** | false completion 완료. 중첩 인자·mutation 14종 미완 |
| 7개 gate | **NOT DONE** | v4 dataset이 없어 채점 대상이 없음 |
| fresh JVM | **PASS** | 66 suites / **387 tests** / 0 fail / 0 error / 0 skip |
| 신규 frozen v4 | **NOT DONE** | |
| actual Gemma 공식 실행 | **NOT DONE** | runtime만 확보 |
| 물리 ARM64 | **NOT DONE** | 기기 없음 |

---

## 1. 기준선 기록 (§1)

`pre_device_v5_cycle7/baseline/cycle7_baseline.json`

- Git HEAD `993a5f561323…`, branch `android-app`, 커밋 없음
- `git status --short` 80개 항목 전량 기록
- cycle 6 결과 61 suites / 351 tests / 0 fail, XML 경로 전량 기록
- 복구된 v3 validation SHA `14db7bb0…`
- production 소스 66개 파일 개별 SHA + 집계 `d32c9f36…`

종료 시점: `baseline/cycle7_final_state.json`, production 집계 `1253e1a2…`

---

## 2. v3 세 결과의 분리 (§2)

`results_labelling/v3_three_results.json`

| | 공식 v3 | 현재 production, wall clock | 현재 production, frozen clock |
|---|---|---|---|
| 종류 | **OFFICIAL** | DIAGNOSTIC | DIAGNOSTIC |
| strict scenario | 60/80 | 77/80 | 80/80 |
| strict turn | 209/248 | 244/248 | 248/248 |
| 실패 assertion | 243 | 4 | 0 |
| reference clock | 실행 당시 시스템 시각 | 기기 wall clock (2026-08-17) | 고정 2026-08-10 (Asia/Seoul) |
| evaluator SHA | `5677ec33…` | `4d0cbaf5…` | `4d0cbaf5…` |
| dataset SHA | 세 결과 모두 동일 | 동일 | 동일 |

정확한 표현은 **`current production passed 80/80 under the frozen replay clock`** 이다.
`v3가 60/80에서 80/80으로 바뀌었다`는 서로 다른 세 측정을 하나로 뭉갠 문장이므로 쓰지 않는다.
wall-clock 77/80의 남은 4건은 전부 `create_calendar_event.start_time`이 정확히 7일 어긋난 것으로,
dataset 기준일과 실행일의 차이지 agent 동작이 아니다.

---

## 3. typed outcome v4 계약 (§3)

### 3.1 production

`ModelDecision.FinalCandidate`에 `clarification: ClarifyReason?`을 추가했다. 필수 slot이 없어
답변한 경계는 이 값을 채우고, `AgentKernel`이 그 턴을 `NEEDS_CLARIFICATION`으로 종료한다.
production은 **언제나** 이 계약을 쓴다. legacy dataset 버전에 따라 동작을 바꾸지 않는다.

### 3.2 계약 버전 분리

`app/src/test/java/com/example/hjp/eval/contract/OutcomeContract.kt`

- `LEGACY_V1_V3` — v1/v2/v3의 공식 결과를 해석·보존하기 위한 계약
- `V4` — 현재 계약. 정확히 일치해야 한다

번역은 **단 하나**의 치환만 허용한다: dataset이 `GENERAL_INFORMATION`을 기대했고, 관측이
`CLARIFICATION_REQUIRED`이며, **그 턴이 도구를 하나도 실행하지 않았을 때**. 도구가 돌았거나
성공을 주장한 턴은 여전히 실패한다(테스트로 고정). 역방향 치환도 허용하지 않는다.

세 평가기가 모두 이 한 곳을 통과한다: `HeldoutV2Evaluator`, `HeldoutV3Evaluator`,
`StrictMultiturnEvaluator`(v1 frozen runner가 `LEGACY_V1_V3`를 명시).
frozen이 아닌 visible·known-regression 데이터셋은 v4 기대값으로 올렸다.

### 3.3 legacy migration 자료

`contract/legacy_outcome_contract_migration.json` — **55건** (v1 3, v2 29, v3 23).
각 항목에 dataset version, case/turn ID, category, legacy/v4 expected outcome, required slot,
기존 answer assertion, tool·side-effect 기대값, 근거를 담았다. 원본은 수정하지 않았다.
번역이 실제로 사용된 지점은 `contract/legacy_outcome_translations_observed.json`에 별도 기록한다.

변경 전 evaluator/runner SHA: `contract/evaluator_sha_before_contract_split.json` (8개 파일).

### 3.4 필수 테스트

`OutcomeContractV4Test` 8건 — compose/sms/calendar/update(필드)/update(대상) 각각에 대해
typed outcome, 정확한 누락 slot 질문, action tool 0회, side effect 0건, focus 미오염,
완료 표현 금지를 **동시에** 검증한다. 더해 legacy와 v4가 같은 관측을 서로 다른 이유로 판정한다는
사실, 번역이 회귀를 숨길 수 없다는 사실을 각각 고정한다.

---

## 4. v2 자기모순과 v2.1 (§4)

원본 4건은 그대로 `DATASET_INVALID`로 보존된다. cycle 6에서 만든 v2.1 corrective 평가는
4건 전부 leak 0으로 통과하고, 정상 87건에서 원래 규칙과 동일 판정임을 테스트로 증명한다.

**미완:** §4가 요구한 케이스별 서술 — 전체 대화 문맥, original required/forbidden value, 충돌 경로,
가능한 수정 후보 열거, 어느 값을 고쳐야 하는지와 그 유일성 근거 — 은 작성하지 않았다.
현재 근거는 "동일 family의 자매 시나리오 2건이 같은 패턴인데 모순이 없다"는 구조적 논증과
"정상 87건에서 판정이 동일하다"는 실험적 논증 두 가지뿐이다. 지시는 후자만으로 유일성을
주장하지 말라고 했으므로, **유일성 주장은 이 보고서에서 하지 않는다.**

---

## 5. mentionSpans 안전성 (§5)

`agent-core/src/test/.../MentionSpanSafetyTest.kt` 7건. 자연스러운 한국어 문장만 사용했다.

탐지해야 하는 형태 9종(도/은/는/라는/이라는/에게/한테/님/씨) **9/9 탐지**.
그 과정에서 두 결함을 고쳤다.

1. `씨/님`이 띄어 쓰인 경우(`남지후 씨는`)를 놓쳤다 — 경칭 뒤에 조사가 붙으면 매칭이 깨졌다.
2. 조사만으로 판단하면 오탐이 폭증했다. `도/은/는/랑/하고/과/와`는 한국어에서 거의 모든 명사에
   붙어서 `회의록도`, `일정은`, `너울건설도`, `미뤄도`가 전부 이름으로 잡혔다(**오탐 40%**).

그래서 표지를 강·약으로 나눴다. 인용형(`이라는/라는/이란/란`)과 띄어쓴 경칭은 무조건 인식하고,
약한 조사는 문장이 사람에 대해 말하고 있을 때만 인식한다. 이름 길이도 2–4음절로 제한해
`영업본부장`·`품질관리팀` 같은 직함 복합어를 구조적으로 배제했다.

**측정된 불필요 focus 은퇴율: 1/10 = 10%** (이전 40%). side effect를 허용하는 방향으로 완화하지
않았다 — 오탐의 대가는 되묻기 한 번이고, 미탐의 대가는 잘못된 수신자다.

간접 언급(인용·회상·가정·제외·비교) 5종도 별도 측정한다.

---

## 6. actual Gemma multi-tool continuation (§6)

runner 전용 patch가 아니라 **production 경로**에 구현했다.

- `AgentWorkflowSession.pendingTerminalTool()` — 이 턴이 아직 갚지 않은 terminal tool을 알려준다.
  안전할 때만 알려준다: 요청이 그 동작이고, 아직 실행되지 않았고, validator가 필요로 하는
  수신자·시각이 갖춰졌을 때. 아니면 null이고 기존 clarification 경로가 처리한다.
- `AgentWorkflowSession.continuationPrompt()` — 일반 tool-result 채널로 보내는 repair 지시.
  새 user turn이 아니므로 native 대화가 사용자 발화로 오염되지 않는다.
- `AgentKernel` — 산문으로 끝났는데 terminal tool이 남아 있으면 **bounded repair**를 1회
  수행한다(`AgentTurnPolicy.maxWorkflowRepairs = 1`, 전체 tool 상한과 함께 적용).

`MultiToolContinuationTest` 12건: search→get, search→get→compose/calendar/update,
get 후 산문→repair→terminal tool, repair 후에도 산문→fail-closed, repair 유계성,
missing slot→clarification, stale target→action 0, correction 후 새 chain, cancel→action 0,
side effect 중복 0.

이 과정에서 **결함 1건**을 더 찾았다. workflow가 수신자 표지로 `에게/한테/와/과`만 인식해서
`표하윤이랑 일정 잡아줘`가 "연락처가 필요 없는 요청"으로 거부됐다. `이랑/랑/하고/께`를 추가했다.
`이랑/랑/하고`는 `와/과`의 일상 구어형이므로 인식하지 않을 이유가 없다.

---

## 7. evaluator 강화 (§7)

### 7.2 Tool 의미 기반 completion — 완료

`open_compose`·`create_calendar_event`는 **화면을 연 것**이고 전송·저장이 아니다.
`update_business_card`만 실제로 쓴다. 그래서 금지어 목록이 아니라 **도구별 의미 대응**으로 구현했다:
같은 문장이 어떤 도구 뒤에서는 참이고 다른 도구 뒤에서는 거짓이다.

- 화면만 연 도구 뒤의 완료 주장 → 정확한 표현으로 교체
- side-effect 도구가 하나도 성공하지 않았는데 완료 주장 → 교체
- backend 실패 후 완료 주장 → 교체
- 참인 보고("작성 화면을 열었습니다")와 읽기 답변은 건드리지 않는다

`FalseCompletionTest` 6건.

### 7.1 중첩 인자 재귀 비교 — **미완**
### 7.3 mutation 14종 — **미완** (기존 `MutationTest`·`HeldoutV3EvaluatorSelfTest`는 통과)

---

## 8. 7개 gate (§8) — **NOT DONE**

채점할 v4 dataset이 없으므로 gate를 구현해도 분모가 없다. 만들지 않았다.

## 9. fresh JVM (§9)

이전 XML 전량 삭제 후 `./gradlew --offline test --rerun-tasks --continue` 단일 실행:

**66 suites / 387 tests / 0 failures / 0 errors / 0 skipped**
`./gradlew --offline :app:assembleDebug` BUILD SUCCESSFUL

`jvm_test_suites_cycle7.json`에 명령·시작/종료 시각·HEAD·suite별 내역·production 집계 SHA를 기록했다.
cycle 6의 61/351은 `pre_device_v4/jvm_test_suites.json`에 그대로 보존된다.

## 10·11. 신규 frozen v4 / actual Gemma 공식 실행 — **NOT DONE**

v4 dataset·runner·gate를 만들지 않았으므로 freeze도 공식 실행도 없다.
actual Gemma는 **runtime 가용성만** 확인된 상태다(`pre_device_v4/actual_gemma/runtime_availability.json`):
엔진 로드, native tool call 1회, 모델 SHA `1819…a63c` 일치. 이것은 gate 통과가 아니다.

## 12. 물리 ARM64 — **NOT DONE** (기기 없음)

---

## 부록 A — 변경한 production 파일

| 파일 | 변경 |
|---|---|
| `agent-contract/.../AgentModel.kt` | `FinalCandidate.clarification` 추가 |
| `agent-core/.../AgentKernel.kt` | missing-slot → `NEEDS_CLARIFICATION`, bounded repair continuation, `maxWorkflowRepairs` |
| `agent-core/.../AgentWorkflowPolicy.kt` | `pendingTerminalTool()`, `continuationPrompt()`, 도구별 false-completion, 수신자 조사 확장 |
| `agent-core/.../TurnIntentLexicon.kt` | mention 표지 강/약 분리, 띄어쓴 경칭, 2–4음절 제한 |
| `app/.../LocalToolRoutingModelGateway.kt` | 모든 missing-slot 답변에 `MISSING_REQUIRED_SLOT` 표시 |

## 부록 B — 추가한 테스트 (cycle 7)

| 파일 | 건수 |
|---|---|
| `app/src/test/.../v4/OutcomeContractV4Test.kt` | 8 |
| `app/src/test/.../v4/LegacyContractMigrationTest.kt` | 3 |
| `app/src/test/.../v4/MultiToolContinuationTest.kt` | 12 |
| `app/src/test/.../v4/FalseCompletionTest.kt` | 6 |
| `agent-core/src/test/.../MentionSpanSafetyTest.kt` | 7 |

지원 코드: `eval/contract/OutcomeContract.kt`, `eval/contract/LegacyOutcomeMigrationLog.kt`,
`v4/ScriptedModel.kt`

---

**최종 판정: PARTIALLY COMPLETE.**
§1–§3, §5, §6, §7.2, §9는 수행했다. §4는 부분, §7.1·§7.3·§8·§10·§11·물리 기기는 수행하지 않았다.
수행하지 않은 단계를 통과로 기록하지 않았고, 기존 frozen 결과를 새 결과로 대체하지 않았다.
