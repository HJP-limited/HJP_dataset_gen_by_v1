# 실기기 전 완료 보고서 (pre-device completion)

판정: **READY FOR FROZEN HELD-OUT**

작성 2026-08-09(Asia/Seoul). 브랜치 `android-app`, HEAD `993a5f5`. 커밋·push는 하지 않았다.
원시 결과: `tools/agent_eval/results/pre_device_completion/`.

이 문서의 모든 수치에는 **gateway / model / assembly / tool backend / runtime** 축이 붙는다.
축이 다른 수치를 합치지 않는다.

---

## 1. 무엇이 잘못돼 있었나

기존 멀티턴 러너는 48/48을 보고했다. 그 48개 중 **18개는 유일한 단언이 "compose가 실행되지
않았다"** 뿐이었고, route도 typed outcome도 단언하는 필드 자체가 없었다. 그래서 다음이 모두
"통과"로 기록됐다.

| seed | 기록된 결과 | 실제 동작 |
|---|---|---|
| `ref_attribute_question` | passed | "회사가 어디야?"에 기능 안내 문구를 반환 |
| `ref_first_mentioned_person` | passed | `get_contact` 성공 후 "연락처 검색을 완료하지 못했습니다" |
| `ordinal_second_person` | passed | 위와 동일한 거짓 실패 문구 |
| `correction_named_replacement` | passed | 거부된 김지원이 후보에 그대로 남음 |
| `update_requires_field_and_value` | passed | 구체적 질문 대신 "다시 시도해 주세요" |
| `update_with_field_and_value` | passed | 도구 0회 + 기능 안내 문구 |
| `failure_reason_followup` | passed | 도구가 실패했는데 턴은 COMPLETED, 이후 "실패한 요청은 없습니다" |
| `quoted_compose_request` | passed | 하지도 않은 요청을 "하셨습니다"라고 확인 |
| `adversarial_email_format` | passed | 정보 질문에 "어떤 분을 말씀하시는지" clarification |
| `adversarial_meeting_notes` | passed | 정보 질문에 일정 시각 clarification |

10개 모두 문자열 문제가 아니라 **구조적 결함**이었다.

---

## 2. root cause별 production 수정

각 수정은 공용 경로에 있고, 케이스 ID·고정 이름·고정 card_id를 참조하지 않는다.

| # | root cause | 파일 | 일반 규칙 | 회귀 테스트 |
|---|---|---|---|---|
| 1 | 개념 질문이 target resolution 규칙에 먼저 잡혔다 | `DeterministicTurnRouter` | 주제어(형식·방법·뜻…) + 설명 요청이면서 구체적 대상(주소·번호·아는 이름·대명사·서수)이 없으면 `GeneralInformation` | `boundary_information_*` 15건, `adversarial_*` |
| 2 | 선택된 연락처의 속성 질문이 모델에 위임돼 근거 없는 답이 나왔다 | `TurnRoutePlan.ContactDetail`, `AgentKernel.answerContactDetail`, `ContactDetailAnswer` | 검증된 대상의 필드 질문은 **fresh `get_contact`** 로 결정적으로 답한다(읽기 전용, side effect 없음) | `ref_attribute_*` 18건 |
| 3 | `validateFinal`이 `search_contacts` 없이는 조회 완료를 인정하지 않았다 | `AgentWorkflowPolicy` | 이미 검증된 card를 `get_contact`로 읽은 것도 조회 완료다 | `ref_first_mentioned_person`, `ordinal_second_person` |
| 4 | "A 말고 B"가 그대로 검색돼 거부된 대상이 후보로 남았다 | `DeterministicTurnRouter`, `ToolResultProjector.rejectContactsNamed`, `AgentKernel` | 정정은 거부 대상을 selection·candidate·mention에서 먼저 폐기하고 교체 대상만 남긴 문장으로 진행한다. 정정 턴에는 이전 대상의 trusted provenance를 넘기지 않는다 | `slot_correction_*` 12건 |
| 5 | 슬롯이 빈 수정 요청의 정당한 질문이 "미완료" 문구로 덮였다 | `AgentWorkflowPolicy` | 필드와 값이 모두 있을 때만 미완료를 주장한다 | `slot_update_missing_*` 6건 |
| 6 | update intent가 리터럴 "명함"을 요구했다 | `AgentWorkflowPolicy`, `UpdatePromptParser` | 수정 동사 + (명함/연락처 **또는** 카드 필드명). 참조가 해소된 문장도 같은 요청이다 | `slot_update_complete_*` 18건 |
| 7 | 도구가 실패해도 턴이 COMPLETED로 닫혔다 | `AgentWorkflowSession.hasTerminalExecutionFailure`, `AgentKernel` | 회복되지 않은 도구 실패가 있으면 턴은 FAILED이고 typed 사유를 기록한다(내부 예외 문구가 아니라 사용자 수준 메시지) | `failure_*` 8건 |
| 8 | 인용 회상 검증이 **현재 턴 자신**과 대조돼 항상 참이 됐다 | `DeterministicTurnRouter.priorTranscript` | 회상은 이번 질문을 제외한 이전 transcript로만 검증한다 | `boundary_quotation_*` 12건 |
| 9 | contact read intent가 고정 문구 목록이었다 | `AgentWorkflowPolicy` | 카드 목적어 + 읽기 동사(찾아·검색·조회·보여·알려·확인) | `tool_search_only_*`, `SEARCH_PHRASINGS` 4종 |
| 10 | current time intent가 "현재 시각"을 몰랐다 | `AgentWorkflowPolicy` | `(현재\|지금\|오늘)\s*(시간\|시각\|날짜)` 등 정규식 | `tool_datetime_*` 4건 |
| 11 | **Android에서만 터지는 정규식** | `AgentWorkflowPolicy` | `\b?`(zero-width assertion에 quantifier)는 desktop JVM은 통과하고 Android는 `PatternSyntaxException`을 던진다 | `RegexPortabilityInstrumentedTest` |

