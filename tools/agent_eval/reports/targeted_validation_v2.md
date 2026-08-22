# Agent evaluation

- input: `tools/agent_eval/results/targeted_validation_v2.jsonl`
- single-turn cases: 5; multi-turn session-runner cases: 0

| Layer | Metric | Result |
|---|---|---:|
| Model | intent | 4/5 (80.0%) |
| Model | action | 4/5 (80.0%) |
| Model | required slot | 3/3 (100.0%) |
| Model | structured output | 4/5 (80.0%) |
| Orchestrator | first tool | 3/4 (75.0%) |
| Orchestrator | workflow | 3/4 (75.0%) |
| ToolRegistry/mock | execution task | 4/4 (100.0%) |
| End state | task success | 4/5 (80.0%) |
| Reliability | pass@1 | 4/5 (80.0%) |

95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.
본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.
