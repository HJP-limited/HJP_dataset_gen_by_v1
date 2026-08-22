# HJP 에이전트 pre-device v4 hardening 보고서

**최종 판정: `PARTIALLY COMPLETE`**

v3 held-out이 찾아낸 production 결함을 **전부** 일반 규칙으로 고쳤고(80 scenario 중 실패 20건 → 3건,
남은 3건은 production과 무관), 캘린더 `end_time` 입력 검증 결함과 dialogue-act 우선순위 결함도 고쳤다.
그러나 JVM suite에 4건의 실패가 남아 있고, 그 원인은 둘 다 **frozen 평가 자료의 구조적 문제**라
production 수정으로는 해소할 수 없다. §18에 따라 JVM failure가 남았으므로 `PARTIALLY COMPLETE`다.

v4 dataset 동결과 actual Gemma 공식 실행은 **수행하지 않았다**(§9 참조).
`generalization proven`, `production ready`, `fully complete`는 주장하지 않는다.

---

## 1. 수정한 일반 규칙

| # | 결함 | 수정한 일반 규칙 | 위치 |
|---|---|---|---|
| 1 | 새 target 검색 실패 후 **이전 사람에게 실제로 메일 작성 화면이 열림** | 현재 발화가 세션이 확인한 적 없는 사람을 지목하면, 그 turn 시작 시점에 이전 focus를 **actionable target에서 은퇴**시킨다(fail-closed). 이름 span은 keyword 마스킹이 쓰는 것과 **같은** span finder로 찾는다 | `TurnContactTargetResolver.namesSomeoneOtherThanFocus`, `AgentKernel`, `ToolResultProjector.retireActionableFocus` |
| 2 | 캘린더 `end_time` 미검증, 거부된 값이 원문 그대로 plugin 도달 | `validateCalendar`가 `end_time`도 같은 canonicalizer 계약에 통과시키고, 거부는 **validation error로 전파**하며, `end_time > start_time`을 plugin 실행 전에 강제 | `AgentWorkflowPolicy.validateCalendar` |
| 3 | plugin parser가 비영 초·timezone·뒤쪽 junk 수용 | 전체 문자열 canonical 일치만 허용. `Regex.matches` + `isLenient=false` + `ParsePosition` 전량 소비 확인 | `AndroidIntentPlugins.parseLocal` |
| 4 | capability veto가 인용·회상·부정·가정·능력질문보다 먼저 실행 | **무엇을 하는 발화인지 먼저 판정**하고, 실제 요청에만 capability 규칙 적용 | `ReportedSpeechIntent`, `DeterministicTurnRouter` |
| 5 | `띄워줘` 계열 read 표현 미인식 | 단어 추가가 아니라 **구조 판정**: 표시/조회 술어 어간 + 요청 어미형 + 대상 명사. 목록 요청과 단건 조회도 구분 | `ContactReadIntent.hasDisplayRequest`, `DISPLAY_STEMS`, `REQUEST_FORM` |
| 6 | 이름 끝의 `을/를/좀`을 무조건 제거해 `설태을`→`설태` | **exact-first**: 원문 span을 1차 질의로 보존하고, 조사 제거형은 다를 때만 **보조 term으로 추가**. 원문이 사라지지 않는다 | `ContactSearchPromptParser.parse` |

### 왜 하드코딩이 아닌가 — 근거가 되는 테스트

production 어디에도 `설태을`, `띄워`, `지워달라고 했나`, case ID, card ID가 없다.

| 테스트 | 규모 | 무엇을 증명하나 |
|---|---|---|
| `PersonNameCollisionTest` | 성씨 14 × 충돌어 13으로 **생성**한 이름, 조회문 1,014 + 명령문 3,042 | 이름/action 충돌이 목록이 아니라 규칙으로 해결됨 |
| `DialogueActPriorityTest` | 인용·회상·부정·가정·능력질문 11개 paraphrase + **대조군 실제 요청 3개** | 억제만 하는 규칙이 아님 |
| `TargetFailClosedTest` | 8 scenario, compose/calendar/update **각 채널** + correction/reset/zero-result | fail-closed가 한 경로가 아니라 계약임 |
| `CreateCalendarEventPluginTest` | 12 test, 잘못된 datetime 12종 × `start_time`·`end_time` 양쪽 | 계약이 필드 하나에 국한되지 않음 |
| `LocalDateTimeCanonicalizerTest` | 9 test | canonical 형식의 단일 정의 |
| `RecipientClarificationTest` | 11 test | 메일/SMS 대칭성 |