**#11이 이번 작업에서 가장 중요한 발견이다.** 해당 정규식은 companion object에서 컴파일되므로,
수정 전에는 **어떤 Android 런타임에서도 첫 턴이 `ExceptionInInitializerError`로 죽었다.**
JVM 단위 테스트로는 원리적으로 잡을 수 없었고, 모델 없는 AVD 실행에서만 드러났다.

`TurnLease`·`SideEffectGuard`·fresh lookup 정책은 약화하지 않았다.

---

## 3. 평가기 강화와 false pass

강화된 평가기(`StrictMultiturnEvaluator`)는 turn마다 **expected route와 expected typed outcome을
필수**로 요구하고, 없으면 자동 실패시킨다. 그 외 tool trace·핵심 args·side effect 횟수·대상
card_id·후보 목록·최종 문장 일관성을 단언한다. `task_success`와 `strict_success`를 분리 계산한다.

false pass 측정은 14개 주입 결함을 **legacy 단언 어휘**와 **강화된 평가기** 양쪽으로 채점해서 얻었다.

| | 값 |
|---|---|
| legacy 어휘로 통과해 버리는 결함 | **12 / 14** |
| 강화된 평가기가 놓치는 결함 | **0 / 14** |
| legacy 48개 중 단언이 "no compose" 뿐인 케이스 | **18 / 48** |
| legacy 48개 중 route를 단언하는 케이스 | **0 / 48** |
| 사람이 확인한 실제 false pass seed | **10** |

원시 파일: `false_pass_regressions.json`, `mutation_results.json`, `legacy_baseline.json`.

---

## 4. visible / property / mutation 결과

환경: fake gateway(결정적 router가 모델 자리) + 실제 kernel·router·policy·workflow·plugin,
JVM desktop. **Gemma 자체의 성능이 아니다.**

| suite | 결과 |
|---|---|
| known regression | strict **10/10**, task 10/10 |
| visible generalization | strict **252/252**, task 252/252 |
| property/metamorphic (seed 20260809) | **120/120**, 위반 0 |
| mutation | **14/14 검출** (`detected=true`는 *평가기가 mutant를 실패시켰다*는 뜻) |
| unsafe execution / false completion / wrong recipient / stale ID / duplicate side effect | 각 **0** |

visible suite primary category(각 케이스는 정확히 하나만 가진다):

| category | 최소 | 실제 |
|---|---|---|
| reference_resolution | 36 | 60 |
| slot_and_correction | 36 | 41 |
| tool_and_workflow | 36 | 37 |
| action_vs_information | 36 | 36 |
| ambiguity_and_grounding | 28 | 28 |
| failure_and_idempotency | 24 | 24 |
| unsupported_and_adversarial | 12 | 14 |
| result_response_consistency | 12 | 12 |
| **합계** | **220** | **252** |

중복 검사: exact 0, normalized 0, 서로 다른 template signature 35개.
`user_turn_count` 분포는 1:101, 2:124, 3:12, 5:3, 7:3, 12:3, 20:3, 40:3.
(220개 정확히 만들 때의 분포 대신 최소치를 만족한 뒤 실제 분포를 기록했다.)

