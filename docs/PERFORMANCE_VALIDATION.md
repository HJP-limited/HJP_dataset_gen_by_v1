# Performance and validation

Official A-15 E-3.2 full evaluation (400 scenarios / 1,918 turns): TSR 167/400 (41.75%), Tool Selection 1,575/1,918 (82.12%), Argument Accuracy 1,470/1,565 (93.93%), Argument Field Accuracy 3,107/3,202 (97.03%), E2E Tool-call 1,470/2,174 (67.62%).

A-21 is focused physical regression evidence only and is not included in the full-400 figures. The baseline used LiteRT-LM 0.16.1 with Gemma 4 E2B on the target Android GPU path. Model decisions remain probabilistic; calendar/compose actions open surfaces and require user save/send.
