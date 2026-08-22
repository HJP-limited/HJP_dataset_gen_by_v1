# Ryeong 멀티턴 공식 baseline v1

## 1. 전체 판정

| 실행 | 판정 |
|---|---|
| **RUN_K1** — `RYEONG_PRODUCTION_COMPATIBILITY_V1_JVM_KEYWORD_BASELINE` | **INVALID RUN** |
| **RUN_D1** — `RYEONG_PRODUCTION_COMPATIBILITY_V1_DEVICE_ACTUAL_MODEL_BASELINE` | **NOT RUN** |

RUN_K1은 **정확히 1회** 실행돼 결과 3종을 원자적으로 남겼고, 동결된 validity gate를 위반해
`INVALID RUN`으로 판정됐다. RUN_D1은 명세 §15가 정한 선행 조건(RUN_K1이 `VALID BASELINE`)이
성립하지 않아 **시작하지 않았다.**

> 이 평가는 **검색·focus·후속 질문 호환성**만 본다. compose/calendar/update를 포함한 전체
> Tool agent 성능이 아니다.

## 2. RUN_K1 validity

`INVALID RUN` — 위반 항목 **1개**.

```
unexpected action tools 1   (동결된 공통 validity gate: unexpected action Tool = 0)
```

통과한 validity 항목: scenario 130, turn 377, kind 21, known_gap 0, generate_only 6,
input SHA mismatch 0, scenario ID 중복 0, 미존재 gold card ID 0, `result.partial` 0,
unhandled exception 0, cross-scenario leakage 0, `scored(328) + excluded(49) + not_run(0) = 377`,
사유 없는 제외 0, numerator > denominator 0, failure list 길이 = failure count,
semantic 허위 기록 0, actual model 허위 기록 0, 130 scenario 전부 처리, 1,000장 fixture 적재,
공식 결과 파일 3종 원자적 생성, exit code와 run status 일치.

### 위반 1건의 정체

| 항목 | 값 |
|---|---|
| scenario | **102** |
| kind | **도구 범위 밖** (out of tool scope) |
| depth | 2 |
| expected route | `null` (upstream이 route를 주장하지 않는 턴) |
| observed act | `ACTION_CALENDAR` |
| executed tools | `get_current_datetime`, **`create_calendar_event`** |

"도구 범위 밖" 시나리오에서 production이 **캘린더 작성 화면을 열었다.** 실제 저장은 아니고
recording fake backend로 격리돼 외부 side effect는 발생하지 않았지만, 검색 전용 턴에서
action tool이 실행된 것은 동결 gate가 0으로 못박은 항목이다.

## 3. RUN_D1 validity

**NOT RUN.** 판정 자체가 없다.

기기와 모델은 준비돼 있었다. 진행을 막은 것은 명세 §15다 —
"RUN_D1은 RUN_K1이 `VALID BASELINE`이고 device canary와 freeze 검증이 통과한 경우에만 진행한다."

따라서 **APK를 빌드하지 않았고, 모델을 push하지 않았고, 앱을 설치하지 않았으며, 기기를 전혀
수정하지 않았다.** 가짜 결과 파일 대신 구조화된 blocker를 남겼다.

## 4. 실기기·모델 식별 정보

| 항목 | 값 |
|---|---|
| serial | `R5CY54CP83R` |
| 제조사/모델 | samsung **SM-S937N** |
| Android / API | **16** / **36** |
| ABI | **arm64-v8a** (단일) |
| fingerprint | `samsung/psqksx/psq:16/BP4A.251205.006/S937NKSS9CZG3_OKR9CZG3:user/release-keys` |
| `ro.kernel.qemu` | **0** — emulator 아님 |
| RAM | 11.38 GB (가용 3.74 GB) |
| 저장공간 | /data 67G free |
| 배터리 / thermal | 74%, 충전 중, 32.1℃, thermal status **0** |
| 앱 설치 | **없음** |

emulator(`emulator-5554`)도 연결돼 있었으나 §8이 배제하므로 **물리 기기는 정확히 1대**였다.
serial을 추측하지 않았다.

모델: `models/hjp-agent.litertlm`(284,426,240 B)과 `models/gemma-4-E2B-it.litertlm`(2,588,147,712 B)이
host에 있다. **어느 것도 기기에 push하지 않았고 로드하지 않았다.**
EmbeddingGemma asset 2종도 host에 있으나 **초기화하지 않았다.**