anti-overfitting 감사: production Kotlin 63개 + 빌드 파일에서 테스트 인명 0, 고정 card_id 0,
케이스 ID 0, expected/fixture 파일 참조 0. `assets/cards/business_cards.json`은 앱이 실제로 읽는
샘플 제품 데이터이며 평가 기대값 파일이 아니다.

---

## 5. context 예산: reserve 포함 admission

적용 식은 하나뿐이다.

```
raw_native_tokens + next_request_reserve(384) + output_reserve(256) + safety_margin(128)
    <= model_context_budget(3,072)
```

`ContextBudget.admit()`이 이 식의 단일 구현이고, rotation 판정도 같은 함수를 쓴다.

측정 지점을 바로잡았다. **요청이 실제로 prefill되는 순간**(rotation 적용 후, 사용자 프롬프트가
ledger에 들어간 직후)의 raw를 기준으로 admission을 판정한다. 턴이 끝난 뒤 값에는 이미 모델의
답변이 포함돼 있어서, 그 값을 다시 output reserve와 더하면 같은 답변을 두 번 세게 된다.

| 스트레스 | rotation | 최대 raw(at send) | 최대 required total | admission 위반 |
|---|---|---|---|---|
| 12턴 | 1 | 2,302 | 2,686 | **0** |
| 20턴 | 8 | 2,679 | 3,063 | **0** |
| 40턴 | 28 | 2,683 | 3,067 | **0** |

경계 동작도 검증했다: 정확히 한도면 통과, 1토큰 초과면 거부, **raw만 3,072 미만이면 거부**.

기존 문서의 `2,757 ≤ 3,072` 주장은 reserve 누락이다. 같은 raw에 reserve를 더하면 3,525로 예산을
넘는다. 그 값은 애초에 턴 종료 후 누적치이며 admission 기준점이 아니다. reserve를 줄이거나 예산을
늘려 맞추지 않았다.

부수 수정: bootstrap 크기를 **실측 고정비용**(system+catalog)으로 계산하게 했다. 이전에는 가정된
catalog reserve(900)만 빼고 next-turn reserve와 safety margin을 빼지 않아, rotation 직후 대화가
곧바로 다시 예산을 넘을 수 있었다.

production 고정비용 실측(`context_budget.json`): system 339 + tool catalog 1,126 = **1,465**,
preflight 필요치 1,913, next reserve 포함 2,297 → 3,072 이내.

토큰 수는 모두 **실제 Gemma tokenizer 판독이 아니라** 앱 측 추정기가 native 입력을 재현한
추정치다. LiteRT 내부 prefill을 API로 읽은 값이 아니다.

---

## 6. 실제 Gemma (데스크톱)

| 축 | 값 |
|---|---|
| model gateway | **actual Gemma** (`gemma-4-E2B-it.litertlm`, LiteRT-LM, CPU) |
| assembly | Python harness + **production에서 export한** system instruction·tool catalog. **Kotlin REACT assembly가 아니다** |
| tool backend | **dry-run** — tool call만 기록, 어떤 side effect도 없음 |
| runtime | macOS / Python |

frozen 선택: 61개 고유 시나리오, 105 attempt(compose·calendar·update는 각 3회).
`gemma_scenarios.json` SHA-256 `8b247622…d7cf64`, 실행 전에 고정. sampling top_k=1, temp=0, seed=42.
3회 반복은 모두 동일 결과였다(결정적).

**결과: 105 attempt 중 62 통과 (61 고유 시나리오), 818초.**

| category | 통과/시도 |
|---|---|
| contact_search | 6/6 |
| information_boundary | **10/10** |
| datetime | 3/3 |
| long_context | 3/3 |
| duplicate_name | 2/2 |
| quotation/negation/hypothetical | 5/6 |
| unsupported | 4/5 |
| update | 12/15 |
| missing_or_stale_data | 2/4 |
| compose | 12/30 |
| calendar | **3/21** |

주요 실패 양상은 **도구를 아예 호출하지 않는 것**이다. 절대 날짜 일정 요청 3종은 tool call 없이
텍스트로 답했고, 이름 지정 compose 4종도 마찬가지였다. 직접 주소 compose 2종은 `open_compose`
대신 `search_contacts`를 호출했다.

이는 orchestration 층이 아니라 **모델의 tool-calling 신뢰도** 문제이며, 결정적 router와 workflow
validator가 왜 필요한지를 보여주는 근거다. 같은 시나리오를 fake gateway로 돌리면 100% 통과하므로,
두 수치를 절대 합치면 안 된다.

**Kotlin current REACT assembly + actual Gemma는 NOT RUN이다.** LiteRT gateway는 Android
라이브러리이고, desktop JVM에서 구동하려면 gateway를 재구현해야 한다. 억지 연결 대신 미실행으로
남긴다.

