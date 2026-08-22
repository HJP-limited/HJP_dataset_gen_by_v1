# Gemma 4 E2B 에이전트 최대 성능 평가

평가일은 2026-08-01(Asia/Seoul)이다. 모델 교체 없이 Gemma 4 E2B와
EmbeddingGemma를 유지했고, held-out 기대값은 모든 변경 선택이 끝난 뒤 한 번만 읽었다.
결론은 **end-state 안전성과 기존 64 회귀는 크게 개선됐지만 production gate는 실패**다.

## 1. 고정 baseline

| 항목 | 값 |
|---|---|
| Git | `993a5f5613235c2192f6c34b06841319813aefeb`, branch `android-app`, 기존 변경이 많은 dirty worktree |
| Ryeong | `b543a189249df7d87523564b844cb472b15fcbf3` |
| Gemma 4 E2B | 2,588,147,712 bytes; `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| EmbeddingGemma | 179,131,736 bytes; `37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5` |
| tokenizer | 4,683,319 bytes; `d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7` |
| Mac runtime | LiteRT-LM 0.14.0, CPU, constrained decoding, top-k 1, top-p 1, temperature 0, seed 42 |
| Android runtime | LiteRT-LM 0.13.1; RAG SDK 0.3.0; Room 2.8.3; DB v3 + `MIGRATION_2_3` |
| baseline APK | 322,407,251 bytes; `3c4c6feafe8cde00a01d733a7c0960f71f6e497d748f9ac09d9f3e4ead026593` |

기존 raw와 dirty diff는 `tools/agent_eval/results/baseline_manifest.json`에 보존했다.
기존 64 baseline은 intent 64/64, action 60/64, slot 38/39, workflow 39/40,
multi-tool 15/15, contact 11/11, strict 54/64, body 15/17, unsupported 4/7,
multiturn 40/40, unsafe/false-completion 0이었다.

## 2. 데이터와 평가 방식

신규 `agent-eval-v1`은 213개로 development/validation/held-out 각 71개다.
201개는 독립 single-turn이고 12개는 session runner용 multi-turn이다. split별 이름,
도메인, 표면 문형을 분리했다. sealed held-out reference SHA-256은
`6a13b63528c0ef0d7a4d7473a28465a943c253f32a003c32424b43aa154f8c2b`이다.

BFCL식으로 모델 intent/action/slot, parser/schema, orchestrator 첫 tool/trajectory,
validator, ToolRegistry/mock, final task state를 분리했다. τ-bench식 final-state 판정은
수신자·채널·본문, 일정 시각/참석자, update 대상/값, mutation/confirmation 상태를 검사한다.
validator 차단이나 orchestrator 성공을 모델 성공으로 올리지 않았다. 자세한 정의는
`docs/AGENT_EVALUATION_METHODOLOGY.md`에 있다.

## 3. 변경과 ablation

| 변경 계층 | development/validation 근거 | 채택 결과 |
|---|---|---|
| capability action policy | 실제 전송·문자 전송·calendar 삭제 3개 repair prompt 변형 모두 3/3 | 짧은 intent 고정 action repair 채택; 기존 unsupported 4/7→7/7 |
| calendar slot projection | title의 generic UI suffix 때문에 5개 argument 실패 | 원문에 짧은 title이 verbatim일 때만 제거; 5/5 회복 |
| mixed content goal | 완료 지시와 수신자 사실이 한 slot에 섞여 compose 자체가 중단 | agent directive만 제거하고 `확인했습니다` 보존; false-completion 사례 회복 |
| attendee slot validator | 이름이 있는 일정에서 get-current/search/get 순서 누락 | 원문 참석자 보존 검사와 slot-only 1회 repair; 기존 contact calendar 회복 |
| CARD_ID provenance | stale ID가 compose schema에 표현되지 않음 | CARD_ID는 표현하되 session-grounded가 아니면 ToolRegistry 전에 차단 |
| cross-field repair | generic repair가 올바른 intent까지 바꿈 | intent-fixed, generic, one-field 3종 비교; validation 순개선인 intent-fixed 선택 |
| body prompt/repair | exact goal 누락과 placeholder | 원문/확정 slot 재전달, keyword validator, targeted body repair를 비교했으나 일반 개선 없음; 실패 변경은 되돌림 |

validation 이전 결과 대비 최종 v2는 final intent `63→64/67`, action `64→65/67`,
required slot `39→42/42`, task `63→64/67`, strict `60→62/67`로 개선됐다.
다만 first-tool/workflow는 `43→42/44`로 1건 하락했다. 이 trade-off를 숨기지 않고
held-out 전에 변경을 고정했다.

## 4. 기존 64 최종 회귀

| 지표 | baseline | 최종 |
|---|---:|---:|
| intent / action | 64/64 / 60/64 | **64/64 / 64/64** |
| required slot / structured | 38/39 / 64/64 | **39/39 / 64/64** |
| first tool / workflow | 39/40 / 39/40 | **40/40 / 40/40** |
| multi-tool / contact chain | 15/15 / 11/11 | **15/15 / 11/11** |
| strict | 54/64 | **63/64 (98.4%, CI 95.3–100)** |
| body success / fact | 15/17 / 미분리 | **17/17 / 16/17** |
| unsupported | 4/7 | **7/7** |
| unsafe / false completion | 0 / 0 | **0 / 0** |

남은 한 건은 compose-error fixture의 정상 안전 중단이지만 본문 exact requirement `안내`가
빠져 strict 실패다. 의도적 backend error fixture 3건 때문에 tool execution은 task
37/40, call 62/65이며, 이는 workflow/task 성공으로 위조하지 않았다.

## 5. validation과 held-out

| 계층 지표 | validation 67 | held-out 67 (95% bootstrap CI) |
|---|---:|---:|
| Stage 1 initial intent | 61/67 (91.0%) | 62/67 (92.5%, 85.1–98.5) |
| final intent | 64/67 (95.5%) | **65/67 (97.0%, 92.5–100)** |
| initial action | 53/67 (79.1%) | 51/67 (76.1%, 65.7–86.6) |
| final action | 65/67 (97.0%) | **61/67 (91.0%, 83.6–97.0)** |
| structured output | 65/67 (97.0%) | **66/67 (98.5%, 95.5–100)** |
| required slot | 42/42 (100%) | **39/42 (92.9%, 83.3–100)** |
| clarify / unsupported | 10/10 / 7/7 | **10/10 / 5/7** |
| body success / fact | 16/17 / 15/17 | **16/17 / 16/17** |
| final-state task | 64/67 (95.5%) | **64/67 (95.5%, 89.6–100)** |
| strict pipeline | 62/67 (92.5%) | **59/67 (88.1%, 79.1–95.5)** |

held-out intent macro-F1은 0.866, action macro-F1은 0.603이다. `<MISSING>`도 별도
prediction class로 포함해 실패를 제외하지 않았다. no-tool end-state는 24/24였고 schema와
argument validation은 모두 100%였다.

### 모델과 실행 계층 분리

| 실행 지표 | validation | held-out |
|---|---:|---:|
| 필요한 첫 tool | 42/44 | 42/44 |
| 전체 workflow | 42/44 | 41/44 |
| multi-tool | 22/22 | 19/22 |
| contact chain | 19/19 | 16/19 |
| ToolRegistry/mock 성공(task) | 41/44 | 41/44 |
| ToolRegistry/mock 성공(call) | 79/82 | 73/76 |
| tool-result transition | 41/41 | 35/41 |
| policy compliance | 67/67 | 67/67 |
| unsafe / false completion | 0 / 0 | 0 / 0 |
| wrong-person / stale 실행 | 0 / 0 | 0 / 0 |

tool backend 실패 3건은 주입된 expected error다. held-out strict 실패의 배타적 주원인은
MODEL_INTENT 1, MODEL_SLOT 3, MODEL_CONTENT 1, SCHEMA/PARSER repair arbitration 1,
VALIDATOR_POLICY capability coverage 2다. 검색과 runtime 주원인은 0이다. 세부 A–E는
`docs/AGENT_FAILURE_ATTRIBUTION.md`에 기록했다.

## 6. 반복 신뢰성

검색, 연락처 이메일/문자 3단계, 상대 날짜 일정, false-completion, no-tool, 실제 전송
unsupported, calendar 삭제 unsupported의 대표 8개를 각각 5회, 총 40회 실행했다.

| 신뢰성 | end-state task | strict model pipeline |
|---|---:|---:|
| pass@1 | 8/8 | 7/8 (CI 62.5–100) |
| pass^3 | 8/8 | 7/8 |
| pass^5 | 8/8 | 7/8 |

timeout/crash는 0, p50/p95는 9.820/15.340초다. 결과 상태 변동은 0이었지만 no-tool
email example은 5/5 모두 안전하게 무실행하면서 structured intent는 실패했다. 따라서
task reliability와 모델 reliability를 별도로 보고한다.

기존 session fixture 멀티턴 40개도 JVM 회귀에서 40/40이다. focus 유지 29/29,
전환 5/5, 대명사/생략 29/29, 숫자 새 검색 4/4, ordinal·동명이인 안전 처리 각 1/1,
wrong-person/stale 실행 0이다.

## 7. 본문 품질

규칙 평가는 held-out body 생성 16/17, 사실/목적 lexical 보존 16/17, placeholder와
거짓 완료 실행 0이다. 기존 64의 종전 blind/manual 평균은 8.1/10이지만, 이번 최종 49개
출력의 새 사람 평가는 완료되지 않았다. `tools/agent_eval/reports/blind_body_review.csv`에
case ID와 순서를 숨긴 worksheet를 만들었고 mapping은 results에 분리했다.

따라서 이번 최종 body quality를 8점 이상이라고 추정하거나 LLM judge로 대체하지 않는다.
사람 평가 미완료는 production blocker다.

## 8. 검색과 Android 환경

최종 50명 keyword/FTS 평가는 192개 R@1/R@5/MRR 모두 1.000, p50/p95
0.074/0.256ms다. 확장 495개는 R@1 0.998, R@5 1.000, MRR 0.999,
p50/p95 0.077/0.165ms다. 5개 대표 query 10회씩 모두 성공, 순위 변형 1종,
process max RSS 23.313MiB다.

AVD regression artifact는 Room FTS 11/11, 100회 100/100, p50/p95 2.334/4.165ms,
PSS 111.834MiB, `KEYWORD_ONLY`, 앱 crash/SIGILL 0이다. AVD 결과는 semantic으로
합산하지 않았다.

최종 APK를 `HJP_API_36_1` AVD(`arm64-v8a`, `ro.kernel.qemu=1`)에 재설치해
migration/FTS/update 재색인/KEYWORD_ONLY workflow 4개를 통과했고, 물리기기 전용
semantic 1개는 assumption skip됐다. AVD 내부 `files/models`의 모델·tokenizer hash는
원본과 일치했고, logcat clean 후 재실행 구간의 앱 fatal/SIGILL은 0이다.

연결된 **물리** 장치는 없다. 물리 ARM64 EmbeddingGemma 초기화, 768차원
query/document embedding, cache/RRF, native latency/PSS는 모두 **NOT RUN**이다.
기존 Mac Gemma 단일 worker max RSS는 2.43GiB이며 이번 반복에서 p50/p95는
9.820/15.340초였다.

## 9. JVM/Android 빌드

```bash
./gradlew clean test :app:assembleDebug :app:assembleDebugAndroidTest
```

150 tasks가 성공했다. XML 기준 JVM tests 93, failures/errors/skipped 0이다:
app 15, agent-contract 12, agent-core 37, search-core 15, tool-contact 9,
tool-android-intents 3, tool-datetime 2.

- debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 322,423,635 bytes,
  SHA-256 `b15c9f7b7356dfacf9c9f7d4483bbcf3ec6f776101414c2603a01dc6996d2ea4`
- test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
  1,054,358 bytes, SHA-256 `7351137565e0c027ceac35d68d525df4242de2b3707e4eb0b8c8766d3eea0f7e`

## 10. best-achievable 및 production gate

현재 고정 구조에서 검증된 best-achievable은 기존 64 strict 63/64, validation task
64/67, held-out task 64/67이다. 안전 지표는 모두 0이고 schema는 100%다. 그러나 다음
gate가 미달이다.

- held-out action 91.0% < 95%
- held-out required slot 92.9% < 95%
- held-out workflow 93.2% < 97.5%, multi-tool 86.4% < 100%
- held-out unsupported 71.4% < 95%
- strict held-out 88.1% < 90%
- 최종 blind human body quality 미평가
- 물리 ARM64 semantic E2E 미실행(AVD keyword fallback만 통과)

따라서 **production gate는 실패**다. Gemma 4/EmbeddingGemma 모델은 유지했지만 Android
production runtime 합격으로 판정하지 않는다. 남은 구조 문제는 capability matrix의 한국어
활용형 coverage와 cross-field repair arbitration이다. 모델/content 문제는 placeholder·미제공
시간 사실과 lexical goal 보존이며, test 문장 hard-code 없이 body 전용 repair를 검토해야 한다.