## 5. frozen input SHA

| 입력 | SHA-256 |
|---|---|
| upstream commit | `1caec3a23d0c1ee8f6a8d4a5e54160dbb2dc81bc` |
| `eval_multiturn.py` | `26a52223…d19031` |
| `cards_eval1000.json` | `f0feaebf…3e7cd24` |
| exported scenarios | `c5c23888…2d9390b` |
| seed | 42 |

셋 다 실행 전후 **불변**이다. 나머지 runner·adapter·scorer·gate SHA는
`freeze/jvm_keyword/PRE_RUN_FREEZE_MANIFEST.json`(16개 artifact)에 있다.

## 6. scorable / excluded / not-run 범위 — 실행 전 동결

| 축 | 값 |
|---|---|
| routing scorable | **328** |
| routing excluded | **49** |
| not_run | 0 |
| 합 | **377** ✅ |
| R@5 상한(gold 선언 턴) | 335 |
| JGA | **NOT_SCORABLE** |
| slot P/R/F1 | **NOT_SCORABLE** |
| generation | **NOT_RUN** (`ACTUAL_MODEL_NOT_EXECUTED`) |

제외 49건 내역: 미주장 13, `empty_result` 9, `context_answer` 9, `filtered_count` 8,
`abstain` 8, `total_count` 1, `self_reference` 1. **사유 없는 제외 0건.**
분모는 실행 전 `METRIC_DENOMINATORS.json`에 동결했고 결과를 본 뒤 바꾸지 않았다.

## 7. RUN_K1 metric (측정값, PASS/FAIL 기준 아님)

| 지표 | 값 |
|---|---|
| routing accuracy | **0 / 328 = 0.000** |
| route coverage | 328 scorable / 49 excluded |
| R@5 | **0 / 0** — 분모가 0이다 |
| JGA | NOT_SCORABLE |
| slot P/R/F1 | NOT_SCORABLE |
| depth success | 2턴 0/61, 3–5턴 0/54, 6–10턴 0/10, 1턴 0/0 |
| category | 21종 전부 numerator 0 |
| unexpected action tool | **1** |
| cross-scenario leakage | **0** |
| elapsed | 467 ms (1,000장 적재 포함) |
| retrieval | **keyword-only** (실제로는 검색 자체가 0회) |

### 가장 중요한 관측 — 검색이 한 번도 실행되지 않았다

```
search_contacts 호출 횟수 : 0
도구를 실행한 턴          : 377턴 중 2턴
observed act 분포         : OTHER 190, CLARIFICATION_REQUIRED 176,
                            QUOTED_RECALL 9, ACTION_CALENDAR 1, DATETIME_QUERY 1
confusion                 : search → CLARIFICATION_REQUIRED 153
                            search → OTHER 147
                            followup → CLARIFICATION_REQUIRED 22
                            followup → OTHER 6
```

R@5 분모가 0인 이유가 이것이다. gold를 선언한 턴이 335개지만 **production이 검색을 하지 않아
순위 자체가 생기지 않았다.** R@5는 "실패"가 아니라 **측정 불가**다.

실패 예시: `현수씨 회사가 어디야?` → `OTHER`, `주소는?` → `CLARIFICATION_REQUIRED`.

이는 §1.3에서 결과를 보기 **전에** 확인·고지된 계약 차이가 예상보다 크게 나타난 것이다.
production 라우터는 `명함` 같은 카드 지시어나 확립된 focus를 요구하는데, Ryeong 질문은
`{이름}씨 회사가 어디야?` 형태가 대부분이다. **이 결과를 근거로 production 문구 판정이나
frozen scenario를 수정하지 않았다.**

## 8. RUN_D1 metric

**없다.** actual generated turns 0, generation checklist NOT_RUN, pass^1 NOT_RUN,
no-card suppression NOT_RUN, semantic invocation 0, keyword fallback 측정 없음,
Gemma/EmbeddingGemma load time 없음, latency 분포 없음, peak memory·thermal 변화 없음,
crash/ANR/OOM 없음(실행 자체가 없었으므로).

## 9. 공통 범위 비교

**하지 않는다.** RUN_D1 측정값이 없어 비교 대상이 성립하지 않는다.
분모가 다른 값을 빼지 않았다. `CROSS_RUN_COMPARISON.md` 참조.