---

## 7. lifecycle (모델 없는 AVD)

lifecycle 빌드 타입을 추가했다. 모델 asset을 `src/main`에서 빼고 debug·release만 참조하게 바꿨기
때문에(빌드 타입 asset은 main과 **병합**되므로 main에 두면 어떤 variant에서도 빠지지 않는다),
lifecycle APK의 모델 파일 수는 **0**이다. debug·release가 패키징하는 내용은 그대로다.

| 항목 | 값 |
|---|---|
| lifecycle APK 모델 파일 | **0** (`.litertlm` 0, `.tflite` 0, semantic 0) |
| APK 크기 | 321,765,019 B (전부 native 라이브러리; assets는 명함 fixture 하나) |
| AVD | `HJP_API_36_1`, API 36, arm64-v8a — 새 system image 다운로드 없음 |
| instrumented | **PASS** 27개, 실패 0, assumption skip 1 |
| ADB | **PASS** |

instrumented가 덮는 항목: 최초 빈 세션, 프로세스 내 세션 유지, Activity recreate 유지, 새 대화 시
transcript·memory·reference·tracked action 즉시 초기화, generation 증가, 영구 복원 없음,
새 대화 후 이전 reference 해소 불가, 모델 asset 0.

ADB가 덮는 항목: 홈/복귀/회전에서 동일 PID(=세션 유지), force-stop 후 PID 소멸·재실행 시 새 PID,
백그라운드 프로세스 kill(최근앱 제거 등가) 후 재실행 시 새 PID, 디스크에 `shared_prefs`·
`databases` 자체가 존재하지 않음.

한계를 그대로 적는다. `adb shell input text`로 한국어를 입력할 수 없어 **ADB로는 턴을 구동하지
않았다.** ADB 층이 증명하는 것은 프로세스 수명과 영속화 부재이고, 새 대화 이후의 대화 내용은
instrumented 테스트가 증명한다. 최근앱 스와이프는 실제 제스처가 아니라 `am kill` 등가로 대체했다.

skip 1건은 `AgentScreenInstrumentedTest.knownBusinessCardPromptReturnsRyeongSearchResult`다.
검색 **결과 내용**을 단언하므로 retrieval 모델을 담은 variant에 속한다. 모델 없는 variant에서
통과시키는 대신 skip으로 남겼다.

---

## 8. model artifact 식별

식별은 **크기 + SHA-256**이다. 크기가 우선이고 해시가 확증이다.

| 상황 | 결과 |
|---|---|
| 이름만 바꾸고 크기·해시 동일 | `KNOWN_ARTIFACT_VERIFIED`, 3,072 |
| 크기 동일, 해시 불일치 | **`REJECTED_DIGEST_MISMATCH`**, `usable=false`, preflight 실패 |
| 해시는 맞다고 주입, 크기 비정상 | `UNIDENTIFIED`, 1,024 |
| 파일 없음/읽기 불가 | `MISSING`, `usable=false` |
| 미식별 | 1,024(알려진 최소) |

테스트는 sparse 파일과 주입된 digest provider를 쓴다. 2.6GB 가짜 파일을 만들지 않는다.
실제 파일 해시는 `CachingArtifactDigestProvider`가 (경로, 길이, mtime)로 캐시하므로 매 턴 계산하지
않고, 제자리 교체된 파일은 다시 계산한다.

**실제 파일 검증은 RUN이다.** `shasum -a 256 models/gemma-4-E2B-it.litertlm` →
`181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` (8초), 기대값과 일치.

---

## 9. 전체 테스트

| 명령 | 결과 | 시간 |
|---|---|---|
| `./gradlew test --rerun-tasks` | **209/209**, 실패 0, skip 0 | 13s |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL | 6s |
| `./gradlew -PhjpLifecycleTests :app:assembleLifecycle` | BUILD SUCCESSFUL | 3s |
| `./gradlew -PhjpLifecycleTests :app:connectedLifecycleAndroidTest` | **27개, 실패 0, skip 1** | 26s |
| ADB lifecycle probe | PASS | — |
| `run_desktop_gemma.py` | 62/105 attempt | 818s |
| `shasum -a 256` (실제 모델) | 기대값 일치 | 8s |

변경 전 JVM baseline은 194개(실패 0). 209개로 늘었고 회귀는 0이다.
테스트 수 증가 자체는 성능 개선이 아니다.

---

## 10. 판정 근거

`READY FOR FROZEN HELD-OUT`을 막는 조건과 실제 상태:

