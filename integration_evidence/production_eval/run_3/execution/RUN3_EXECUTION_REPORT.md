# run_3 실행 — Ryeong production search evaluation v3

**판정: PASS — RUN_3 EXECUTED, ALL FROZEN GATES PASSED**

동결된 evaluator를 **정확히 한 번** 실행했다. 실행 후 runner·gate·dataset·production source를
수정하지 않았고, 재실행하지 않았다.

> 이 PASS는 **production 검색 경로 diagnostic이 사전 등록된 바를 넘었다**는 뜻이다. Ryeong 원본
> 평가 재현도, semantic 검증도, 전체 JVM PASS도, Android 검증도, v4 freeze 준비도, 에이전트
> production-ready도 아니다.

---

## 1. 실행 전 확인

| 항목 | 값 |
|---|---|
| `run_3/result` | 없음 |
| `run_3/result.partial` | 없음 |
| 기존 evaluator 실행 흔적 | 0 |
| frozen hash preflight | **26개 검사, 불일치 0, 누락 0, 타입 이상 0** |
| preflight 판정 | **FREEZE INTACT** |

preflight 대상: input 2, gate 2, runner 3 + self-test 1, `build.gradle.kts` 1,
production search source 12, 사전 evidence 4.

---

## 2. 실행

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
./gradlew --offline :search-core:runRyeongSearchEvalV3 --rerun-tasks \
  > .../execution/stdout.log 2> .../execution/stderr.log
