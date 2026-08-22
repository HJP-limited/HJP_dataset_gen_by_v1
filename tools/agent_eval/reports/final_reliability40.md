# Agent evaluation

- input: `tools/agent_eval/results/final_reliability40.jsonl`
- single-turn cases: 40; multi-turn session-runner cases: 0

| Layer | Metric | Result |
|---|---|---:|
| Model | intent | 35/40 (87.5%) |
| Model | action | 35/40 (87.5%) |
| Model | required slot | 25/25 (100.0%) |
| Model | structured output | 35/40 (87.5%) |
| Orchestrator | first tool | 25/25 (100.0%) |
| Orchestrator | workflow | 25/25 (100.0%) |
| ToolRegistry/mock | execution task | 25/25 (100.0%) |
| End state | task success | 40/40 (100.0%) |
| Reliability | pass@1 | 8/8 (100.0%) |

95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.
본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.
