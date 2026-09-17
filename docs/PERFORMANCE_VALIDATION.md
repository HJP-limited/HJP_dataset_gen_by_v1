# Performance and validation

## Current official contract

E-3.7 is the current official evaluation contract:

- Gold: `tools/agent_eval_multiturn_v1/data/eval_set_v1_e37.json`
- SHA-256: `7e028767bf95cefc7438885ac572cb3db4aba94f485b41393d7f0702f07f4961`
- Scope: 400 scenarios / 1,918 turns

The current official baseline is an offline re-score of the immutable, runtime-valid A-56 physical
trace using the A-15 scenario-local temporal-anchor scorer semantics:

- TSR: 288/400 (72.00%)
- Tool Selection: 1,589/1,918 (82.85%)
- Argument Accuracy: 1,644/1,708 (96.25%)
- Argument Field Accuracy: 3,418/3,482 (98.16%)
- E2E Tool-call: 1,644/2,154 (76.32%)

This score is not a fresh physical run after A-63. It must not be presented as evidence for changes
made after the A-56 trace. E-3.6 is deprecated/non-reproducible as an official contract.

## Historical results

The A-15 E-3.2 full evaluation reported TSR 167/400 (41.75%), Tool Selection 1,575/1,918
(82.12%), Argument Accuracy 1,470/1,565 (93.93%), Argument Field Accuracy 3,107/3,202
(97.03%), and E2E Tool-call 1,470/2,174 (67.62%). These figures remain historical and are not the
current contract baseline. A-21 was focused physical regression evidence, not a full-400 rerun.

Physical evaluation used LiteRT-LM 0.16.1 with Gemma 4 E2B on the declared target-device GPU
configuration. The current production `AppContainer` itself selects `CPU_ONLY`; do not infer the
production backend from the evaluation runner. Model decisions remain probabilistic, and
calendar/compose actions open Android surfaces that require the user to save/send.

See `tools/agent_eval_multiturn_v1/README.md` for reproducible contract and scoring commands.
