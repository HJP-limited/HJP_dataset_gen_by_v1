# Agent evaluation

- input: `tools/agent_eval/results/ablation_calendar_title_legacy5.jsonl`
- cases: 5

| Layer | Metric | Result |
|---|---|---:|
| Model | intent | 5/5 (100.0%) |
| Model | action | 5/5 (100.0%) |
| Model | required slot | 5/5 (100.0%) |
| Model | structured output | 5/5 (100.0%) |
| Orchestrator | first tool | 5/5 (100.0%) |
| Orchestrator | workflow | 5/5 (100.0%) |
| ToolRegistry/mock | execution task | 5/5 (100.0%) |
| End state | task success | 5/5 (100.0%) |
| Reliability | pass@1 | 5/5 (100.0%) |

95% CI는 JSON의 각 binary metric에 bootstrap percentile 방식으로 저장했다.
본문 human mean은 미입력 case를 0점으로 포함하며 LLM judge를 사용하지 않는다.
