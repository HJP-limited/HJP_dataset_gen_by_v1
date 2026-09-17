# Multi-turn Agent evaluation

## Current contract

The current official contract is E-3.7:

```text
data/eval_set_v1_e37.json
SHA-256 7e028767bf95cefc7438885ac572cb3db4aba94f485b41393d7f0702f07f4961
400 scenarios / 1,918 turns
```

E-3.6 is deprecated and must not be selected as the current scoring contract. See
`docs/E36_DEPRECATED_STATUS.md` and `docs/E37_OFFICIAL_EVALUATION_CONTRACT.md`.

## Validate the contract and scorer

From the repository root:

```bash
python3 -m unittest discover -s tools/agent_eval_multiturn_v1 -p 'test_*.py' -v
```

The tests pin the E-3.6 input bytes, E-3.7 output bytes, 400/1,918 scope, duplicate-free scenario
inventory, deterministic generation, Policy-A primary expectations, and temporal-anchor scoring.

E-3.7 retains a bounded legacy representation for 15 unique-name lookup turns: the primary route
forbids `get_contact`, while `alternative_expected_calls` explicitly permits a direct Policy-A
unique-name read. `test_e37_official_contract.py` pins this exception to turn 1 and
`get_contact` only; extending it requires a new contract revision.

## Re-score an existing physical trace

Scoring never runs the Agent. It reads a completed raw replay and, for relative calendar dates, the
scenario-local `get_current_datetime` ToolResult referenced by the authoritative artifact index.

```bash
python3 tools/agent_eval_multiturn_v1/score.py \
  --repo . \
  --set tools/agent_eval_multiturn_v1/data/eval_set_v1_e37.json \
  --raw /path/to/raw_combined_400.jsonl \
  --artifact-index /path/to/final_artifact_index_400.json \
  --out /path/to/e37_score_400.json \
  --trace-out /path/to/e37_trace_400.jsonl
```

Requirements:

- Raw trace and artifact index must describe the same physical run and provenance.
- A relative datetime is scored only when that scenario's recorded `get_current_datetime` result
  establishes the runtime date. The host date is never used.
- Missing temporal provenance fails the relative field instead of guessing.
- Output paths should remain outside Git; raw device artifacts are not release source.

## Regenerate E-3.7

```bash
python3 tools/agent_eval_multiturn_v1/normalize_e37_policy_contract.py
```

The command refuses an unexpected E-3.6 input SHA. A successful regeneration must produce the
declared E-3.7 SHA above and no Git diff in the Gold or change manifest.
