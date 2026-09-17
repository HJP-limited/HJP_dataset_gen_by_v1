# HJP On-device Contact Management Agent

HJP combines Gemma 4 E2B with deterministic routing, typed workflow policy, local contact retrieval, and safe Android surfaces.

## Architecture

```text
User → Router / AgentKernel → Gemma 4 E2B → Tool decision
     → AgentWorkflowPolicy validation/guards → Tool executor
     → Room / Android surfaces → ToolResult → ConversationMemory → next turn
```

`agent-core` owns ReAct execution, routing, memory, references, correction, freshness, and safety. `agent-contract`/`tool-contract` define interfaces; `tool-contact`, `tool-datetime`, and `tool-android-intents` implement capabilities; `search-core` provides retrieval; `llm-litert` provides LiteRT-LM integration; `app` wires Room/FTS, EmbeddingGemma, Android surfaces, and UI.

## Tools

- `search_contacts`: local retrieval and persisted candidate order
- `get_contact`: verified card detail read (`display`, `email`, `sms`, `calendar`)
- `update_business_card`: confirmation- and provenance-checked update
- `create_calendar_event`: opens Android calendar insert; user saves
- `get_current_datetime`: runtime temporal anchor
- `open_compose`: opens email/SMS compose; user sends

Policy A permits direct unique resolution, searches unresolved names, and keeps 2+ candidates in clarification. ConversationMemory retains target/reference/correction state; fresh-read, provenance, confirmation, and SideEffectGuard checks precede execution.

## Build and integration

Requirements: JDK 17 and Android SDK 36. Build with `./gradlew :app:assembleDebug`; run the core JVM tests with `./gradlew :agent-core:test`. `app/src/main/java/com/example/hjp/AppContainer.kt` is the composition root; host apps bind their repository, model gateway, tool registry, and Android executor through the module interfaces.

Generative and embedding binaries are external. See `external_assets/MANIFEST.json` for relative placement and checksums. A source build can compile without bundling those files, but real inference/retrieval requires the declared artifacts. No credentials or signing material is included. The current production `AppContainer` selects the LiteRT `CPU_ONLY` backend; GPU evaluation is a separate instrumentation configuration, not the production default.

## Validation baseline

The current official contract is E-3.7, SHA-256 `7e028767bf95cefc7438885ac572cb3db4aba94f485b41393d7f0702f07f4961` (400 scenarios / 1,918 turns). Its first official baseline is a reproducible re-score of the immutable A-56 physical trace: TSR 288/400 (72.00%); Tool Selection 1,589/1,918 (82.85%); Argument Accuracy 1,644/1,708 (96.25%); Argument Field Accuracy 3,418/3,482 (98.16%); E2E Tool-call 1,644/2,154 (76.32%). It is not a fresh physical run after A-63. E-3.2/A-15 and E-3.5 results are historical baselines under older contracts.

Use `tools/agent_eval_multiturn_v1/README.md` for contract validation and offline re-scoring. E-3.6 is deprecated and non-reproducible as an official contract.

Known limitation: model decisions remain probabilistic. Calendar/compose surfaces are open-only and do not send/save automatically.
