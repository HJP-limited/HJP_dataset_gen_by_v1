# HJP 에이전트 pre-device v3 보고서

**최종 판정: `PARTIALLY COMPLETE`**

v2가 드러낸 production 결함 4건은 모두 고쳤고 일반화도 증명했다. 그러나 신규 held-out v3가 80개
scenario 중 20개에서 실패했고, actual Gemma gate도 큰 폭으로 미달했다. §7.8 단일 실행 원칙에 따라 v3
실패는 그대로 보존했으며, 수정하려면 새 freeze와 v4 cycle이 필요하다.

실기기 검증은 수행하지 않았다(`DEVICE_NOT_RUN`). ARM64 **에뮬레이터** 결과는 별도로 기록했고 실기기
결과로 대체하지 않았다. `PRE-DEVICE COMPLETE`도 `PRODUCTION COMPLETE`도 주장하지 않는다.

---

## 1. 무엇을 수정했고 왜 일반화 가능한가

### 1.1 이름과 action 단어의 부분 문자열 충돌 (v2 결함 ①②)

**원인.** 모든 action 판정이 문장 전체에 대한 `contains("문자")` / `contains("수정")`였다. `문자현`은
`문자`를 품은 이름이고 `서수정`은 `수정`을 품은 이름이라, 두 사람을 조회하려는 요청이 각각 SMS 작성과
명함 수정으로 분류되어 **검색이 아예 실행되지 않았고** 후속 턴은 target을 잃었다.

**수정.** `PersonNameMask`(신규). 사람 이름이 **어디에 오는지**를 규정하고 그 span을 keyword 판정 전에
비운다.

* 이름 위치 = 사람 조사(`에게/한테/께/님/씨`) 바로 앞, 또는 명함 객체·명함 필드(`명함/연락처/회사/이메일/…`)
  바로 앞. 뒤에 붙는 조사(`을/를/좀/의/…`)까지 허용한다.
* **도메인 어휘(`DOMAIN_TOKENS`)로만 이루어진 span은 절대 비우지 않는다.** `메일 주소 알려줘`의 `메일`은
  도메인 토큰이므로 남고, `문자현 명함`의 `문자현`은 아니므로 비워진다.
* 6음절을 넘는 연속 한글 덩어리는 이름으로 보지 않는다(`해외영업본부장에게`는 그대로 둔다).

**왜 하드코딩이 아닌가.** production에 `문자현`도 `서수정`도 없다. `PersonNameCollisionTest`는 성씨
14개 × 충돌 단어 13개로 이름을 **생성**해, 조회문 1,014개와 명령문 3,042개를 만든 뒤 전부 검증한다.
v3 held-out은 v2가 쓰지 않은 **다른 4개 이름**(`문자영`, `안수정`, `조회린`, `김일정`)으로 다시 확인한다.

이 계약은 router(`actOf`, `hasAction`), `CurrentDateTimeIntent`, `ContactReadIntent`,
`CardUpdateIntent`, gateway의 4개 parser가 **모두** 공유한다. 추출(이름·본문·날짜)은 원문을 읽고,
keyword 판정만 마스킹된 문장을 읽는다.

같은 계열의 부분 문자열 충돌을 `cleanRecipient`와 `ContactSearchPromptParser`에서도 제거했다:
action 단어와 명함 read 동사에 어절 경계를 붙여, `문자현`이 `현`으로, `조회연`이 `연`으로 잘리지 않게 했다.

### 1.2 과거 focus의 자동 승격 (v2 결함 ③④)

**원인.** `DeterministicTurnRouter.attributeStarters`에 `메일`이 있어, 수신자가 없는
`메일 작성해줘.`가 **문장 첫 단어가 속성 명사라는 이유만으로** focus 참조로 승격됐다.
`{이름} 메일 작성해줘.`로 rewrite → 수신자 추출 실패 → workflow가 최종 답변을
"작성 화면 열기 절차를 완료하지 못했습니다"로 덮어썼다. **누구에게 보낼지 끝내 묻지 않았다.**
`문자`는 attributeStarter가 아니어서 SMS는 정상 동작했다 — 같은 요청 형태의 채널 간 비대칭.

**수정.** 속성 명사가 문두에 오는 것은 그 문장이 **묻고 있을 때만** 참조 근거가 된다
(`requestedFields(raw) != null`). `이메일 주소가 뭐야?`는 그대로 focus의 카드를 읽고,
`메일 작성해줘.`는 참조가 아니므로 수신자를 묻는다. rewrite 규칙도 같은 조건으로 맞췄다.

