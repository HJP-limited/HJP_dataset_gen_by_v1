# Agent evaluation

- input: `tools/agent_eval/results/final_legacy64.jsonl`
- single-turn cases: 64; multi-turn session-runner cases: 0

| Layer | Metric | Result |
|---|---|---:|
| Model | intent | 64/64 (100.0%) |
| Model | action | 64/64 (100.0%) |
| Model | required slot | 39/39 (100.0%) |
| Model | structured output | 64/64 (100.0%) |
| Orchestrator | first tool | 40/40 (100.0%) |
| Orchestrator | workflow | 40/40 (100.0%) |
| ToolRegistry/mock | execution task | 37/40 (92.5%) |
| End state | task success | 63/64 (98.4%) |
| Reliability | pass@1 | 63/64 (98.4%) |

95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.
본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.
