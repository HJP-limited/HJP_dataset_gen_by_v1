# Agent evaluation

- input: `tools/agent_eval/results/ablation_capability_legacy3.jsonl`
- cases: 3

| Layer | Metric | Result |
|---|---|---:|
| Model | intent | 3/3 (100.0%) |
| Model | action | 3/3 (100.0%) |
| Model | required slot | N/A |
| Model | structured output | 3/3 (100.0%) |
| Orchestrator | first tool | N/A |
| Orchestrator | workflow | N/A |
| ToolRegistry/mock | execution task | N/A |
| End state | task success | 3/3 (100.0%) |
| Reliability | pass@1 | 3/3 (100.0%) |

95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.
본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.
