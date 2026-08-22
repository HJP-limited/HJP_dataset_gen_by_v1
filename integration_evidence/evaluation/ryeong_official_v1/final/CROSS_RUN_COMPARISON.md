# 두 실행의 비교

**비교하지 않는다.** RUN_D1이 실행되지 않았으므로 비교할 두 번째 측정값이 없다.

| | RUN_K1 | RUN_D1 |
|---|---|---|
| 상태 | 실행됨 (1회) | **NOT_RUN** |
| 판정 | **INVALID RUN** | — |
| 실행 환경 | JVM, keyword-only | — |
| actual Gemma | 미실행 | 미실행 |
| actual EmbeddingGemma | 미실행 | 미실행 |
| scenario/turn | 130 / 377 | — |
| routing | 0 / 328 | — |
| R@5 | 0 / 0 | — |

## 왜 RUN_D1을 실행하지 않았나

이번 작업 명세 §15가 정한다 — **RUN_D1은 RUN_K1이 `VALID BASELINE`일 때만 진행한다.**
RUN_K1은 동결된 validity gate의 `unexpected action Tool = 0`을 위반해 `INVALID RUN`이다.

기기(`R5CY54CP83R`)와 모델은 준비돼 있었지만 규칙이 진행을 막았고, 그래서
APK를 빌드하지 않았고, 모델을 push하지 않았고, 기기를 전혀 수정하지 않았다.

## 분모가 다른 값을 빼지 않는다

RUN_D1 수치가 없으므로 차이 계산 자체가 성립하지 않는다. 이후 RUN_D1이 실행되더라도
**공통 분모·동일 scenario·동일 scorable contract**에서만 비교해야 한다.