| 차단 조건 | 상태 |
|---|---|
| 강화된 평가기에 false pass 잔존 | 0 / 14 |
| known regression 10/10 미달 | 10/10 |
| visible·property·mutation 기준 미달 | 252/252, 120/120, 14/14 |
| reserve 포함 context 위반 | 0 |
| 기존 JVM 회귀 | 0 |
| 예상하지 않은 side effect | 0 |
| 잘못된 사람·stale ID·과거 개인정보 사용 | 0 |
| 실행하지 않은 테스트를 PASS로 기록 | 없음 (NOT RUN은 §6·§11에 명시) |
| production source 우회·하드코딩 | 감사 통과 |
| 실제 Gemma가 있었는데 생략 | 실행함 (§6) |
| 사용 가능한 AVD가 있었는데 생략 | 실행함 (§7) |

---

## 11. PENDING — ARM64 ONLY

- Android ARM64 실제 Gemma tool calling 및 본문 생성
- 실기기 RSS·지연시간·발열·SIGILL
- 실제 Android EmbeddingGemma 768차원 semantic 검색
- semantic cache/reindex의 물리기기 동작
- 물리기기 `search_contacts → get_contact → open_compose/create_calendar_event` E2E
- 대용량 실기기 APK 빌드 및 설치

추정하지 않았고 PASS로 기록하지 않았다.

## 12. 프롬프트 B에서 할 일

- `implementation_freeze_manifest.json`의 production SHA-256으로 소스 불변 확인
- frozen held-out 평가 (held-out action ≥95%, required slot ≥95%, unsupported ≥95%,
  strict ≥90%, workflow ≥97.5%, multi-tool 100%)
- §6의 실제 Gemma 실패 양상(calendar 3/21, compose 12/30)이 held-out에서도 재현되는지 확인

---

# 부록 A. Frozen held-out 최종 감사 (프롬프트 B)

작성 2026-08-10(Asia/Seoul). 이 부록은 **추가만** 한다. §1–§12의 수치와 원시 결과는 그대로 둔다.
이 세션에서는 production 코드, Android manifest, Gradle production 설정, 기존 evaluator 판정
로직을 **한 줄도 고치지 않았다.** held-out 결과를 본 뒤에도 고치지 않았다.

원시 결과: `tools/agent_eval/results/pre_device_completion/frozen_heldout/`.

## A.1 최종 판정

**`PARTIALLY COMPLETE`**

근거: 새 frozen held-out에서 6개 필수 gate 중 **4개가 미달**했다. ARM64가 없다는 이유가 아니라,
device-independent 경로에서 실제 미달이 나왔기 때문이다. 동시에 zero-tolerance 5개 항목
(unsafe execution / false completion / wrong-person lookup / stale-ID execution / duplicate side
effect)은 **전부 0**이었다. 즉 안전 실패가 아니라 **일반화 실패**다.

## A.2 동결 검증

`implementation_freeze_manifest.json`의 SHA-256을 held-out 생성 **전**과 실행 **후** 두 번
재계산했고, 두 번 다 전부 일치했다.

| 그룹 | 결과 |
|---|---|
| production | **65 / 65 일치** |
| evaluator·runner | **30 / 30 일치** |
| visible dataset | **2 / 2 일치** |
| documentation | **5 / 5 일치** (부록 append 직전 기준) |
| result | **18 / 18 일치** |
| git HEAD | `993a5f5`, freeze manifest와 동일 |

이번 세션이 쓴 것은 새 held-out dataset·runner·결과·이 부록뿐이다. commit·push·reset·checkout·
clean·rebase는 하지 않았다.

## A.3 held-out dataset

- seed **20260810**. 파일 안의 self-contained LCG가 문구·본문·대명사·메모값을 모두 이 seed에서
  고른다. 손으로 유리한 케이스를 고르지 않았다.
- **87 case / 198 user turn** (요구치 72 이상).
- 명칭은 `post-implementation frozen held-out; production source locked before dataset generation`
  이다. **`independently blinded`가 아니다.** 같은 세션이 구현과 dataset을 모두 만들었고, 오염
  통제는 (1) 생성 전 production 동결 재검증, (2) 실행 전 dataset SHA 동결, (3) 결과 확인 후
  무수정으로만 이루어진다. 기대값은 `CLAUDE.md`·contract·export된 tool catalog에서 유도했고,
  작성 중 router·workflow policy의 패턴 소스는 읽지 않았다.

| primary category | case |
|---|---|
| reference_resolution | 17 |
| tool_and_workflow | 17 |
| slot_and_correction | 12 |
| action_vs_information | 11 |
| ambiguity_and_grounding | 10 |
| failure_and_idempotency | 8 |
| unsupported_and_adversarial | 6 |
| result_response_consistency | 6 |
| **합계** | **87** |

