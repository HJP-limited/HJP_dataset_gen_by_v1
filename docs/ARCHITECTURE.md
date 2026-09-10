# Architecture

```text
User → Router / AgentKernel → Gemma 4 E2B → Tool decision
     → AgentWorkflowPolicy validation/guards → Tool executor
     → Room / Android surfaces → ToolResult → ConversationMemory → next turn
```

`agent-core` owns ReAct execution, routing, references, correction, freshness, and safety. `agent-contract`/`tool-contract` define interfaces. `tool-contact`, `tool-datetime`, and `tool-android-intents` implement capabilities. `search-core` provides retrieval, `llm-litert` integrates LiteRT-LM, and `app` wires Room/FTS, EmbeddingGemma, Android surfaces, and UI.
