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

Requirements: JDK 21 and Android SDK. Build with `./gradlew :app:assembleDebug`; run JVM tests with `./gradlew test`. `app/src/main/java/com/example/hjp/AppContainer.kt` is the composition root; host apps bind their repository, model gateway, tool registry, and Android executor through the module interfaces.

Generative and embedding binaries are external. See `external_assets/MANIFEST.json` for relative placement and checksums. No credentials or signing material is included.

## Validation baseline

Official A-15 E-3.2 full evaluation: TSR 167/400 (41.75%); Tool Selection 1,575/1,918 (82.12%); Argument Accuracy 1,470/1,565 (93.93%); Argument Field Accuracy 3,107/3,202 (97.03%); E2E Tool-call 1,470/2,174 (67.62%). A-21 is focused physical regression evidence only, not a full-400 rerun.

Known limitation: model decisions remain probabilistic. Calendar/compose surfaces are open-only and do not send/save automatically.
