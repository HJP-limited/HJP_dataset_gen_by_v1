# run_3 준비 — Ryeong production search evaluator v3

**판정: PASS — RUN_3 FROZEN, NOT EXECUTED**

evaluator, gate, input, production source를 모두 동결했다. **평가는 실행하지 않았다.**
결과 파일은 0개이고 `run_3/result`는 존재하지 않는다.

---

## 1. run_3는 새 version이다

run_2를 고치거나 재사용하지 않았다.

작업 시작 시 `integration_evidence/production_eval/run_3`는 **존재하지 않았다**(`production_eval`에는
`run_1`과 `run_2`뿐). 그래서 신규 준비를 시작했다.

run_2 adapter는 **감사 참고용으로만 읽었고** 수정하지 않았다(SHA 불변). v3에는 run_2의 결함이
하나도 들어가지 않았다.

| run_2 결함 | v3 |
|---|---|
| `"세종특별자치시 디자이너"` 정확 문자열 제외 | 없음 — **질의를 제외하는 코드 경로 자체가 없다** |
| `REFERENCE_FLAGGED_MISLABEL` | 없음 |
| `excludedMislabelled` | 없음 |
| `queries_excluded_reference_flagged_mislabel` | 없음 |
| 결과가 자신을 `production adapter run_1`이라 적음 | `run_id`는 `ryeong_production_search_run_3` |
| 기권 분모 19 | **20** |
| 기존 run 디렉터리에 결과 기록 | 보호 경로 쓰기 거부 |
| `Files.write` truncate 의미 | `CREATE_NEW`만 |

`grep -a`로 검증했다(0건). **`-a`가 중요하다**: 이 셸은 `LANG=C.UTF-8`이고 BSD grep은 binary로
판단한 파일을 조용히 건너뛴다. 첫 검증 시도는 그래서 **공허한 all-clear**를 냈다(§6 참조).

---

## 2. 동결한 것

### 2.1 입력 — run_1 원본과 byte 동일

`run_1/freeze_manifest.json`을 읽어 실제 사용 파일을 확인했다(파일명을 추측하지 않았다).

| 파일 | SHA-256 | 바이트 |
|---|---|---|
| `run_3/input/cards_eval1000.json` | `f0feaebf…e7cd24` | 405,413 |
| `run_3/input/ryeong_queries_extracted.json` | `3a288ac8…f709d5` | 52,441 |

두 파일 모두 `run_1/` 원본과 **SHA 동일**하고 원본은 수정하지 않았다.

검증 결과:

| 항목 | 값 |
|---|---|
| 카드 | **1,000** (중복 ID 0) |
| 총 질의 | **203** |
| ranking 질의 | **183** |
| abstention 질의 | **20** |
| 제외된 질의 | **0** |
| 중복 질의문 | 0 |
| category 누락 | 0 |
| 데이터셋에 없는 relevant ID | 0 |
| ranking 질의의 relevant ≥ 1 | 전부 참 |
| abstention 질의의 relevant = 0 | 전부 참 |

category 분포: 회사명 40 / 이름 40 / 전화(하이픈X) 30 / 지역+직함(문장) 40 / 개념형(easy) 14 /
개념형(hard) 19 / 기권(정답없음) 20 — `run_1` freeze manifest와 일치.

**183 + 20 = 203.**

### 2.2 evaluator

```
search-core/src/test/java/com/hjp/searchlookup/eval/
  RyeongProductionSearchEvalV3Runner.java        입력 적재·측정·출력 정책·main()
  RyeongProductionSearchEvalV3Gate.java          동결된 임계값을 읽어 판정
  EvalJson.java                                  재귀 하강 JSON
  RyeongProductionSearchEvalV3RunnerSelfTest.java  self-test
```

`EvalJson`은 정규식 JSON 리더를 대체한다. 이전 adapter의 정규식은 모든 중첩 깊이에 동시에
매칭돼 질의 파일을 단일 객체로 읽었고, 그 결함이 run_1을 한 번 날렸다.

**일반 test task가 공식 평가를 실행하지 않는다.** runner에는 test annotation이 없고 이름도
`*Test`가 아니다. self-test가 리플렉션으로 이것을 검사한다.

### 2.3 gate — 결과 이전에 고정

`run_3/gate/ryeong_search_gate_v3.json` (`GATE_RATIONALE.md` 동봉)

**절대 조건**

```
cards               == 1000
queries_total       == 203
queries_scored      == 183
abstention_queries  == 20
excluded_queries    == 0
abstention_passed   == 20
abstention_failed   == 0
stale_id_resolved   == 0
unstable_rankings   == 0
```

**비회귀 하한** (전부 정수)

```
overall_top1                              >= 154   (분모 183)
category_top1[회사명]                      >= 40
category_top1[이름]                        >= 40
category_top1[전화(하이픈X)]                >= 30
category_top1[지역+직함(문장)]              >= 39
category_recall5_complete[지역+직함(문장)]  >= 40
```