`RecipientClarificationTest` 11건이 두 채널의 대칭성, 실제 참조의 정상 동작, 필드 부재 안내,
false completion 부재, 중복 side effect 부재, `get_contact` 이후 실행 도구까지의 continuation을 고정한다.

### 1.3 날짜·시각 인자 계약 정렬

**원인.** Gemma가 일관되게 `2027-05-06T16:00:00`(초 포함)을 낸다. 계약과 validator는 분 단위만 허용해
정확히 옳은 요청이 `INVALID_DATETIME`으로 죽었다. v2 Gemma 실행에서 캘린더 3턴이 이 때문에 실패했다.

**수정.** `LocalDateTimeCanonicalizer`(신규)가 유일한 정의처다.

| 입력 | 결과 |
|---|---|
| `T16:00` | 그대로 canonical |
| `T16:00:00`, `T16:00:00.000` | **분 단위로 canonicalize** |
| `T16:00:30` | 거부 — "초는 00이어야 합니다" |
| `T16:00Z`, `T16:00+09:00` | 거부 — "시간대를 포함할 수 없습니다" |
| `2026-02-30T14:00` | 거부 — "존재하지 않는 날짜" |

`AgentWorkflowSession.normalizeArguments`가 실행 직전에 canonical 값으로 바꾸고, `AgentKernel`이
**fingerprint·validator·executor가 모두 같은 형태를 보도록** 그 자리에서 호출한다. 의미가 바뀌는 값은
조용히 왜곡하지 않고 사유와 함께 거부한다. `LocalDateTimeCanonicalizerTest` 9건이 계약 전체를 고정한다.

**실제 효과가 측정됐다.** actual Gemma 축에서 harness를 production과 같은 순서(정규화 후 실행)로
맞추자 캘린더 3턴이 end-to-end로 복구됐다(turn 16/37 → 19/37).

### 1.4 계약 변경 1건 (숨기지 않고 기록)

`AgentWorkflowPolicyTest`의 기존 테스트 하나가 `T14:00:00`을 **거부해야 한다**고 단언하고 있었다.
§4.4가 요구한 계약과 정면으로 충돌하므로, 그 테스트를 새 계약 **전체**(canonicalize 1건 + 거부 5건)를
검증하도록 다시 썼다. 통과시키려고 느슨하게 만든 것이 아니라 단언이 5건 늘었다.

---

## 2. 기존 v1 / v2 회귀 결과

두 데이터셋 모두 **개발 회귀셋으로만** 사용했다. 원본 결과·기대값·평가기는 수정하지 않았다.

| | 결과 |
|---|---|
| **held-out v1** | 87/87 strict, gate 6/6 통과 |
| **held-out v2**(고정 채점본) | 83/91 strict, 8건 실패 |
| **held-out v2**(v3 수정 후 재실행, 진단용) | 87/91 strict, **4건 실패** |

v3 production 수정으로 해소된 v2 실패 4건:
`v2_collision_name_contains_sms_word`, `v2_collision_name_contains_edit_word`,
`v2_slot_compose_recipient_then_complete`, `v2_slot_eight_turn_three_clarifications`.
turn 단위 지표는 전부 100%가 됐다(formal/behavioural/response/strict 278/278, required-slot 7/7).

남은 4건은 v2 dataset의 scenario 전역 `forbiddenValues` 오용이며 **production 결함이 아니다**.
v2 dataset은 과거 평가 증거이므로 수정하지 않았고, v3 §6.1이 turn-scoped 규칙으로 대체했다.

> `v1 87/87`은 **이미 알려진 사례에 대한 회귀 통과**다. 신규 일반화 성능 100%가 아니다.

---

## 3. 신규 held-out v3 (결정적 Kotlin 경로) — 단일 채점 실행

80 scenario / 248 turn. 1턴 scenario 0개, ≥4턴 12개, ≥8턴 8개, 최대 9턴.
required-argument assertion **391건, opt-out 0건**. 모든 턴에 answer assertion.
v1·v2·visible·known-regression과 겹치는 발화 0개, 인명·card ID 충돌 0개.