## 10. routing 실패 category

21종 **전부** numerator 0이다. 특정 category의 문제가 아니라 **문구 계약 전반의 불일치**다.
`search`가 `CLARIFICATION_REQUIRED`(153) 또는 `OTHER`(147)로, `followup`이
`CLARIFICATION_REQUIRED`(22) 또는 `OTHER`(6)로 갔다.

## 11. R@5 실패 query와 top-5

**해당 없음.** 검색이 0회라 top-5가 존재하지 않는다. R@5 분모 0.

## 12. generation 실패

**해당 없음.** actual model을 실행하지 않았다(`NOT_RUN`, `ACTUAL_MODEL_NOT_EXECUTED`).

## 13. semantic / fallback

RUN_K1은 JVM keyword-only 계약이었고 embedding engine은
`google/embeddinggemma-300m (keyword-fallback:LocalEmbeddingEngine)`,
`MODEL_ENGINE_MISSING`이었다. 다만 **검색이 0회라 keyword 경로조차 실행되지 않았고**,
관측된 retrieval mode 목록은 비어 있다. `semantic_axis_executed = false`로 기록했다.
keyword fallback을 semantic으로 표기하지 않았다.

## 14. latency

RUN_K1 전체 467 ms. per-turn 분포는 이번 축에서 수집하지 않았다(§16의 latency 항목은
RUN_D1용이며 RUN_D1은 미실행). upstream의 latency 정의는 데스크톱 서버 왕복이라
실기기 지연으로 인용할 수 없다.

## 15. memory / thermal / crash

RUN_D1 미실행이므로 기기 측 수치가 없다. 실행 전 기록된 기기 상태는 §4에 있다.
crash 0, ANR 0 — **실행하지 않았기 때문**이며 안정성 증거가 아니다.

## 16. 독립 검산

`result.json`의 `turn_records`만으로 runner를 보지 않고 재계산했다.

| 항목 | 재계산 | runner | 일치 |
|---|---|---|---|
| scenario / turn | 130 / 377 | 130 / 377 | ✅ |
| scored / excluded / not_run | 328 / 49 / 0 | 동일 | ✅ |
| 합 = 377 | ✅ | ✅ | ✅ |
| routing numerator/denominator | 0 / 328 | 0 / 328 | ✅ |
| R@5 numerator/denominator | 0 / 0 | 0 / 0 | ✅ |
| action violation | 1 | 1 | ✅ |
| leakage | 0 | 0 | ✅ |
| failure list 길이 = failure count | 329 = 329 | ✅ | ✅ |
| run ID (result/gate/status) | 동일 | 동일 | ✅ |

**모든 값이 일치했다.** 재계산과 runner 보고가 달랐다면 그 자체로 `INVALID RUN`이었을 것이다.
JGA·slot TP/FP/FN은 `NOT_SCORABLE`이라 재계산 대상이 아니다.

## 17. 무결성

| 항목 | 값 |
|---|---|
| 보호 evidence (freeze 시점 → 종료) | **863 → 863**, changed 0 / removed 0 / added 0 |
| production `src/main` 변경 | **0** |
| source (freeze 시점 → 종료) | 227 → 227, changed 0 |
| frozen scenario JSON | **불변** (`c5c23888…`) |
| frozen cards JSON | **불변** (`f0feaebf…`) |
| upstream clone | `git status` **clean** |
| debug APK | SHA **불변** (`e4bfa60a…`) |
| run_1 / run_2 / run_3 | 재실행 **0** |
| 기기 | **수정 0** (설치·push·uninstall·pm clear 전부 없음) |
| 금지 git 명령 | **0회** |
| 결과를 본 뒤 수정 | **없음** — result 파일은 run이 직접 쓴 그대로이고 편집하지 않았다 |
| 같은 version 재실행 | **없음** |

RUN_K1은 실패했지만 결과·로그를 삭제하지 않았고 재실행하지 않았다.

## 18. 실행하지 못한 항목

* **RUN_D1 전체** — §15 선행 조건 불성립
* device canary — 위와 같음
* APK / androidTest APK 빌드 — 위와 같음
* device 용 Ryeong runner 구현 — 위와 같음
* JGA·slot 관측성 추가 — `search-core` production 변경이 필요해 별도 version으로 남김
* generation·pass^k·no-card suppression — actual model 미실행