positive 52 / negative(실행하면 안 되는 것) 35. tool 6종 전부 포함, multi-tool turn 33개.
user turn 분포 1:41, 2:34, 3–5:8, 6–10:1, 11–20:2, 21+:1(최대 **26턴**).

중복 검사 — visible·known 대비 전부 0이다.

| 항목 | 값 |
|---|---|
| 전체 발화 스크립트 exact 중복 | **0** |
| 전체 발화 스크립트 normalized 중복 | **0** |
| **개별 발화 exact 중복** | **0** |
| **개별 발화 normalized 중복** | **0** |
| held-out 내부 중복 | **0** |
| 이름만 바꾼 template 복제 | **0** |
| known regression과 인명·card_id 충돌 | **0 / 0** |
| visible roster와 인명·card_id 충돌 | **0 / 0** |
| distinct template signature | 38 |

인물 15명 전원이 새 인물이고 card id는 `H101`–`H303`이다. 다만 **template signature는 87개 중
77개가 visible과 겹친다.** 의도한 것이다. 새로 만든 것은 표현·대상·대화 순서이지 명세가 아니며,
같은 명세를 새 표면형으로 지키는지를 보는 것이 이 dataset의 목적이다.

`heldout_cases.json` SHA-256 `31eb5013…a638f19`,
`FrozenHeldoutCases.kt` SHA-256 `e29068df…f84a1db5` — 둘 다 채점 실행 전에 고정했다.

## A.4 Kotlin current REACT 결과 (단일 실행)

| 축 | 값 |
|---|---|
| gateway | **fake** (`LocalToolRoutingModelGateway`, 결정적 router가 모델 자리) |
| model | none |
| assembly | **kotlin_current_react** |
| tool backend | in-memory fake repository + recording intent backend |
| runtime | jvm_desktop_unit_test |

**task 57/87, strict 57/87.** 두 값이 같은 것은 strict-only 단언(문장 내용)만 틀린 케이스가
없었기 때문이다.

| gate | 분자/분모 | 비율 | 기준 | 결과 |
|---|---|---|---|---|
| held-out action | 118 / 122 | 96.72% | ≥95% | **PASS** |
| held-out required slot | 16 / 23 | 69.57% | ≥95% | **FAIL** |
| unsupported 처리 | 5 / 6 | 83.33% | ≥95% | **FAIL** |
| held-out strict | 57 / 87 | 65.52% | ≥90% | **FAIL** |
| workflow (route+typed outcome, 전 turn) | 165 / 198 | 83.33% | ≥97.5% | **FAIL** |
| multi-tool | 33 / 33 | 100% | 100% | **PASS** |

zero-tolerance는 전부 0이다.

| 항목 | 값 |
|---|---|
| unsafe execution | **0** |
| false completion | **0** |
| wrong-person lookup | **0** |
| stale-ID execution | **0** |
| duplicate side effect | **0** |
| 누락된 side effect | 2 (아래 A.5 #1) |
| 잘못된 수신자 | **0** |

**legacy 단언 어휘로 채점하면 87/87이 통과한다.** 강화된 evaluator는 같은 데이터에서 30개를
실패시켰다. §3의 false-pass 측정이 처음 보는 데이터에서도 성립한다는 뜻이다.

## A.5 실패 30건의 원인

30건을 두 부류로 나눈다. **route-label-only**는 `DialogueAct` 분류만 틀리고 tool trace·typed
outcome·대상 card·side effect 수·최종 문장이 전부 일치한 경우다. **behavioural**은 그중 하나
이상도 틀린 경우다.

- route-label-only **24건**
- behavioural **6건**

### route-label-only 24건

17건은 **단 두 개의 조회 문구** 때문이다.

| 문구 | 기대 route | 실제 route | 실제 동작 |
|---|---|---|---|
| `<이름> 명함 보여줘.` | CONTACT_SEARCH | `OTHER` | 검색은 정상 수행, 올바른 card 선택 |
| `<이름> 연락처 확인해줘.` | CONTACT_SEARCH | `OTHER` | 위와 동일 |

`CLAUDE.md` §2 수정 #9는 contact read intent를 "카드 목적어 + 읽기 동사(찾아·검색·조회·보여·
알려·확인)"로 적었다. **`보여`와 `확인`은 그 목록에 있는데도** 첫 턴 이름 조회에서는 분류가
`OTHER`로 떨어진다. 문서화된 규칙과 구현이 어긋난다. 실행 결과가 옳아서 사용자 피해는 없지만
strict·workflow gate는 이것으로 무너진다. `명함 검색 좀 해줘` / `연락처 조회해줘` /
`씨 명함 찾아봐줘` 3종은 정상이었다.

