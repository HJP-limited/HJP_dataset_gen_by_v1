# Agent evaluation harness

## 실기기 전 완료 결과 (2026-08-09)

원시 결과는 `results/pre_device_completion/`에 있고, 사람이 쓴 설명은
`docs/AGENT_PRE_DEVICE_COMPLETION_REPORT.md`에 있다. runner가 만든 JSON과 서술 문서를 섞지 않는다.

| suite | 결과 | 환경 |
|---|---|---|
| known regression | strict 10/10 | fake gateway, Kotlin REACT, JVM |
| visible generalization | strict 252/252 | fake gateway, Kotlin REACT, JVM |
| property/metamorphic (seed 20260809) | 120/120 | fake gateway, Kotlin REACT, JVM |
| mutation | 14/14 검출 | 평가기 자체 검증 |
| context stress 12/20/40턴 | admission 위반 0 | JVM |
| lifecycle instrumented | 27개, 실패 0 | 모델 없는 AVD |
| desktop actual Gemma | 62/105 attempt (61 고유) | **actual Gemma + dry-run tools + Python** |

주의할 점 세 가지.

1. fake gateway 결과는 orchestration 검증이며 **Gemma 성능이 아니다.** 두 수치를 합치지 않는다.
2. `mutation_results.json`의 `detected=true`는 *평가기가 mutant를 실패시켰다*는 뜻이다.
   mutant 동작이 성공했다는 뜻이 아니다.
3. desktop Gemma는 **Kotlin REACT assembly가 아니라** production catalog를 export해 쓰는 Python
   harness이고, tool backend는 dry-run이다. Android E2E로 표현하지 않는다.

재현:

```bash
./gradlew test                                              # 209 tests
./gradlew -PhjpLifecycleTests :app:connectedLifecycleAndroidTest
tools/litertlm_benchmark/.venv/bin/python tools/agent_eval/run_desktop_gemma.py
```

`run_desktop_gemma.py`는 `results/pre_device_completion/production_tool_catalog.json`
(=`ExportProductionCatalogTest`가 만든 실제 production system instruction과 tool contract)과
실행 전에 고정된 `gemma_scenarios.json`을 읽는다.

---

`build_dataset.py` freezes 213 template-disjoint tasks into development,
validation, and sealed held-out splits (71 each). Of these, 201 are independent
single-turn tasks and 12 require the session runner. `evaluate_agent.py` evaluates model,
orchestrator, tool execution, and final state separately and computes bootstrap
confidence intervals and repeated-run reliability.

The held-out reference is read only by the final evaluator. Do not pass it to a
model prompt or use it while selecting changes.

Final artifacts are indexed by `reports/FINAL_SUMMARY.md`; the machine-readable
summary is `results/final_summary.json`. Raw JSONL files are never overwritten by
the evaluator. The blinded body worksheet intentionally has empty human-rating
columns until an independent reviewer completes it.

## 정책·스키마 parity 테스트

Kotlin(`agent-contract`, `agent-core`)과 이 디렉터리의 Python 정책은 같은 fixture를 읽는다.
`fixtures/capability_policy_cases.json`이 유일한 기준이며, 한쪽만 바꾸면 양쪽 suite가 실패한다.

```bash
# hjp_staged_schemas가 litert_lm을 import하므로 benchmark venv로 실행한다.
tools/litertlm_benchmark/.venv/bin/python -m unittest discover -s tools/agent_eval -t tools/agent_eval

# Kotlin 쪽
./gradlew :agent-core:test --tests "*PolicyFixtureParityTest"
./gradlew :agent-contract:test --tests "*StagedSchemaParityTest"
```
