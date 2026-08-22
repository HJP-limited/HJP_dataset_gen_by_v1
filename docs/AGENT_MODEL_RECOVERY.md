# Android agent model recovery record

Date: 2026-07-24  
Decision: **no production model selected; Android integration is blocked by the model acceptance gate**

## 1. Scope and source of truth

The requested `docs/litertlm_tool_calling_model_research.md` was not present in this checkout. The existing root document `LITERT_LM_GENERATIVE_TOOL_USE_RESEARCH.md`, the benchmark under `tools/litertlm_benchmark/`, and the current Android/Kotlin source were analyzed instead.

The benchmark preset uses the current application model names and core schemas:

- `search_contacts`, `get_contact`, `update_business_card`: `tool-contact/src/main/kotlin/com/hjp/tool/contact/ContactToolContracts.kt`
- `create_calendar_event`, `open_compose`: `tool-android-intents/src/main/kotlin/com/hjp/tool/android/AndroidIntentToolContracts.kt`
- `get_current_datetime`: `tool-datetime/src/main/kotlin/com/hjp/tool/datetime/DateTimeToolContracts.kt`
- `ToolContract`/registry transport: `tool-contract/`, `agent-contract/src/main/kotlin/com/hjp/agent/contract/AgentModel.kt`
- model call loop: `agent-core/src/main/kotlin/com/hjp/agent/core/AgentKernel.kt`
- LiteRT-LM `OpenApiTool` conversion, `Message.toolCalls`, and `ToolResponse`: `llm-litert/src/main/kotlin/com/hjp/agent/litert/LiteRtAgentModelGateway.kt`
- model selection: `app/src/main/java/com/example/hjp/AppContainer.kt`

No Android tool schema or Android source was changed during this recovery attempt.

## 2. Current artifact diagnosis

Artifact:

| Property | Value |
|---|---|
| Path | `models/hjp-agent.litertlm` |
| Size | 284,426,240 bytes |
| SHA-256 | `510c8257d1f9d12b5be630b5d6a593732e10896e9ee0b3f3d20b2eebff8e1b13` |
| Android runtime | `litertlm-android 0.13.1` |
| Mac comparison runtimes | `litert-lm 0.13.1`, `litert-lm 0.14.0` |
| Backend | CPU |

The following command was run with both CLI versions:

```bash
litert-lm-peek --litertlm_file models/hjp-agent.litertlm
```

Relevant metadata was identical:

```text
LiteRT-LM Version: 1.5.0
max_num_tokens: 1024
llm_model_type { function_gemma {} }
tokenizer: SP_Tokenizer
model_type: tf_lite_prefill_decode
start token id: 2
stop tokens: <end_of_turn>, <start_function_response>
```

The dumped SentencePiece tokenizer contains FunctionGemma's function declaration, function call, function response, escape, and turn special tokens. Verbose execution also selected the FunctionGemma Jinja template and serialized tools with `<start_function_declaration>...<end_function_declaration>` and calls with `<start_function_call>call:name{...}<end_function_call>`. This agrees with the [official FunctionGemma model card and chat template](https://huggingface.co/google/functiongemma-270m-it).

### Failure separation

| Hypothesis | Result | Evidence |
|---|---|---|
| Wrong model identification | Rejected | Package metadata explicitly says `function_gemma`. |
| Wrong chat/tool template | Rejected for the minimal case | The embedded FunctionGemma template was selected. With only `get_current_datetime`, the artifact emitted a real native call with empty arguments under both a minimal and FunctionGemma-style system prompt. |
| Runtime 0.13.1 vs 0.14.0 mismatch | Rejected as the cause of the observed corruption | Peek metadata was identical. The full-preset 2,048-token run produced the same corrupt behavior and 0/3 native calls in both versions. A no-tool Korean prompt produced normal `안녕하세요.` in both versions. |
| Full tool input exceeds artifact context | Confirmed | The earlier concise instruction measured approximately 1,371–1,388 tokens. The final policy-v2 smoke inputs were rejected at 1,534, 1,543, and 1,553 tokens, while package metadata fixes the model at 1,024 tokens. The default run fails before useful generation. |
| `--max-num-tokens 2048` truly expands context | Rejected | Current CLI help defines it as “Maximum number of tokens for the KV cache.” It changes engine cache capacity; it does not re-export or retrain the model with a larger supported context. Forcing 2,048 allowed the process to run but produced abnormal mixed tokens. |