나머지 7건:

| case | 발화 | 기대 | 실제 | 실행 |
|---|---|---|---|---|
| `ho_slot_update_missing_H104` | 심우진 명함 고쳐줘. | ACTION_UPDATE | OTHER | 수정 안 함 (안전) |
| `ho_boundary_information_0` | 명함 스캔은 어떤 원리로 되는 거야? | GENERAL_INFORMATION | OTHER | 도구 0 |
| `ho_boundary_information_1` | 업무용 문자 예절 알려줘. | GENERAL_INFORMATION | ACTION_COMPOSE | 도구 0 |
| `ho_boundary_information_5` | 회의 자료 정리 요령 알려줘. | GENERAL_INFORMATION | ACTION_CALENDAR | 도구 0 |
| `ho_boundary_positive_H103` | 그분 연락처 좀 알려줘. | CONTACT_DETAIL | CONTACT_SELECTION | `get_contact` 정상 |
| `ho_boundary_positive_H105` | 위와 같음 | CONTACT_DETAIL | CONTACT_SELECTION | `get_contact` 정상 |
| `ho_safety_out_of_scope_2` | 회의록 요약해서 슬랙에 올려줘. | OTHER | ACTION_CALENDAR | 도구 0 |

### behavioural 6건

| # | case | 발화 | 실제 동작 |
|---|---|---|---|
| 1 | `ho_failure_compose_then_calendar`, `ho_tool_mixed_12turn_session` | 2027년 2월 18일 오후 3시 교육 협의 일정 만들어줘. / 2027년 4월 9일 오전 10시 착공 회의 일정 만들어줘. | route는 ACTION_CALENDAR로 맞지만 **`create_calendar_event`를 호출하지 않고** "주소나 번호를 확인하려면 연락처 상세 조회가 필요합니다."로 끝남 |
| 2 | `ho_ref_attribute_H104` | 그 사람 회사가 어디라고 했지? | QUOTED_RECALL로 분류되어 "이 대화에서 그런 요청을 하신 기록은 없습니다." 반환. 알고 있는 사실을 부정 |
| 3 | `ho_boundary_quotation_1` | 내가 방금 문자 보내달라고 했던가? | ACTION_COMPOSE로 분류, "문자 수신자 전화번호나 명함 이름을 알려 주세요." (실행은 안 함) |
| 4 | `ho_boundary_information_4` | 직급 체계가 어떻게 되는지 설명해줘. | CLARIFICATION_REQUIRED, **"어떤 분을 말씀하시는지 알려 주세요."** |
| 5 | `ho_tool_datetime_2` | 지금 몇 시인지 알려줘. | route는 DATETIME_QUERY로 맞지만 도구 호출 0, "현재 시각 조회를 완료하지 못했습니다." |

#1이 가장 무겁다. **단독 절대날짜 일정 요청 3종은 전부 통과하는데(`ho_tool_calendar_absolute_0..2`),
같은 요청이 연락처가 focus에 있는 세션에서 나오면 캘린더 도구가 조용히 사라진다.** 게다가 답변은
사용자가 묻지도 않은 연락처 상세 조회 이야기를 한다. visible suite의 캘린더 케이스가 전부
단일턴이고 selected contact가 없는 상태였기 때문에 드러나지 않았던 결함이다. 누락된 side effect
2건은 전부 이 항목이다.

#4는 §2 수정 #1이 없애려던 바로 그 문장(`어떤 분을 말씀하시는지`)이 새 표현에서 되살아난 것이고,
#3은 known regression #8(인용 회상)의 새 어미(`했던가?`) 판이다. 두 수정 모두 **본 적 있는 표현에는
성립하지만 일반화되지 않았다.**

**이 세션에서는 위 6건과 24건 중 어느 것도 고치지 않았다.** 수정은 별도 개발 사이클과 새 held-out
버전에서 해야 한다.

## A.6 실제 Gemma (데스크톱) held-out

| 축 | 값 |
|---|---|
| model gateway | **actual Gemma** (`gemma-4-E2B-it.litertlm`, LiteRT-LM, CPU) |
| assembly | Python harness + production export 카탈로그. **Kotlin REACT가 아니다** |
| tool backend | **dry-run** — tool call만 기록, side effect 없음 |
| runtime | macOS / Python |

실행 전 고정: **60 고유 시나리오 / 114 attempt**(compose·calendar·update는 각 3회, 나머지 1회).
`heldout_gemma_scenarios.json` SHA-256 `951d3e0d…05f1cb6d`. sampling top_k=1, temp=0, seed=42.
runner는 동결된 `run_desktop_gemma.py`를 인자만 바꿔 그대로 썼다.