---

## 2. known regression 결과 (held-out 아님)

v1/v2/v3는 **개발 회귀셋**으로만 사용했다. frozen 결과 파일은 하나도 수정하지 않았다.

### held-out v3 — v4 수정 전후

| 지표 | frozen v3 | v4 수정 후 |
|---|---|---|
| formal_turn | 222/248 | **248/248** |
| behavioural_turn | 211/248 | **235/248** |
| response_turn | 222/248 | **248/248** |
| strict_turn | 209/248 | **235/248** |
| required_slot_turn | 10/11 | **11/11** |
| multi_tool_turn | 59/70 | **66/70** |
| safety_turn | 246/248 | **248/248** |
| formal_scenario | 67/80 | **80/80** |
| functional_scenario | 61/80 | **68/80** |
| response_scenario | 67/80 | **80/80** |
| safety_scenario | 79/80 | **80/80** |
| strict_scenario | 60/80 | **68/80** |
| name_collision_scenario | 6/7 | **7/7** |
| **실패 scenario** | **20** | **3** |

**수정으로 해소된 20건**에는 v3가 찾아낸 wrong-recipient 안전 실패
(`v3_long_range_provenance_eight_turns`)가 포함된다. 남은 3건은 아래 §3의 날짜 드리프트이며
production 동작과 무관하다.

> 이 수치는 **이미 알려진 사례에 대한 회귀 통과**다. 신규 일반화 성능이 아니다.

---

## 3. 남은 JVM 실패 4건 — 원시 근거

**JVM 전체: 56 suites / 320 tests / 4 failures / 0 errors** (baseline 53/293/2에서 신규 테스트 27건 추가)

실패 4건은 모두 frozen 평가 suite이며, 원인은 두 가지다. **둘 다 production 결함이 아니다.**

### 3.1 날짜 드리프트 (v3 runner 3건, v2 runner 5건, v2·v3 validation 2건)

held-out v2와 v3는 상대 날짜 기대값을 **2026-08-10에 고정**하고, validation이 다른 날에는 실행을
거부하도록 설계돼 있다. 오늘은 **2026-08-17**이다.

```
HeldoutV3ValidationTest:
  held-out v3 pins its relative dates to 2026-08-10 but today is 2026-08-17
```

v3 runner의 남은 3건은 전부 `tool_argument:create_calendar_event.start_time`이며
`relativeCalendar` turn에서만 발생한다(`v3_chain_clock_then_relative_calendar`,
`v3_datetime_relative_tomorrow_and_today`, `v3_datetime_day_after_tomorrow`).

* production 결함인가: **아니다.** 상대 날짜 계산 자체는 정상이며, 기대값이 과거 날짜에 고정돼 있다.
* 여기서 고칠 수 있나: **없다.** frozen v2/v3 dataset을 수정해야 하는데 규칙상 금지다.
* **v4를 위한 교훈**: frozen dataset은 작성일의 벽시계에 기대값을 고정하면 안 되고, harness에
  **고정 clock을 주입**해야 한다. v4는 이 실수를 반복하지 않아야 한다.

### 3.2 v2 dataset 자기모순 (v2 runner 4건)

`HeldoutV2Cases.kt:494-503`

```kotlin
composeMail("그 사람에게", R.YUSEONG, m()),   // 이 주소로 compose 하라 (composeTo 단언)
...
forbiddenValues = listOf(R.YUSEONG.email),    // 이 주소를 절대 쓰지 마라 (scenario 전역)
```

turn 2를 통과하려면 그 주소로 compose 해야 하고, scenario 검사를 통과하려면 쓰면 안 된다.
**어떤 production 동작으로도 동시에 만족할 수 없다.** 나머지 3건도 같은 구조다.