run_2 원시 카테고리 값에서 개수로 환산했다: 40+40+30+39+5+0 = **154**, 154 ÷ 183 = 0.841530 →
run_2가 보고한 전체 P@1 `0.8415`를 **정확히 재현**한다. 즉 추측이 아니다.

**gate로 삼지 않은 것**: MRR·nDCG@5(run_2가 소수 넷째 자리까지만 보존 — 반올림에 임계값을 거는
셈), 개념형 easy/hard(모델 부재 상태의 수치를 바로 굳히게 됨), latency(비결정적). 전부 보고는 한다.

비율이 아니라 개수를 쓴 이유: `0.9750`을 `>= 0.975`로 적으면 40건 나눗셈의 소수 넷째 자리가
판정을 좌우한다. **40건 중 39건**은 흔들리지 않는다.

### 2.4 production source

12개 파일의 SHA-256을 개별 기록했다 — `SearchLookupService`, `SearchFieldConstraintPlan`,
`SearchFieldVocabulary`, `SearchFieldConstraintResolver`, `SearchFieldConstraintMatcher`,
`QueryAnalyzer`, `QueryAnalysis`, `LikeFallbackKeywordRetriever`, `SemanticRetriever`,
`ReciprocalRankFusion`, `BusinessCard`, `BusinessCardRepository`.

**Git HEAD만으로는 재현되지 않는다**: 이 검색 소스들은 working tree에서 untracked다. HEAD는
분기점을 가리킬 뿐이고, 실제로 측정될 코드를 특정하는 것은 위 해시다. `build.gradle.kts`,
toolchain(JDK 21.0.11 / Gradle 9.4.1), 실행 예정 명령, red/green 증거 SHA도 함께 동결했다.

---

## 3. self-test — 19/19

실제 1,000장 평가를 돌리지 않고 evaluator만 검증했다. gate는 **손으로 쓴 synthetic outcome**으로
시험한다(측정 대상과 독립적으로 정답을 알아야 하므로).

| # | 검증 | 결과 |
|---|---|---|
| 1 | 총 질의 203 | PASS |
| 2 | ranking 분모 183 | PASS |
| 3 | abstention 분모 20 | PASS |
| 4 | 제외 0 (+ 전체 input validate) | PASS |
| 5 | hard-negative 1건이 응답 → gate FAIL | PASS |
| 6 | 20/20 기권 → gate PASS | PASS |
| 7 | stale ID 1건 → gate FAIL | PASS |
| 8 | unstable ranking 1건 → gate FAIL | PASS |
| 9 | 회사명 39/40, 이름 39/40, 전화 29/30 → gate FAIL | PASS |
| 10 | 지역+직함 38/40 → FAIL, 39/40(경계) → PASS | PASS |
| 11 | run ID가 `ryeong_production_search_run_3` | PASS |
| 12 | 기존 output 디렉터리 쓰기 거부 | PASS |
| 13 | run_1/run_2/final/archive/isolation 경로 거부 (`..` 우회 포함) | PASS |
| 14 | 중단된 실행은 공식 결과로 승격되지 않음 | PASS |
| 15 | number/boolean/array JSON 타입 보존 | PASS |
| + | runner가 일반 test 실행 대상이 아님 (리플렉션) | PASS |
| + | 결과 파일 덮어쓰기 불가 (`CREATE_NEW`) | PASS |
| + | 카드 파서가 location/address/title/tags를 보존 | PASS |
| + | overall 하한이 카테고리 하한과 독립적으로 작동 | PASS |

`./gradlew --offline :search-core:compileTestJava --rerun-tasks` → **BUILD SUCCESSFUL, exit 0**.

실행하지 않은 것: `:search-core:runRyeongSearchEvalV3`, `RyeongDatasetProductionAdapterTest`,
전체 `:search-core:test`, 전체 JVM suite, APK 빌드.

---

## 4. 출력 정책

| 규칙 | 구현 |
|---|---|
| 기존 output 경로 거부 | `Files.exists` → `IOException` |
| 보호 경로 거부 | canonical path에 `production_eval/run_1`·`run_2`·`final`·`evaluator_archive`·`evaluator_isolation`·`red`·`green` 포함 시 거부 |
| symlink·`..`·absolute 우회 거부 | 판정 전에 `getCanonicalFile()`로 해석 |
| 파일 생성 | `StandardOpenOption.CREATE_NEW`만 |
| 부분 결과 승격 방지 | `<output>.partial`에 전부 쓰고 완료 시에만 `ATOMIC_MOVE`로 이동 |
| 실패한 gate | 수치는 그대로 기록하고 verdict에 실패로 남긴다. PASS로 승격하지 않고 삭제하지도 않는다 |

---

## 5. 동결 후 무결성

freeze manifest 작성 후 **22개 파일을 다시 해시**해 manifest 값과 대조했다.