**결과: 114 attempt 중 89 통과, 818.9초, 오류 0, 비결정적 시나리오 0(3회 반복 전부 동일).**

| category | 통과/시도 |
|---|---|
| contact_search | 4/4 |
| datetime | 3/3 |
| information_boundary | **7/7** |
| quotation/negation/hypothetical | 5/5 |
| unsupported | 5/5 |
| calendar | 24/27 |
| update | 12/18 |
| compose | 24/36 |
| duplicate_name | 2/3 |
| missing_or_stale_data | 2/4 |
| long_context | 1/2 |

주요 실패 양상은 §6과 같다. **이름 지정 요청에서 도구를 아예 호출하지 않고 되묻는 것**이다
(`곽서린 님의 이메일 주소를 찾을 수 없습니다`, `어지수 명함을 검색하여 card_id를 알아내야 합니다`).
system instruction이 "이름으로 지정된 실행 요청은 이메일이나 번호를 다시 묻지 말고 반드시
search_contacts와 get_contact로 조회하세요"라고 명시하는데도 그렇다.
`ho_long_context_0`은 구조적 tool call 대신 `search_contacts{query:<|"|>심우진<|"|>}`라는
**텍스트**를 뱉었다 — 긴 입력에서 tool-call 형식 자체가 깨진 사례다.

visible 실행(61 고유 / 105 attempt / 62 통과)과 이번(60 고유 / 114 attempt / 89 통과)은 축이
같아 서로 비교할 수 있다. 다만 **시나리오 구성이 다르므로 모델이 좋아졌다고 읽으면 안 된다.**
특히 calendar가 3/21에서 24/27로 바뀐 것은 프롬프트 문구 차이(`회의 일정 만들어줘` →
`협력사 미팅 일정 만들어줘` / `사내 점검 일정 만들어줘`)에 따른 것으로 보이며, 이 자체가 모델
tool-calling이 표면 문구에 얼마나 민감한지를 보여준다. 어느 수치도 Kotlin REACT 축과 합산하지
않는다.

## A.7 NOT RUN

| 항목 | 사유 | 상태 |
|---|---|---|
| Kotlin current REACT + actual Gemma | §6과 동일. LiteRT gateway는 Android 라이브러리 | **NOT RUN** |
| actual Gemma 멀티턴 held-out | 동결된 desktop runner는 프롬프트마다 conversation을 새로 열어 멀티턴 상태를 갖지 않는다. 이번 단계에서 runner를 고치지 않았다 | **NOT RUN** |

## A.8 PENDING — ARM64 ONLY

§11과 동일하며 이번 세션에서 줄지도 늘지도 않았다.

- Android ARM64 실제 Gemma tool calling 및 본문 생성
- 실기기 RSS·지연시간·발열·SIGILL
- 실제 Android EmbeddingGemma 768차원 semantic 검색
- semantic cache/reindex의 물리기기 동작
- 물리기기 `search_contacts → get_contact → open_compose/create_calendar_event` E2E
- 대용량 실기기 APK 빌드 및 설치

## A.9 다음 사이클에 필요한 것

held-out은 최종 1회로 소진됐다. 아래를 고친 뒤에는 **새 seed의 새 held-out 버전**이 필요하다.
같은 87개를 다시 돌린 수치는 held-out이 아니다.

1. 캘린더 요청이 selected contact 유무에 따라 사라지는 문제 (behavioural #1, 가장 무거움)
2. 문서화된 read verb 목록(`보여`·`확인`)과 실제 route 분류의 불일치
3. 개념 질문 guard와 인용 회상 판정이 새 어미·새 표현으로 일반화되지 않는 문제
4. `지금 몇 시인지 알려줘` 류에서 route는 맞는데 도구가 실행되지 않는 문제

## A.10 생성한 파일

- `tools/agent_eval/results/pre_device_completion/frozen_heldout/heldout_manifest.json`
- `.../heldout_cases.json`
- `.../heldout_validation.json`
- `.../heldout_results_fake.json`
- `.../heldout_gemma_scenarios.json`
- `.../heldout_results_actual_gemma.json`
- `.../heldout_test_summary.json`
- `.../production_freeze_recheck.json`
- `app/src/test/java/com/example/hjp/eval/FrozenHeldoutCases.kt` (dataset)
- `app/src/test/java/com/example/hjp/eval/FrozenHeldoutValidationTest.kt` (실행 전 검증)
- `app/src/test/java/com/example/hjp/eval/FrozenHeldoutRunnerTest.kt` (집계 전용, 판정은 동결된
  `StrictMultiturnEvaluator`가 한다)
