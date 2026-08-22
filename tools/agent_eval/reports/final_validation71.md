# Agent evaluation

- input: `tools/agent_eval/results/final_validation71.jsonl`
- single-turn cases: 67; multi-turn session-runner cases: 4

| Layer | Metric | Result |
|---|---|---:|
| Model | intent | 63/67 (94.0%) |
| Model | action | 64/67 (95.5%) |
| Model | required slot | 39/42 (92.9%) |
| Model | structured output | 65/67 (97.0%) |
| Orchestrator | first tool | 43/44 (97.7%) |
| Orchestrator | workflow | 43/44 (97.7%) |
| ToolRegistry/mock | execution task | 41/44 (93.2%) |
| End state | task success | 63/67 (94.0%) |
| Reliability | pass@1 | 63/67 (94.0%) |

95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.
본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.
