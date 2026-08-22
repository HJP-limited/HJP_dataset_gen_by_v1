# 공식 run 계획 — RYEONG SEARCH MULTITURN COMPATIBILITY

## 0. 이름

결과의 공식 명칭은 **`RYEONG SEARCH MULTITURN COMPATIBILITY`**다.
"전체 에이전트 멀티턴 성능"으로 표현하지 않는다. Production Agent Multiturn V4와 수치를 합치지 않는다.

## 1. 실행 전 확인

1. `PRE_RUN_FREEZE_MANIFEST.json`의 모든 SHA 재확인. 하나라도 어긋나면 **중단**.
2. exporter를 다시 돌려 manifest가 `c5c23888…`로 byte-identical한지 확인.
3. 전체 JVM이 green인지 확인(현재 기준 88 suites / 659 tests / 0/0/0).

## 2. 아직 만들어야 할 것 — `RyeongOfficialCompatibilityRunTest`

이번 작업에서 만들지 않았다. 공식 run을 여는 시점에 추가하며, 다음만 하면 된다.

```
RyeongScenarioLoader.load()                       // 130/377/21 gate
  → cards_eval1000.json 1000장을 BusinessCardRecord 로 적재
  → RyeongCompatibilityRunner(cards, RyeongContactSearchBackend 팩토리)
  → RyeongCompatibilityScorer.score(...)
  → RyeongRunGate.evaluate(RunInputs(...frozen SHA...), score)
  → 결과를 원자적으로 기록하고 gate 판정으로 종료
```

부품은 전부 존재하고 mutation·canary로 검증됐다. 새로 필요한 것은 **1000장 fixture 적재와
결과 파일 기록**뿐이다.

## 3. 기록해야 할 결과 파일

`main result JSON` / `gate verdict JSON` / `run status JSON` / `stdout` / `stderr` / `manifest`.
result에는 run ID, timestamp, Git HEAD, evaluator·adapter·dataset SHA, upstream commit,
scenario/turn 수, environment, **model 실행 여부**, **semantic 실행 여부**,
scorable/excluded/not-run 수, routing, R@5, depth, confusion matrix, category별 수치,
`unexpected_action_tool` 수, stale ID 수, cross-scenario leakage 수, failure list, excluded list,
NOT_RUN list를 담는다. 모든 비율은 분모를 함께 적는다.

## 4. exit code 계약

`RyeongRunGate`가 결정한다 — 입력 무결성 실패, exact gate 실패, evaluator 예외, partial result,
leakage, unexpected action tool, 분모 오염, 모델 없이 생성 채점, **채점된 실패 존재** 중 하나라도
있으면 **non-zero**. upstream의 "실패해도 exit 0"은 가져오지 않았다.

## 5. gate threshold — 사전 등록 필요

성능 threshold는 **아직 정하지 않았다.** 결과를 본 뒤 정하지 않는다. 공식 실행 전에 별도 gate 파일에
사전 등록한다.

등록할 때 반드시 반영할 사실이 하나 있다. canary에서 확인된 대로 **Ryeong의 문구 상당수가 현재
production 라우터에서 검색으로 가지 않는다** — `"제갈민씨 찾아줘"`는 `OTHER`, `"제갈민씨 명함 찾아줘"`는
`CONTACT_SEARCH`다. Ryeong 질문은 대부분 전자 형태이므로 **첫 공식 run의 routing 수치는 낮게 나올
것으로 예상해야 한다.** 이는 두 계약의 차이이지 adapter 결함이 아니며, 이 예상을 근거로
production을 고치거나 frozen scenario를 고쳐 쓰면 안 된다.

## 6. 하지 말 것

* 결과를 본 뒤 production·adapter·dataset·gate를 고쳐 같은 version으로 재실행
* 공식 run을 두 번 실행
* generation metric을 actual model 없이 채점 (`RyeongRunGate`가 거부한다)
* keyword-only 결과를 semantic으로 표기
* Ryeong 수치를 Production Agent V4 수치와 합산
