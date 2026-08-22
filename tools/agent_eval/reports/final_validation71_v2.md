# Agent evaluation

- input: `tools/agent_eval/results/final_validation71_v2.jsonl`
- single-turn cases: 67; multi-turn session-runner cases: 4

| Layer | Metric | Result |
|---|---|---:|
| Model | intent | 64/67 (95.5%) |
| Model | action | 65/67 (97.0%) |
| Model | required slot | 42/42 (100.0%) |
| Model | structured output | 65/67 (97.0%) |
| Orchestrator | first tool | 42/44 (95.5%) |
| Orchestrator | workflow | 42/44 (95.5%) |
| ToolRegistry/mock | execution task | 41/44 (93.2%) |
| End state | task success | 64/67 (95.5%) |
| Reliability | pass@1 | 64/67 (95.5%) |

95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.
본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.