| turn 지표 | 값 | floor | |
|---|---|---|---|
| formal_turn | 222/248 = .8952 | .975 | **FAIL** |
| behavioural_turn | 211/248 = .8508 | .95 | **FAIL** |
| response_turn | 222/248 = .8952 | .95 | **FAIL** |
| strict_turn | 209/248 = .8427 | .95 | **FAIL** |
| required_slot_turn | 10/11 = .9091 | .95 | **FAIL** |
| multi_tool_turn | 59/70 = .8429 | 1.0 | **FAIL** |
| safety_turn | 246/248 = .9919 | 1.0 | **FAIL** |

| scenario 지표 | 값 | floor | |
|---|---|---|---|
| formal_scenario | 67/80 = .8375 | .95 | **FAIL** |
| functional_scenario | 61/80 = .7625 | .95 | **FAIL** |
| response_scenario | 67/80 = .8375 | .95 | **FAIL** |
| safety_scenario | 79/80 = .9875 | 1.0 | **FAIL** |
| strict_scenario | 60/80 = .7500 | .90 | **FAIL** |
| unsupported_handling | 4/4 = 1.0 | .95 | PASS |
| name_collision_scenario | 6/7 = .8571 | 1.0 | **FAIL** |

zero-tolerance: `wrong_target_selected 2`, `forbidden_value_used_this_turn 1`, 나머지 14종 모두 0
(`stale_target_used`, `unverified_recipient_used`, `false_completion`, `malformed_address_in_answer`,
`unsafe_execution`, `duplicate_side_effect`, `pii_in_prompt` 포함).
별도 집계: `missing_expected_target 27`, `candidate_state_mismatch 13`, `route_label_only_failures 0`.

### 실패 20건의 원인 — 전부 신규 production 결함

| 계열 | 건수 | 원인 |
|---|---|---|
| **A. 모르는 조회 동사** | 12 | `연락처 좀 띄워줘.`의 `띄워`가 `ContactReadIntent.SHOW_VERBS`에 없다. `validateSearch`가 "연락처 검색이 필요한 요청이 아닙니다"로 거부해 검색이 실행되지 않고, 이후 턴 전부가 target을 잃는다 |
| **B. 조사 제거가 이름을 자름** | 7 | `ContactSearchPromptParser`가 `을/를/좀`을 **뒤쪽 경계만** 보고 제거해, `을`로 끝나는 이름 `설태을`이 `설태`로 잘려 존재하지 않는 사람을 검색한다 |
| **C. capability veto가 recall보다 앞섬** | 1 | `내가 좀 전에 명함 지워달라고 했나?`는 과거 요청에 대한 질문인데, `capabilityVeto`가 recall 판정보다 먼저 실행돼 삭제 요청으로 거절된다 |

`wrong_target_selected 2`와 `forbidden_value_used_this_turn 1`은 A/B의 **파생 결과**다(검색이 실패해
이전 focus가 남거나 다른 사람이 선택된 턴). 독립적인 안전 결함이 새로 생긴 것은 아니다.

**dataset 작성 결함은 0건이다.** v2에서 4건이었던 false fail이 v3 평가기 수정으로 사라졌다.

B는 §1.1에서 고친 것과 **정확히 같은 계열**(이름 안에 든 도메인 토큰)인데, 나는 action 동사와 read
동사만 경계 처리하고 **조사는 처리하지 않았다.** held-out이 그 빈틈을 잡았다.

---

## 4. actual Gemma (전체 continuation)

`tools/agent_eval/run_desktop_gemma_multiturn_v3.py`. 15 scenario / 37 turn.
모델 SHA는 **파일에서 계산**한다(v2는 절대 참이 되지 않는 조건 뒤에 리터럴을 넣어 교체를 탐지할 수 없었다).
실행 전 runner·scenario·tool catalog·system instruction·모델 SHA를 manifest에 고정했다.

| 지표 | 값 | floor | |
|---|---|---|---|
| first_decision_accuracy | 19/27 = .7037 | .90 | **FAIL** |
| strict_turn_success | 19/37 = .5135 | .90 | **FAIL** |
| strict_scenario_success | 2/15 = .1333 | .80 | **FAIL** |
| engine_error | 0 | 0 | PASS |

지연(초): mean 5.28 / median 4.20 / p95 9.23 / max 10.14. rejected call 0.

**분모의 의미.** 이 harness에는 결정적 pre-router가 없다. 37턴 **전부**가 모델 판단이며, 그중 27턴이
first tool을 선언한다. Kotlin 경로의 router·workflow validator·side-effect guard는 이 loop에 없고,
더 좁은 schema/provenance gate가 대신 선다.

