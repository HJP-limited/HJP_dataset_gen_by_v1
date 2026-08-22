# Android 명함 AI 에이전트 최종 성능 재평가

평가일은 2026-08-01(Asia/Seoul)이다. 기존 기대값과 `test_cases.jsonl`은 변경하지 않았다. 결론은 **회귀 하한 통과, production gate 실패**다. 실패 이유는 Gemma action 정확도, unsupported 분류, 기존 실패 11개 완전 회복 조건과 물리 ARM64 semantic 검증 부재다.

## 1. 기준 상태와 사전 검증

| 항목 | 확인 결과 |
|---|---|
| Git | `993a5f5613235c2192f6c34b06841319813aefeb`, branch `android-app`; 기존 Ryeong 통합 변경을 포함한 dirty worktree에서 평가 |
| Ryeong 기준 | `b543a189249df7d87523564b844cb472b15fcbf3` |
| EmbeddingGemma | `app/src/main/assets/models/embeddinggemma-300m.tflite`, 179,131,736 bytes, SHA-256 `37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5` |
| tokenizer | `app/src/main/assets/models/sentencepiece.model`, 4,683,319 bytes, SHA-256 `d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7` |
| Gemma 4 E2B | `models/gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| runtime | Android LiteRT-LM 0.13.1, Mac CLI 0.14.0, AI Edge localagents-rag 0.3.0, Room 2.8.3 |
| DB | version 3, 명시적 `MIGRATION_2_3`; destructive fallback 없음 |
| Android 연결 | `emulator-5554`, `arm64-v8a`, `ro.kernel.qemu=1`, AVD `HJP_API_36_1`; 물리기기 없음 |

APK 안의 두 asset은 `Stored` 상태이고 APK에서 직접 읽은 SHA-256도 원본과 같다. `AndroidEmbeddingGemmaEngine`은 asset과 `filesDir/models`의 크기·SHA-256을 비교해 누락 또는 오래된 내부 파일만 교체한다. AVD 내부의 두 복사본도 위 SHA-256과 일치했다.

## 2. JVM 테스트와 빌드

최종 상태에서 아래 명령을 그대로 실행했다.

```bash
./gradlew clean test :app:assembleDebug :app:assembleDebugAndroidTest
```

결과는 `BUILD SUCCESSFUL`(150 tasks: 148 executed, 2 up-to-date)이다.

| 모듈 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| app | 15 | 0 | 0 | 0 |
| agent-contract | 10 | 0 | 0 | 0 |
| agent-core | 31 | 0 | 0 | 0 |
| search-core | 15 | 0 | 0 | 0 |
| tool-contact | 9 | 0 | 0 | 0 |
| tool-android-intents | 3 | 0 | 0 | 0 |
| tool-datetime | 2 | 0 | 0 | 0 |
| 합계 | **85** | **0** | **0** | **0** |

검색·FTS, workflow/validator, staged codec, 날짜 parser, embedding cache/fallback, stale ID, 멀티턴 resolver 테스트가 포함된다. 중간에 추가 AVD 계측 테스트의 nullable 컴파일 오류 1건을 발견해 수정했고, 위 최종 clean 실행은 모두 통과했다.

## 3. Mac Gemma 4 E2B staged 64

- runtime/backend: LiteRT-LM 0.14.0 / CPU
- constrained decoding: 사용
- sampling: `top_k=1`, `top_p=1.0`, `temperature=0`, `seed=42`
- process 성공: 64/64, timeout 0
- 이전 `gemma4-staged-intent-e-64-ryeong-latest-b543a18`과 case-level intent/tool/strict diff: **0건**
- intent worker latency: 평균 12.797 s, p50 12.022 s, p95 24.450 s, max 28.304 s
- content worker latency: 평균 7.322 s, p50 6.151 s, p95 18.159 s
- case worker 합계 latency: 평균 14.856 s, p50 15.353 s, p95 26.786 s, max 34.851 s
- 단일 intent worker 실측 maximum RSS: 2,606,170,112 bytes(2.43 GiB)

### 모델 판단

| 지표 | 결과 |
|---|---:|
| Stage 1 initial intent | 62/64 (96.9%) |
| Stage 1 final intent | 64/64 (100%) |
| initial action | 50/64 (78.1%) |
| final action | 60/64 (93.8%) |
| initial/final structured output | 64/64 / 64/64 |
| initial/final required slot | 37/39 / 38/39 |
| repair 사용 | 14/64 (21.9%) |
| repair 성공 | 12/14 (85.7%) |
| clarify | 10/10 |
| unsupported | 4/7 |
| answer/preview | 7/7 |
| 본문 생성 성공 | 15/17 (88.2%) |
| 본문 품질 | 8.1/10; 17개 전체 분모, 실패 0점 |

본문 평가는 자연스러움, 원문 사실 보존, 말투, 간결성, placeholder·거짓 완료 부재를 0~10점으로 수동 평가했다. `compose_email_explicit_01`의 제목 `인사 인사드립니다.`는 6점, 본문을 생성하지 않은 `false_completion_guard_01`은 0점으로 반영했다.

### orchestrator·tool 실행

| 지표 | 결과 |
|---|---:|
| 필요한 첫 tool | 39/40 (97.5%) |
| 전체 workflow | 39/40 (97.5%) |
| multi-tool | 15/15 |
| contact chain | 11/11 |
| 날짜 계산 | 10/10 |
| schema validity | 64/64 |
| argument validation | 64/64 tool calls |
| tool result 이후 transition | 25/25 |
| tool 실행 성공 | 61/64 |
| ToolRegistry 내부 오류 | 0 |
| unsafe 실행 | 0 |
| 거짓 완료 | 0 |

61/64는 의도적으로 주입한 `search_contacts`, `get_contact`, `open_compose` 오류 fixture 3건을 실패로 센 값이다. 정책 차단을 실행 성공으로 바꾸지 않았고, 모델이 생성하지 않은 결정을 모델 성공으로 기록하지 않았다.

strict는 54/64다. 10개 실패는 다음과 같다.

- action/slot/workflow: `false_completion_guard_01`
- unsupported action: `unsupported_send_email_02`, `unsupported_send_sms_02`, `unsupported_delete_calendar_02`
- 정확한 날짜는 맞지만 title argument가 `… 일정` 또는 `… 작성 화면`으로 변형됨: `contact_calendar_chain_01`, `calendar_absolute_no_datetime_02`, `calendar_relative_tomorrow_02`, `calendar_relative_next_friday_02`, `false_completion_calendar_02`
- compose 오류 fixture의 body에 기대 키워드 `안내`가 없었음: `tool_error_compose_replan_02`

기존 실패 11개 중 10개가 strict 통과했다. 남은 `contact_calendar_chain_01`은 기대 title `후속 상담`을 `후속 상담 일정`으로 생성했다. 날짜와 참석자 이메일 및 3-tool 순서는 맞았다.

## 4. 멀티턴

기존 시나리오를 유지하고 20개를 추가해 40개 2~3턴 시나리오를 검증했다.

| 지표 | 결과 |
|---|---:|
| 전체 | 40/40 |
| focus 유지 | 29/29 |
| 명시적 새 이름 focus 전환 | 5/5 |
| 대명사·주어 생략 | 29/29 |
| 숫자·전화번호 새 검색 분리 | 4/4 |
| 무관한 이전 맥락 분리 | 2/2 |
| ordinal 선택 | 1/1 |
| 동명이인 안전 처리 | 1/1 |
| 잘못된 사람 `get_contact` | 0 |
| stale ID 실행 | 0 |

3턴 focus 전환 요청은 10회 모두 성공했고 rank 변동 1종, p50 0.220 ms, p95 2.213 ms였다. 멀티턴 resolver 결과와 실제 연락처 workflow는 각각 resolver 테스트와 `StructuredAgentKernel`/provenance 테스트로 분리 검증했다.

## 5. Ryeong 검색

### JVM/production-equivalent FTS

50명 fixture의 기존 192개 gate query에서 이전 in-memory 방식과 최신 tiered FTS 모두 Recall@1/5/MRR 1.000이었다. 최신 FTS는 p50 0.092 ms, p95 0.328 ms, process max RSS 16.797 MiB였다.

영문명·부서·산업·지역·태그·메모·존칭·prefix·동의어 LIKE를 추가한 495개 평가에서는 Recall@1 494/495(99.8%), Recall@5 495/495(100%), MRR 99.9%였다. title은 Recall@1 27/28(96.4%)였고 나머지 category는 100%였다. 동의어 LIKE는 semantic 지표에 포함하지 않았다.

실제 Kotlin 검색 backend의 10개 소형 fixture 결과는 다음과 같다.

| 경로 | R@1 | R@5 | MRR | exact | semantic query |
|---|---:|---:|---:|---:|---:|
| 이전 keyword | .800 | .800 | .800 | 1.000 | .000 |
| 통합 KEYWORD_ONLY | .800 | .800 | .800 | 1.000 | .000 |
| deterministic fake hybrid | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |

deterministic hybrid는 구조 검증 전용이며 실제 semantic 성능에 합산하지 않았다. 통합 KEYWORD_ONLY p50/p95는 0.206/0.368 ms, 초기화 0.836 ms, JVM heap peak 18.318 MiB였다.

### AVD Room FTS와 fallback

최종 clean APK를 AVD에 설치해 migration, Room FTS, update/delete 재색인, fallback `search → get → validated mock open_compose`를 실행했다. 5개 instrumentation 중 4개 통과, 물리기기 전용 semantic 1개는 assumption skip, 실패 0이다.

- 11 query 정확도: 11/11
- 100회 반복: 100/100, 순위 변동 없음
- first query 14.057 ms, p50 2.334 ms, p95 4.165 ms
- test process total PSS 111.834 MiB
- mode: `KEYWORD_ONLY`, reason: `UNSUPPORTED_EMULATOR_NATIVE_SME2`
- `com.example.hjp` crash/SIGILL: 0

시스템 logcat의 Google TTS 프로세스 SIGILL은 앱 프로세스가 아니므로 앱 오류로 집계하지 않았다. AVD 결과는 semantic 성공으로 기록하지 않았다.

### 물리 ARM64 semantic

**NOT RUN.** 연결된 장치는 AVD 하나뿐이었다. 따라서 다음은 모두 성공 또는 추정값으로 기록하지 않는다.

- 실제 EmbeddingGemma 768차원 query/document 추론
- semantic-only Recall@1/5/MRR와 query latency
- 실제 document 전체 색인 시간
- Room embedding cache의 물리기기 재시작 재사용 및 명함 수정 재색인
- semantic + FTS RRF E2E
- Android native semantic PSS/RSS peak

실행 스크립트 `scripts/run-ryeong-arm64-e2e.sh`는 syntax 검사를 통과했다. 물리 ARM64 장치에서 위 항목을 실행해야 한다.

## 6. 반복 안정성

Mac Gemma 4를 실제로 40회 추가 실행했다. 각 대표 case는 10/10 process·intent·action·slot·strict 성공, timeout/crash 0, classification/plan/workflow 변동 1종이었다.

| 실제 Gemma case | 성공 | p50 | p95 | 본문 변형 수 |
|---|---:|---:|---:|---:|
| 이름 검색 | 10/10 | 14.970 s | 18.425 s | N/A |
| 연락처 이메일 chain | 10/10 | 23.333 s | 32.418 s | 1 |
| 연락처 문자 chain | 10/10 | 23.412 s | 25.253 s | 1 |
| 상대 날짜 일정 | 10/10 | 15.819 s | 16.852 s | N/A |

Kotlin production orchestrator를 deterministic model fixture로 반복한 결과는 이메일 10/10(p50 1.147 ms, p95 7.216 ms), 문자 10/10(1.060/3.644 ms), 상대 날짜 10/10(1.282/2.424 ms)이다. FTS의 exact-name, phone, all-term, honorific, synonym-LIKE 대표 검색도 각각 10/10이고 rank order 변동은 모두 1종이다. Gemma worker는 요청마다 별도 프로세스여서 장기 세션 memory 증가 여부는 측정하지 않았고, 실제 semantic 반복은 물리기기 부재로 NOT RUN이다.

## 7. 최종 지표와 gate

| 지표 | 결과 |
|---|---:|
| intent final | 64/64 |
| action final | 60/64 |
| required slot final | 38/39 |
| structured output | 64/64 |
| workflow | 39/40 |
| tool execution | 61/64; 예상 오류 fixture 3건 |
| multi-tool | 15/15 |
| contact chain | 11/11 |
| multiturn | 40/40 |
| clarify | 10/10 |
| unsupported | 4/7 |
| date | 10/10 |
| schema | 64/64 |
| strict | 54/64 |
| body success | 15/17 |
| body quality | 8.1/10 |
| keyword 192 R@1/R@5/MRR | 1.000 / 1.000 / 1.000 |
| keyword expanded 495 R@1/R@5/MRR | .998 / 1.000 / .999 |
| semantic R@1/R@5/MRR | NOT RUN |
| Mac model latency | intent p50/p95 12.022/24.450 s |
| memory | Mac intent max RSS 2.43 GiB; JVM search heap 18.318 MiB; AVD test PSS 111.834 MiB |
| unsafe / false completion | 0 / 0 |
| wrong-person lookup / stale ID execution | 0 / 0 |

사용자가 제시한 최소 회귀 기준은 모두 충족했다. 그러나 기존 production gate의 action ≥95%, clarify·unsupported ≥95%, 기존 실패 11/11 조건 중 action 93.8%, unsupported 57.1%, 기존 실패 10/11이 미달이다. 물리 ARM64 semantic E2E도 미실행이다. 따라서 **production gate는 통과하지 않았으며 Android production 모델/runtime 연결 완료로 판정하지 않는다.**

## 8. 산출물

- staged raw: `tools/litertlm_benchmark/results/gemma4-staged-final-performance-20260801.jsonl`
- staged report/CSV/split metrics/diff: `tools/litertlm_benchmark/reports/gemma4-staged-final-performance-20260801_*`
- 40회 raw: `tools/litertlm_benchmark/results/repeat-20260801-*.jsonl`
- 반복 요약: `tools/litertlm_benchmark/reports/gemma4-staged-repeat-20260801.json`
- FTS: `tools/ryeong_search_benchmark/results-final-20260801.json`
- 멀티턴/AVD/workflow: `tools/ryeong_search_benchmark/*-final-20260801.json`
- debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 322,407,251 bytes, SHA-256 `3c4c6feafe8cde00a01d733a7c0960f71f6e497d748f9ac09d9f3e4ead026593`
- test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, 1,054,358 bytes, SHA-256 `7351137565e0c027ceac35d68d525df4242de2b3707e4eb0b8c8766d3eea0f7e`