The exact failure is therefore **catalog-plus-prompt context overflow followed by an invalid KV-cache override beyond the artifact's exported 1,024-token configuration**. It is not fixed by changing parsers, regular expressions, or switching between tested runtime versions.

There is also a capability mismatch: Google describes FunctionGemma as a small model intended to be fine-tuned for a specific function-calling task and not as a direct dialogue model. The current artifact is not adequate for rich Korean body generation plus the complete application catalog. See the [FunctionGemma model card](https://huggingface.co/google/functiongemma-270m-it) and [LiteRT-LM runtime overview](https://developers.google.com/edge/litert-lm/overview).

Production decision: **exclude `models/hjp-agent.litertlm`.**

Raw/runtime comparison:

- `tools/litertlm_benchmark/results/current-hjp-agent_cpu_smoke_20260724T115915Z.jsonl`
- `tools/litertlm_benchmark/reports/current-hjp-agent_cpu_smoke_20260724T115915Z_report.md`
- `tools/litertlm_benchmark/results/current-hjp-agent_runtime-0.13.1_cpu_smoke.jsonl`
- `tools/litertlm_benchmark/reports/current-hjp-agent_runtime-0.13.1_cpu_smoke_report.md`
- `tools/litertlm_benchmark/results/current-hjp-agent_cpu_smoke_20260724T103653Z.jsonl`
- `tools/litertlm_benchmark/reports/current-hjp-agent_cpu_smoke_20260724T103653Z_report.md`

## 3. Official candidate tests on Mac

Only official LiteRT community artifacts were downloaded, one model at a time. All recorded runs used `litert-lm 0.14.0`, CPU, temperature `0.2`, top-k `20`, top-p `0.95`, seed `7`, and the real application schema represented by the dry-run preset. Model repositories: [Qwen3-0.6B](https://huggingface.co/litert-community/Qwen3-0.6B), [Qwen2.5-1.5B-Instruct](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct), and [Gemma 4 E2B LiteRT-LM](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm). Gemma 4's native function-call format is also documented in [Google's Gemma 4 function-calling guide](https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4).

| Candidate | File / SHA-256 | Gate result |
|---|---|---|
| Qwen3-0.6B mixed INT4 | 497,664,000 bytes; `b1baab462f6be49d70eada79d715c2c52cd9ece0cad00bddf6a2c097d23498e9` | Failed smoke. Policy-v2: native/tool 2/3, required arguments 1/2, body keyword 0/3. The explicit email body became the recipient address and the contextual email produced no call. Full 32 not run. |
| Qwen2.5-1.5B-Instruct Q8 EKV4096 | 1,597,931,520 bytes; `faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9` | Initial smoke was sufficient to proceed, but full 32 failed. Policy-v2 full run: native 31/32, tool selection 12/21 (57.1%), required arguments 9/20 (45.0%), schema 25/31 (80.6%), no-tool 1/11 (9.1%), multi-tool 0/7, false-completion expressions 2. It bypassed contact lookup chains and over-called tools. |
| Gemma 4 E2B | 2,588,147,712 bytes; `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` | Policy-v2 failed because the simple email omitted `body`. A schema-preserving policy-v3 example then passed smoke 3/3, so full 32 was run. Full result failed: native 23/32 (71.9%), tool selection 15/21 (71.4%), required arguments 14/20 (70.0%), no-tool 8/11 (72.7%), multi-tool 2/7 (28.6%). Body keyword coverage was 26/26, but it skipped contact chains, attempted unsupported deletion, and opened compose for an invalid email. |

The policy-v2 retry only strengthened the system instruction (non-empty model-generated body, contact lookup order, no-tool and safe-failure rules). Policy-v3 added one explicit `open_compose` argument example because `body` is optional in the real schema. Neither retry altered tool schemas, synthesized calls, or relaxed evaluation.

Primary preserved outputs:

- Qwen3: `tools/litertlm_benchmark/results/qwen3-0.6b-mixed-int4_runtime-0.14.0_cpu_smoke_policy-v2.jsonl`
- Qwen3 report: `tools/litertlm_benchmark/reports/qwen3-0.6b-mixed-int4_runtime-0.14.0_cpu_smoke_policy-v2_report.md`
- Qwen2.5: `tools/litertlm_benchmark/results/qwen2.5-1.5b-instruct-q8-ekv4096_runtime-0.14.0_cpu_full32_policy-v2.jsonl`
- Qwen2.5 report: `tools/litertlm_benchmark/reports/qwen2.5-1.5b-instruct-q8-ekv4096_runtime-0.14.0_cpu_full32_policy-v2_report.md`
- Gemma 4 smoke: `tools/litertlm_benchmark/results/gemma-4-e2b-it_runtime-0.14.0_cpu_smoke_policy-v3.jsonl`
- Gemma 4 full: `tools/litertlm_benchmark/results/gemma-4-e2b-it_runtime-0.14.0_cpu_full32_policy-v3.jsonl`
- Gemma 4 full report: `tools/litertlm_benchmark/reports/gemma-4-e2b-it_runtime-0.14.0_cpu_full32_policy-v3_report.md`

## 4. Selection and Android integration decision

No candidate satisfies all mandatory smoke criteria. Consequently:

- no default/production model was selected;
- no candidate was copied over `models/hjp-agent.litertlm`;
- no Android gateway, Gradle configuration, system prompt, tool schema, or deterministic router behavior was changed;
- no AVD Mac bridge was implemented;
- required Android and AVD Logcat fields were not added;
- Gemma 4 passed **21/32 individual Mac cases** under a strict per-case intersection (process, exact tool sequence/no-tool decision, schema, expected arguments, body requirements, no unknown tool, and no false completion) without a deterministic router. This is not a model-level pass because 11 cases failed and multi-tool completion was only 2/7;
- the count of Android/AVD end-to-end tests succeeding without the deterministic router is **0**, because the model-level gate blocked integration.

This is intentional enforcement of the requested gate: Android integration may begin only after a model produces correct native calls, schema-valid arguments, non-empty natural Korean email/SMS content, preserves recipient/intent, and generates no unknown tool in smoke tests.

The current app still selects `LocalToolRoutingModelGateway` on an emulator and `LiteRtAgentModelGateway` on a real device in `AppContainer.kt`. The existing on-device gateway already preserves `OpenApiTool → Message.toolCalls → Android tool execution → ToolResponse → continuation`, but its current model is excluded above. The emulator path is therefore still deterministic and must not be presented as a model-quality result.

## 5. Remaining blocker and next admissible step

The blocker is a **qualified official `.litertlm` artifact**, not the parser or Android tool loop. A next candidate must first pass the same three smoke tests with every required `open_compose` argument, then the full 32 cases with multi-tool and no-tool behavior. Android 0.13.1 compatibility must be verified separately after that Mac gate; a Mac 0.14.0 success alone is not proof of Android compatibility.

Only after such a pass should work proceed in this order:

1. verify the candidate with Android LiteRT-LM 0.13.1 or make an explicit, tested runtime upgrade;
2. add the requested structured model/tool/result logs;
3. make on-device inference primary and deterministic routing an explicit failure-only fallback;
4. implement a development-only AVD-to-Mac bridge whose Mac side performs inference only and whose Android `ToolRegistry` executes every call;
5. run the required real-device and AVD integration tests without enabling deterministic selection.

There is currently no valid bridge or real-device run command to publish because implementing one before a model passes would violate the acceptance order and could falsely imply that the requested agent is complete.