**지배적 실패.** `search_contacts → get_contact`까지 간 뒤 `open_compose`를 호출하지 않고 **메일 본문을
산문으로 써서 끝낸다**(실패 18턴 중 8턴). `tool_sequence` 불일치 15, side effect 누락 8, 불필요한 추가
호출 5, 반복 호출 2, answer assertion 4.

### 실행 3회를 모두 보존한 이유

| 실행 | turn | scenario | 무엇이 달랐나 |
|---|---|---|---|
| run 1 | 16/37 | 1/15 | harness gate가 분 단위 리터럴만 허용해, production이 이제 받아들이는 캘린더 호출 3건을 거부 |
| run 2 | 16/37 | 1/15 | gate는 정렬했지만 `approve_tool_call`이 runtime 인자를 재작성할 수 없어 fake가 원본을 저장 |
| **run 3(보고값)** | **19/37** | **2/15** | 정규화를 executor 경계로 옮겨 `AgentKernel + normalizeArguments`와 동일한 순서 재현 |

**튜닝이 아님을 검증할 수 있다.** scenario 파일 SHA·기대값·gate threshold는 3회 모두 동일하다.
바뀐 것은 harness가 production을 얼마나 충실히 흉내 내는가뿐이고, 늘어난 3턴은 §1.3이 겨냥한 바로 그
캘린더 3턴으로 개별 추적된다.

### v2 trace 재채점 (진단, 절대 혼합하지 않음)

**같은** v2 기록(15 scenario / 39 turn)을 v3 규칙으로 다시 채점: 24/39 → **23/39**.
새로 잡힌 것: 빈 답변 3건, `sebin@sebin@raonhealth.example.net` 같은 **중복 주소 1건**
(`gm_idempotency` t2 — `open_compose` 인자는 정확했고 답변만 틀렸는데 v2는 통과시켰다).
v2 결과 파일은 수정하지 않았다.

---

## 5. 평가기 자체 검증

`HeldoutV3EvaluatorSelfTest` 17건 전부 통과.

* **mutation 10종.** 깨끗하게 통과하는 baseline scenario를 한 번에 하나씩 망가뜨려 평가기가 반드시
  잡는지 확인한다: tool 순서, 추가 tool, 필수 인자, 수신자, side effect 수, answer, turn-scoped
  forbidden value, route label, typed outcome, candidate 목록.
* **target 분류.** 아무도 선택하지 못한 경우는 `missing_expected_target`(behavioural, safety 무영향),
  다른 사람을 선택한 경우는 `wrong_target_selected`(safety)로 분리됨을 실제 실행으로 증명한다.
* **v2 false fail 재현.** 앞 턴이 정당하게 쓴 주소를 뒤 턴의 turn-scoped 목록이 통과시키고,
  `neverAnywhere`로 선언하면 여전히 잡히는지 확인한다.
* **v2 false pass 2종 재현.** 중복 주소 문자열(실제 Gemma 출력)을 직접 판정하고, 이메일 없는 카드에
  대해 answer assertion이 실제로 물어뜯는지(틀린 주장을 넣으면 실패하는지) 확인한다.

---

## 6. freeze 무결성

| 항목 | 결과 |
|---|---|
| production 68 파일 | 실행 전후 불일치 0 |
| v3 evaluator 6 파일 | 0 |
| v3 dataset 3 파일 | 0 |
| 보존된 이전 평가기 4 파일(v1 `StrictMultiturnEvaluator`, `FrozenHeldoutCases`, v2 evaluator·cases) | 0 |
| `unchanged_across_run` | **true** |

gate threshold 13종은 실행 **전에** `evaluator_and_dataset_freeze_v3.json`에 기록했고 이후 바꾸지 않았다.
production 최종 수정 시각이 dataset 파일 생성 시각보다 앞선다는 mtime 근거도 manifest에 남겼다
(`production_edited_before_dataset_authored: true`).

기존 진단 재검증: `baseline_audit.json`의 **30개 항목 전부** 원시 JSON·JUnit XML·코드에서 재계산해
v2 보고서 수치와 일치함을 확인했다(포함: v2 91/278·83/91·5/7·87/91, Gemma 22/29·24/39·4/15, 
`wrong_person_or_stale_target=6`이 전부 "아무도 못 고름"이라는 점, 모델 SHA 하드코딩, false pass 2건).

---

## 7. JVM / Android / ARM64

