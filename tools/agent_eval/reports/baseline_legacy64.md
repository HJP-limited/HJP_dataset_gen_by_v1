# Agent evaluation

- input: `tools/litertlm_benchmark/results/gemma4-staged-final-performance-20260801.jsonl`
- cases: 64

| Layer | Metric | Result |
|---|---|---:|
| Model | intent | 64/64 (100.0%) |
| Model | action | 60/64 (93.8%) |
| Model | required slot | 63/64 (98.4%) |
| Model | structured output | 64/64 (100.0%) |
| Orchestrator | first tool | 39/40 (97.5%) |
| Orchestrator | workflow | 39/40 (97.5%) |
| ToolRegistry/mock | execution | 37/40 (92.5%) |
| End state | task success | 54/64 (84.4%) |
| Reliability | pass@1 | 54/64 (84.4%) |

95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.
본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.
