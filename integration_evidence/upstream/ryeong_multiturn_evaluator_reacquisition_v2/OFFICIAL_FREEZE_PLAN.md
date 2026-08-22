# 공식 평가 freeze 계획

**이번 작업에서 공식 run을 실행하지 않았다.** 아래는 실행 전에 고정해야 할 입력과 wrapper 요구사항이다.

## 1. freeze 대상 (이미 확보된 값)

| 입력 | 값 |
|---|---|
| upstream commit | `1caec3a23d0c1ee8f6a8d4a5e54160dbb2dc81bc` (branch `llm-integration-work`) |
| `scripts/eval_multiturn.py` | `26a522235fbbd73760229260e3cc4373ca6d66ce0a4d4ca9dbf612cd21d19031` |
| `scripts/hybrid_server.py` | `5fc3872ddadcc89849c28f68117bd23e1b6bad0353c0980a78ea5c3a66a424f0` |
| `scripts/eval_search.py` | `af6003fa478af80381b020112d78ba472162d5526ebc7344a4cc8520e7b6f48d` |
| `data/cards_eval1000.json` | `f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24` |
| `data/cards_eval1000_vectors.bin` | `UPSTREAM_MANIFEST.json` 참조 |
| `eval/rag_eval_dataset.jsonl` | `73eaf7a7d88362e320e32e4054e291a4eda52598893eadc37bdb98da75ce4862` (멀티턴에는 **미사용**) |
| current production source | `source_sha256_after.txt` (217개 `.kt`/`.java`) |
| seed | `SEED = 42` |
| scenario inventory | 130 시나리오 / 377턴 / 21종 — `SCENARIO_INVENTORY.json` |
| environment | `ENVIRONMENT_READINESS.json` |
| exact command | `official_run_command.txt` |

## 2. 아직 고정할 수 없는 값

* **model artifact SHA** — 체크포인트가 없다. 배치 후 반드시 해시해 기록한다.
* **dependency versions** — 브랜치에 pin이 없다. `pip freeze`로 **설치 시점에** 고정한다.
* **adapter SHA** — adapter를 만들지 않았다.

## 3. wrapper 요구사항 (원본을 고치지 말 것)

원본은 **stdout 전용**이고 **실패가 있어도 `return 0`**이며 **gate가 없다**
(`METRIC_DEFINITIONS.md` 참조). 따라서 wrapper가 필수다.

wrapper는 다음을 해야 한다.

1. stdout·stderr를 **원문 그대로** 보존
2. process exit code를 그대로 보존하되 **PASS 판정에 쓰지 않음**
3. metric을 구조화 — 라우팅/JGA/슬롯 P·R·F1/R@5, 각 **분자·분모를 그대로**
4. 실패 건수를 구조화 (`[실패 N건]` 블록)
5. `known_gap`을 별도 집계 (현재 tip 기준 **0개**)
6. `generate_only`를 별도 집계 (**6개**, dry-run에서 미실행)
7. skipped/excluded를 분리 표기
8. SHA manifest 기록
9. `RUN_STATUS.json` 작성
10. **실패가 하나라도 있으면 성공으로 판정하지 않음**

wrapper가 metric 기준이나 시나리오를 바꾸면 안 된다. 출력은 `CREATE_NEW`로 쓰고
run마다 고유 경로를 쓴다.

## 4. gate 사전 등록

원본에는 gate가 없다. 공식 gate는 **결과를 보기 전에** 현재 평가 계약과 목표를 근거로
사전 등록해야 한다. 과거 Ryeong 숫자를 보고 사후에 정하면 안 된다.

`multiturn-final-20260801.json`의 40/40은 **과거 결과 요약**이며 dataset도 evaluator도
현재 성능도 아니다. gate 근거로 인용 금지.

## 5. 두 축 분리

원본 Ryeong 평가와 현재 Android 에이전트 평가는 **다른 run, 다른 보고서**다.
수치를 같은 표에 합치지 않는다. 현재 에이전트 평가는 `ADAPTER_TRANSLATION_MATRIX.md` §4의
정책 결정이 끝난 뒤에야 시작할 수 있다.