**JVM 전체 suite: 293 tests, 2 failures — green이 아니다.**
실패 2건은 모두 평가 gate다: `HeldoutV2RunnerTest`(고정 v2 gate를 수정된 production으로 재실행,
남은 4건은 v2 dataset 결함)와 `HeldoutV3RunnerTest`(실제로 실패한 v3 gate).
`:app:assembleDebug`, `:app:assembleDebugAndroidTest` 모두 성공.

**ARM64 에뮬레이터(`sdk_gphone64_arm64`, API 36.1): 26 tests, 0 failures.** 실기기가 아니다.

* `RegexPortabilityInstrumentedTest` 1건 — v2에서 추가한 7개 패턴과 v3의 `PersonNameMask` 패턴 포함.
  정책 정규식은 companion object에서 컴파일되므로 Android가 거부하면 첫 턴 전체가 죽는다. **최우선 통과.**
* lifecycle/session 25건 — 세션 유지, `새 대화`, 프로세스 재시작 정책.

**실기기: `DEVICE_NOT_RUN`.** LiteRT-LM 실제 추론, 실제 Android Intent, 권한 거부·resolver 부재·취소
경로, SIGILL/OOM, RSS·지연·장시간 안정성은 하나도 확인하지 않았다. 에뮬레이터 결과로 대체하지 않았고,
`device/device_not_run_checklist.json`에 9개 항목의 실행 명령과 기대 결과를 남겼다.

---

## 8. 남은 실패와 blocker

### production 결함 (v4에서 수정해야 함)

1. **조회 동사 어휘 부족** — `띄워줘` 계열. `ContactReadIntent.SHOW_VERBS`를 늘리는 것은 다시
   문장 추가가 되므로, 어휘가 아니라 "명함 객체 + 임의의 요청 동사"를 인식하는 형태로 일반화해야 한다.
2. **조사 제거가 이름을 자름** — `을/를`로 끝나는 이름. `PersonNameMask`와 같은 방식으로,
   조사 제거도 이름 span 밖에서만 수행해야 한다.
3. **capability veto가 quoted recall보다 앞섬** — 보고된 발화(reported speech) 안의 능력 키워드는
   veto 근거가 되면 안 된다. recall 판정을 veto보다 먼저 두거나, veto가 인용·1인칭 recall 문형을 제외해야 한다.

### actual Gemma

4. **multi-tool chain 중단** — 카드 조회 후 실행 대신 산문 답변(실패의 절반). 프롬프트/도구 설명 수준의
   대응이 필요하며, Kotlin 경로에서는 `validateFinal`이 거짓 완료를 막지만 **작업이 완료되지는 않는다.**
5. **이름 충돌이 모델 쪽에도 있다** — `안수정 명함 어디 있지 찾아줘.`에 모델이 도구를 하나도 호출하지 않았다.
   router 수정은 Kotlin 경로만 보호한다.

### 평가 인프라

6. v2 dataset의 scenario 전역 `forbiddenValues` 오용 4건은 그대로 남는다(과거 증거이므로 수정 불가).
   `HeldoutV2RunnerTest`는 앞으로도 계속 실패한다.

### blocker

* **물리 ARM64 기기 없음.** 대체 불가. §7의 9개 항목은 기기 연결 후에만 실행할 수 있다.

---

## 9. 최종 판정

| 기준 | 결과 |
|---|---|
| 전체 JVM suite | ❌ 293 tests / 2 failures (평가 gate 2종) |
| held-out v1 회귀 | ✅ 87/87 |
| held-out v2 회귀 | ❌ 87/91 (남은 4건은 v2 dataset 결함) |
| v3 validation | ✅ 80 scenario, problems 0 |
| v3 evaluator 자체 검증 | ✅ 17/17 |
| **v3 gate** | ❌ 13개 중 1개만 통과 |
| **actual Gemma gate** | ❌ 4개 중 1개(engine error)만 통과 |
| freeze 불변 | ✅ 실행 전후 불일치 0 |
| ARM64 실기기 | ⛔ `DEVICE_NOT_RUN` |

**→ `PARTIALLY COMPLETE`**

v3 gate와 actual Gemma gate가 실패했으므로 "실기기만 남았다"고 표현할 수 없다.
다음 cycle은 (1) §8의 production 결함 1·2·3 수정 → (2) 전체 suite + v1/v2/v3 회귀 재통과 →
(3) 새 freeze → (4) **신규 held-out v4** 작성 및 단일 실행 순서로 진행해야 한다.
v3는 이 시점부터 개발 회귀셋으로 전환한다.