| 그룹 | 파일 | 불일치 |
|---|---|---|
| runner (4) | Runner/Gate/EvalJson/SelfTest | **0** |
| gate (2) | JSON + rationale | **0** |
| input (2) | cards + queries | **0** |
| production source (12) | 검색 코어 | **0** |
| build file (1) | `search-core/build.gradle.kts` | **0** |
| self-test (1) | | **0** |
| **합계 22** | | **0** |

`PRE_RUN_STATUS.json`:

```json
{
  "run_id": "ryeong_production_search_run_3",
  "status": "FROZEN_NOT_RUN",
  "evaluation_executed": false,
  "result_files_present": false
}
```

`result_sha256`은 기록하지 않았다 — 아직 존재하지 않는 값을 미리 적는 것은 동결이 아니다.

**동결 이후 이 파일들을 수정하지 않는다.** 문제가 발견되면 run_3를 보존하고 다음 version을 만든다.

---

## 6. 준비 중 발견해 고친 결함

`RyeongProductionSearchEvalV3Runner.java`의 중복 질의 검사에서, 구분자가 있어야 할 자리에
**NUL 바이트 1개**가 들어가 있었다.

기능상으로는 동작했지만(구분자로 쓰였다) 파일이 **binary로 분류**되어 grep과 diff가 조용히
건너뛰었다. run_2 결함 부재를 확인하려던 첫 검증이 공허한 all-clear를 낸 것이 그 때문이다.

수정: 구분자를 없애고 `(category, query)` 쌍 자체를 키로 쓴다. 이후 evaluator 소스 4개 전부
**control byte 0**, 유효 UTF-8, `file`이 "Java source, Unicode text, UTF-8"로 인식. compile과
self-test를 다시 돌려 green을 확인했다.

**동결 이전에 발견해 고쳤다.** 동결된 해시는 수정 후 파일의 것이다.

---

## 7. 무결성

| 항목 | 값 |
|---|---|
| 보호 파일 (before / after) | **59 / 59** |
| changed / added / removed | **0 / 0 / 0** |
| run_1 / run_2 변경 | 0 / 0 |
| final·errata / red / green 증거 변경 | 0 / 0 / 0 |
| evaluator archive·isolation 변경 | 0 / 0 |
| `RyeongDatasetProductionAdapterTest` 변경 | **없음** |
| `SearchFieldConstraintCharacterizationTest` 변경 | **없음** |
| production 검색 코드 변경 | **0** |
| dataset·gate 변경 | 0 / 0 |
| run_3 result 파일 | **0** |
| 평가 실행 횟수 | **0** |

`search-core/build.gradle.kts`에 JavaExec 등록 1건이 추가됐다(untracked 파일이므로 tracked diff
25건은 그대로).

---

## 8. 다음 실행 명령

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
./gradlew --offline :search-core:runRyeongSearchEvalV3 --rerun-tasks
```

기본 인자: `--input ../integration_evidence/production_eval/run_3/input`,
`--gate ../integration_evidence/production_eval/run_3/gate/ryeong_search_gate_v3.json`,
`--output ../integration_evidence/production_eval/run_3/result`.

산출 예정: `production_search_eval_v3_results.json`, `gate_verdict.json`, `RUN_STATUS.json`.
exit 0은 모든 gate 통과, 1은 하나라도 실패 — 어느 쪽이든 수치는 기록된다.

---

## 9. 이 실행이 시험할 위험

run_3는 **field constraint가 들어간 뒤 처음 돌리는 전량 평가**다. gate는 이것을 드러내라고 있다.

1. `지역+직함(문장)` 40건 — 원본 ground truth는 `(location, title)` 정확 일치이고, 새 구현은 지역을
   `location`+`address`에, 직책을 `title` 단어 단위에 적용한다. 정답 카드가 제거되지 않아야 하지만
   **그것은 주장이지 측정이 아니다.**
2. `이름`·`회사명` 80건 — 사람 이름이나 상호가 지역 어휘와 겹치면 지역 조건이 생겨 정답이 걸러질
   수 있다. 배제 가드가 있으나 1,000장 실데이터에서 검증된 적은 없다.
3. 기권 20건 — characterization은 9장 fixture에서 통과했다. 1,000장에서 20건 전부가 빈 결과를
   낼지는 이 실행이 처음 확인한다.

**이 위험 때문에 gate를 낮추지 않았다.** 실패하면 그것이 알아내려던 사실이다.

---

## 10. 이 판정이 의미하지 않는 것

`PASS — RUN_3 FROZEN, NOT EXECUTED`는 **준비가 끝났다**는 뜻일 뿐이다.

* run_3 결과가 아니다. 아직 없다.
* Ryeong 평가 PASS가 아니다. run_1은 FAIL, run_2는 `INVALID AS PASS`다.
* semantic 성능 검증이 아니다. 임베딩 모델이 없어 semantic 축은 실행되지 않을 것이고,
  개념형 카테고리는 keyword-only diagnostic으로 명시된다.
* 전체 JVM PASS, Android production 검증, v4 freeze, production ready 중 어느 것도 아니다.
