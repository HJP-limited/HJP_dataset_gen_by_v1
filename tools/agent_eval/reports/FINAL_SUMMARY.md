# Agent evaluation final summary

- methodology: `docs/AGENT_EVALUATION_METHODOLOGY.md`
- failure attribution: `docs/AGENT_FAILURE_ATTRIBUTION.md`
- full report: `docs/AGENT_MAX_PERFORMANCE_EVALUATION.md`
- held-out: task 64/67, strict 59/67, unsafe/false/wrong-person/stale 0
- reliability: task pass@1/pass^3/pass^5 8/8; strict 7/8
- production gate: **FAIL** (action/slot/workflow/unsupported/strict, human body review, physical ARM64 semantic)

Raw JSONL is immutable under `tools/agent_eval/results/`; derived JSON/CSV metrics and reports
use matching file stems. `blind_body_review.csv` intentionally contains empty human-rating fields.
