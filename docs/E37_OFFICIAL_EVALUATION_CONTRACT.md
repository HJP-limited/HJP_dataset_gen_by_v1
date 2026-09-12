# E-3.7 Official Evaluation Contract

E-3.7 is the current official evaluation contract. It is generated from the reproducible E-3.6 starting asset after an
independent Policy-A audit. No scenario or turn expectation required a
runtime-independent correction, so all scenario payloads are preserved.

- Gold: `tools/agent_eval_multiturn_v1/data/eval_set_v1_e37.json`
- Source E-3.6 SHA-256: `39c34fe152c9394fbf9fec7c4de89b3f4a2561ce5e1226356ae4938e90538cbe`
- E-3.7 SHA-256: `7e028767bf95cefc7438885ac572cb3db4aba94f485b41393d7f0702f07f4961`
- Scope: 400 scenarios, 1,918 turns
- Scenario/turn semantic changes: 0
- Policy-A static contract errors: 0

The ambiguity rule remains: two or more candidates without explicit selection
stay unresolved and require clarification; no implicit target promotion is
permitted.

The first official E-3.7 baseline re-score uses the immutable A-56 raw trace
and is recorded separately in the evaluation output bundle:

- TSR: 288/400 (72.00%)
- Tool Selection: 1,589/1,918 (82.85%)
- Argument Accuracy: 1,644/1,708 (96.25%)
- Argument Field Accuracy: 3,418/3,482 (98.16%)
- E2E Tool-call: 1,644/2,154 (76.32%)

This is a re-score of an existing physical trace, not a new Agent run.