```

| 항목 | 값 |
|---|---|
| 시작 | 2026-08-18T22:34:19+0900 |
| 종료 | 2026-08-18T22:34:21+0900 |
| 소요 | 2초 |
| **invocation count** | **before 0 → after 1** |
| **process exit code** | **0** |
| evaluation 본체 진입 | 예 (`> Task :search-core:runRyeongSearchEvalV3` 이후 run_id·output·gate_passed 출력) |
| stderr | 0 바이트 |

---

## 3. 공식 결과 3개

| 파일 | SHA-256 |
|---|---|
| `production_search_eval_v3_results.json` | `a7e15cfb6b524412…ab2dac` |
| `gate_verdict.json` | `04c031c74f715aa6…fc6d11` |
| `RUN_STATUS.json` | `ccaa6f3f6cdce3fb…9ed6e2` |

`result.partial`은 남지 않았고, 임시 파일이 공식 결과로 남지도 않았다. JSON parse 성공,
number/boolean/array 타입 보존, run ID 3개 파일 모두 `ryeong_production_search_run_3`,
exit code와 gate verdict 일치, `RUN_STATUS`와 verdict 일치.

gate SHA는 freeze manifest·결과 파일·재계산 **세 곳이 모두 `c798cf5e…a9c2a2`로 일치**한다.

---

## 4. 수치

| 항목 | 값 |
|---|---|
| cards | **1,000** |
| queries total | **203** |
| queries scored (ranking) | **183** |
| abstention queries | **20** |
| excluded queries | **0** |

### 전체 (분모 183)

| 지표 | 값 |
|---|---|
| top-1 개수 | **162 / 183** |
| P@1 | **0.885246** |
| P@5 | 0.222951 |
| R@5 | **0.826251** |
| MRR | **0.897817** |
| nDCG@5 | **0.875479** |

### 카테고리별

| 카테고리 | n | top-1 | R@5 완전 | P@1 | R@5 | MRR | nDCG@5 |
|---|---|---|---|---|---|---|---|
| 회사명 | 40 | **40** | 40 | 1.000000 | 1.000000 | 1.000000 | 1.000000 |
| 이름 | 40 | **40** | 40 | 1.000000 | 1.000000 | 1.000000 | 1.000000 |
| 전화(하이픈X) | 30 | **30** | 30 | 1.000000 | 1.000000 | 1.000000 | 1.000000 |
| 지역+직함(문장) | 40 | **40** | 40 | 1.000000 | 1.000000 | 1.000000 | 1.000000 |
| 개념형(easy) | 14 | 7 | 0 | 0.500000 | 0.058160 | 0.577381 | 0.452797 |
| 개념형(hard) | 19 | 5 | 0 | 0.263158 | 0.020506 | 0.327216 | 0.203865 |

### 기권 20건

| 항목 | 값 |
|---|---|
| 모집단 | **20** (제외 0) |
| 빈 결과 반환 | **20** |
| 결과를 반환한 실패 | **0** |
| accuracy | **1.000000** |
| 실패 query 목록 | **비어 있음** |

run_1이 실패했던 `세종특별자치시 디자이너`를 포함해 20건 전부가 기권했다.

### 안전성·운영

| 항목 | 값 |
|---|---|
| stale ID 해소 | **0** |
| 순위 불안정 | **0** (불안정 query 목록 비어 있음) |
| latency p50 / p95 / max | **1ms / 2ms / 50ms** |
| semantic axis 실행 | **false** |
| keyword fallback 비율 | **1.000000** |

---

## 5. 동결 gate 판정 — 15개 전부 통과

### Exact

| 검사 | 요구 | 실제 | 결과 |
|---|---|---|---|
| cards | == 1000 | 1000 | ✅ |
| queries_total | == 203 | 203 | ✅ |
| queries_scored | == 183 | 183 | ✅ |
| abstention_queries | == 20 | 20 | ✅ |
| excluded_queries | == 0 | 0 | ✅ |
| abstention_passed | == 20 | **20** | ✅ |
| abstention_failed | == 0 | 0 | ✅ |
| stale_id_resolved | == 0 | 0 | ✅ |
| unstable_rankings | == 0 | 0 | ✅ |

### Minimum count

| 검사 | 요구 | 실제 | 여유 |
|---|---|---|---|
| overall_top1 | ≥ 154 | **162** | +8 |
| 회사명 top1 | ≥ 40 | 40 | 0 |
| 이름 top1 | ≥ 40 | 40 | 0 |
| 전화(하이픈X) top1 | ≥ 30 | 30 | 0 |
| 지역+직함(문장) top1 | ≥ 39 | **40** | +1 |
| 지역+직함(문장) R@5 완전 | ≥ 40 | 40 | 0 |

**실패한 gate 0개.** 표시된 소수로 재계산하지 않고 동결된 count gate를 그대로 적용했다.

---

## 6. 독립 검산

`gate_verdict.json`을 믿지 않고 결과 파일과 동결 입력에서 직접 계산했다.

| 검산 | 결과 |
|---|---|
| 183 + 20 = 203 | ✅ |
| 카테고리 n 합 = 183 | ✅ (동결 입력의 카테고리별 ranking 건수와도 일치) |
| 카테고리 top-1 합 = 전체 top-1 162 | ✅ |
| 전체 분모 = 183 | ✅ |
| P@1 = 162/183 = 0.885246 | ✅ |
| 카테고리별 top-1 개수 ↔ 보고된 P@1 | ✅ 6개 전부 일치 |
| 기권 passed + failed = 20 | ✅ |
| 동결 입력의 hard-negative 개수 = 20 | ✅ |
| 기권 accuracy = 20/20 | ✅ |
| 실패 목록 길이 = failed 수 | ✅ (둘 다 0) |
| 제외 = 0, runner에 제외 기구 없음 | ✅ |
| stale = 0, unstable = 0, 목록 길이 일치 | ✅ |
| keyword fallback 1.0 ↔ semantic 미실행 | ✅ |
| **동결 gate 독립 적용** | **PASS, 실패 0 — runner 판정과 일치** |

### NOT INDEPENDENTLY VERIFIABLE

runner를 수정해 상세 결과를 추가하지 않았으므로 다음은 이 artifact만으로 확인할 수 없다.
추측하지 않고 그대로 표시한다.

* hard-negative 20건 **각각이** 빈 목록을 반환했는지 (runner는 실패만 기록하며 failed=0이라
  건별 기록이 없다. 확인하려면 재실행이 필요하고, 재실행은 금지다)
* 각 hard-negative가 반환한 card ID
* ranking 실패 query가 무엇이고 그 top-5가 무엇인지
* 개별 query의 relevant ID 대 실제 top-5
* latency 수치 (벽시계, artifact에서 재현 불가)

---

## 7. run_2와의 비교 (count 기준)

> run_2의 랭킹 수치는 `DIAGNOSTIC ONLY`이고 기권 주장은 `INVALID AS PASS`다. 아래는 **run_2의
> 랭킹 diagnostic과의 비교**이며 run_2 전체 PASS와의 비교가 아니다 — 그런 것은 없었다.

| 카테고리 | n | run_2 top-1 | run_3 top-1 | 변화 |
|---|---|---|---|---|
| 회사명 | 40 | 40 | 40 | 0 |
| 이름 | 40 | 40 | 40 | 0 |
| 전화(하이픈X) | 30 | 30 | 30 | 0 |
| **지역+직함(문장)** | 40 | 39 | **40** | **+1** |
| 개념형(easy) | 14 | 5 | **7** | +2 |
| 개념형(hard) | 19 | 0 | **5** | +5 |
| **전체** | 183 | **154** | **162** | **+8** |

| 지표 | run_2 | run_3 |
|---|---|---|
| 전체 P@1 | 0.8415 | **0.885246** |
| 전체 R@5 | 0.8246 | 0.826251 |
| MRR | 0.8617 | **0.897817** |
| nDCG@5 | 0.8534 | **0.875479** |
| 지역+직함 R@5 완전 | 40/40 | 40/40 |
| latency p50/p95/max | 1/1/22 ms | 1/2/50 ms |
| keyword fallback | 1.0 | 1.0 |
| semantic 실행 | false | false |

**기권**

| | 모집단 | 통과 | 실패 | 제외 | 판정 |
|---|---|---|---|---|---|
| run_1 | 20 | 19 | 1 | 0 | FAIL (`세종특별자치시 디자이너`) |
| run_2 | 19 | 19 | 0 | **1** | INVALID AS PASS — 실패한 질의를 요구에서 제거 |
| **run_3** | **20** | **20** | **0** | **0** | 전량, 제외 기구 없음 |

**field constraint가 바꾼 카테고리**: 지역+직함(문장), 개념형(easy), 개념형(hard).
식별자형 3개(회사명·이름·전화)는 **변화 없음** — 사람 이름이나 상호가 지역으로 오인될 위험이
있었으나 1,000장에서 실제로 발생하지 않았다.

개념형 두 카테고리의 상승은 **양쪽 run 모두 임베딩 모델이 없는 상태**에서 나온 것이므로
keyword-only diagnostic의 차이일 뿐이며 semantic 검색에 대해 아무것도 말하지 않는다.

---

## 8. semantic 상태

```
semantic_axis_executed = false
keyword_fallback_ratio = 1.000000
```

실제 EmbeddingGemma는 실행되지 않았다. 개념형 easy/hard 결과는 **semantic 미실행 상태의 keyword
diagnostic**이다. 이 실행을 semantic 검색 성능, EmbeddingGemma 평가 완료, hybrid production 성능
검증, 실제 의미 검색 검증 중 어느 것으로도 기술하지 않는다.

---

## 9. 무결성

| 항목 | 값 |
|---|---|
| 보호·동결 SHA map (before / after) | **120 / 120** |
| changed / added / removed | **0 / 0 / 0** |
| 실행 후 frozen 22개 재검증 | **불일치 0** |
| run_1 / run_2 변경 | 0 / 0 |
| final·errata / red / green 변경 | 0 / 0 / 0 |
| evaluator archive / isolation 변경 | 0 / 0 |
| run_3 preparation / input / gate 변경 | 0 / 0 / 0 |
| `PRE_RUN_FREEZE_MANIFEST.json` | **불변** |
| `PRE_RUN_STATUS.json` | **불변** (실행 전 상태를 증명하는 기록이므로 갱신하지 않았다) |
| production search source 12개 변경 | **0** |
| test source 변경 | 0 |
| `build.gradle.kts` 변경 | 0 |

신규 생성은 허용된 두 경로뿐이다: `run_3/result/**`(3), `run_3/execution/**`(11), 그리고 Gradle
build 출력.

---

## 10. 이 결과가 의미하지 않는 것

* **Ryeong 원본 평가의 재현이 아니다.** 참조 evaluator는 `sentence_transformers`와
  `embeddinggemma-300m` 부재로 한 번도 실행되지 않았고, 참조가 발표한 수치와 비교할 수 없다.
* **semantic 검색 검증이 아니다.** semantic 축은 실행되지 않았다.
* **전체 JVM PASS가 아니다.** 이 작업에서 전체 suite를 돌리지 않았다.
* **Android production 검증이 아니다.** Room/FTS 실경로와 기기 실행은 하지 않았다.
* **v4 freeze 준비 완료가 아니고, 에이전트 production-ready도 아니다.** 검색 한 축의 diagnostic이다.
* **멀티턴 평가는 여전히 NOT RUN이다.**

---

## 11. 재실행 금지

이 run_3는 봉인됐다. 결과를 본 뒤 runner·gate·dataset·production을 수정하지 않았고, 같은 run_3를
다시 실행하지 않았다. 향후 변경이 필요하면 run_3를 보존하고 **다음 evaluator version**을 만들어야
한다.
