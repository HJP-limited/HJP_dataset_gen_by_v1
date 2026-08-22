# Agent evaluation

- input: `tools/agent_eval/results/baseline_development.jsonl`
- cases: 70

| Layer | Metric | Result |
|---|---|---:|
| Model | intent | 67/70 (95.7%) |
| Model | action | 64/70 (91.4%) |
| Model | required slot | 38/46 (82.6%) |
| Model | structured output | 69/70 (98.6%) |
| Orchestrator | first tool | 43/48 (89.6%) |
| Orchestrator | workflow | 38/48 (79.2%) |
| ToolRegistry/mock | execution task | 45/48 (93.8%) |
| End state | task success | 56/70 (80.0%) |
| Reliability | pass@1 | 56/70 (80.0%) |

95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.
본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.