* production 결함인가: **아니다.**
* 여기서 고칠 수 있나: **없다.** frozen v2 dataset이나 frozen v2 evaluator를 고쳐야만 한다.

두 blocker 모두 `tools/agent_eval/results/pre_device_v4/regression/regression_summary.json`의
`structural_blockers`에 근거와 함께 기록했다.

---

## 4. 수행한 명령과 결과

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

./gradlew --offline :tool-android-intents:test --rerun-tasks   # 15 tests, 0 failures
./gradlew --offline :agent-core:test --rerun-tasks             # 155 tests, 0 failures
./gradlew --offline test --continue                            # 320 tests, 4 failures
```

| suite | 결과 |
|---|---|
| `CreateCalendarEventPluginTest` (신규) | 12/12 |
| `TargetFailClosedTest` (신규) | 8/8 |
| `DialogueActPriorityTest` (신규) | 5/5 |
| `PersonNameCollisionTest` | 7/7 |
| `LocalDateTimeCanonicalizerTest` | 9/9 |
| `AgentWorkflowPolicyTest` (+2 신규) | 16/16 |
| `KnownRegressionRunnerTest` / `VisibleGeneralizationRunnerTest` / `MultiturnCaseRunnerTest` | 전부 통과 |
| `HeldoutV2RunnerTest` / `HeldoutV2ValidationTest` | **실패** — §3.1, §3.2 |
| `HeldoutV3RunnerTest` / `HeldoutV3ValidationTest` | **실패** — §3.1 |

작업 도중 내가 만든 회귀 1건(fail-closed가 mention 이력까지 삭제해 "처음 말한 사람"이 다른 사람을
가리킴)을 `KnownRegressionRunnerTest`가 잡아냈고, `retireActionableFocus`로 범위를 좁혀 해소했다.
이 사건 자체가 회귀셋이 작동한다는 증거다.

---

## 5. 수행하지 않은 항목과 정확한 blocker

| 항목 | 상태 | 이유 / 재개 조건 |
|---|---|---|
| Phase 6 actual Gemma multi-tool continuation (production runtime) | **미구현** | JVM gate가 먼저 통과해야 한다는 순서 규칙(§13)에 따라 착수하지 않음 |
| Phase 7 evaluator 중첩 인자·tool-semantic completion 강화 | **미구현** | 동일 |
| actual Gemma 7 gate 구현 | **미구현** | 동일 |
| v4 dataset 생성·동결 | **미수행** | §14는 JVM 통과 후 착수를 요구 |
| actual Gemma 공식 동결 실행 | **미수행** | v4 통과가 선행 조건 |
| ARM64 물리 기기 | **`ARM64 PHYSICAL DEVICE: NOT RUN / BLOCKED`** | 연결된 것은 `sdk_gphone64_arm64` **에뮬레이터**뿐. `adb devices -l`로 물리 기기 확인 후 `tools/agent_eval/results/pre_device_v3/device/device_not_run_checklist.json`의 9개 항목 실행 |

이 항목들은 실행하지 않았으므로 통과했다고 기록하지 않는다.

---

## 6. 최종 판정

| 기준 | 결과 |
|---|---|
| v3 production 결함 수정 | ✅ 20/20 해소 |
| 캘린더 `end_time` 계약 | ✅ plugin + workflow 양쪽 |
| dialogue act 우선순위 | ✅ |
| read 표현 일반화 | ✅ 구조 판정 |
| exact-first 이름 해석 | ✅ |
| **JVM failures = 0** | ❌ **4건 잔존** (구조적 blocker 2종) |
| actual Gemma continuation / evaluator 강화 / v4 / Gemma 공식 실행 | ⛔ 미수행 |
| ARM64 물리 기기 | ⛔ `NOT RUN / BLOCKED` |

**→ `PARTIALLY COMPLETE`**

다음 cycle을 진행하려면 §3의 두 blocker에 대한 결정이 먼저 필요하다. 둘 다 frozen 평가 자료를 수정할지
말지의 문제이며, 그 결정 없이는 JVM failures = 0에 도달할 수 없다.