## 19. 현재 결과로 가능한 주장

* Ryeong 130 scenario / 377 turn을 현재 production Kotlin 경로에서 **정확히 1회** 실행했다.
* scenario·turn·kind·SHA·분모가 실행 전 동결값과 정확히 일치했다.
* cross-scenario leakage가 **0**이었다 — session 격리는 실제로 동작한다.
* **현재 production 라우터는 Ryeong의 질문 문구로 검색을 시작하지 않는다.** 377턴 중 검색 0회.
* "도구 범위 밖" 시나리오 1건에서 캘린더 작성 경로가 열렸다.
* 독립 재계산이 runner 보고와 모든 항목에서 일치했다.
* 물리 ARM64 기기가 실재하고 사용 가능한 상태였다.

## 20. 현재 결과로 불가능한 주장

* **"현재 에이전트의 멀티턴 성능이 0이다"** — 아니다. 이것은 **문구 계약 불일치**를 측정한 것이고,
  같은 production 경로가 `명함 찾아줘` 형태에서는 검색·순위·focus를 정상 수행한다(canary 13/13).
* **유효한 baseline을 얻었다** — 아니다. RUN_K1은 `INVALID RUN`이다.
* actual Gemma·actual EmbeddingGemma 성능 — 실행하지 않았다.
* 물리 ARM64 실행 성능·안정성 — 실행하지 않았다.
* R@5가 0이다 — 아니다. **측정 불가**(분모 0)다.
* 전체 Tool agent 성능 — 이 평가의 범위가 아니다.
* production readiness — 아니다.

## 21. 다음 개선 version 제안

이번 run은 보존한다. 아래는 **별도 version**으로 제안한다.

1. **v2-a: 문구 계약 결정.** Ryeong 질문이 production 검색을 타지 않는 것이 (a) production 라우터가
   좁은 것인지 (b) Ryeong 문구가 이 제품의 사용자 표현과 다른 것인지 결정한다. 이는 제품 정책이며
   **평가 결과를 근거로 조용히 고칠 사안이 아니다.**
2. **v2-b: "도구 범위 밖" 위반 조사.** scenario 102 depth 2에서 캘린더가 열린 경로를
   characterization red로 고정한다.
3. **v2-c: JGA·slot 관측성.** `SearchLookupService.retrieve()`가 **적용된** field constraint plan을
   no-op 기본 콜백으로 내보내게 하고, observer 유무로 순위가 구조적으로 동일함을 고정한 뒤
   §6의 8개 slot mutation을 수행한다.
4. **v2-d: RUN_D1.** 위가 정리되고 RUN_K1이 `VALID BASELINE`이 된 뒤에 실기기 축을 연다.

---

## 22. 질문별 답

| 질문 | 답 |
|---|---|
| JGA·slot 관측성을 추가했는가? | **NO** — 5개 우선순위를 모두 조사한 뒤 `NOT_SCORABLE` 유지 |
| 관측성 추가가 production 결과를 바꾸지 않았는가? | **N/A** — 추가하지 않았다. `src/main` 변경 0 |
| route/scorable 분모를 실행 전에 동결했는가? | **YES** — 328 / 49 / 377 |
| 물리 ARM64 기기를 사용했는가? | **NO** — 확인·기록했으나 RUN_D1을 시작하지 않았다 |
| actual Gemma를 실제로 로드했는가? | **NO** |
| actual Gemma generation을 실행했는가? | **NO** |
| EmbeddingGemma semantic path가 실제 실행됐는가? | **NO** |
| RUN_K1은 정확히 1회 실행됐는가? | **YES** |
| RUN_D1은 정확히 1회 실행됐는가? | **NO** — 0회 (§15 선행 조건 불성립) |
| frozen scenario를 실행 후 수정하지 않았는가? | **YES** — SHA 불변 |
| 결과를 본 뒤 gate를 바꾸지 않았는가? | **YES** |
| 두 run은 각각 VALID BASELINE인가? | **NO** — RUN_K1 `INVALID RUN`, RUN_D1 `NOT RUN` |
| 이 결과가 전체 Tool agent 성능을 의미하는가? | **NO** |

Ryeong 공식 v1은 검색·focus·후속 질문 중심의 compatibility baseline이다.
compose/calendar/update와 전체 production Tool agent 성능은 별도의
**Production Agent Multiturn V4** 평가에서 측정해야 한다.
