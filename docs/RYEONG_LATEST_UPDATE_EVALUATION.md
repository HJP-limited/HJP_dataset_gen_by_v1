# Ryeong latest search integration evaluation

## Scope and provenance

- Previous integration commit: `0bfd236efa40987c8f0a620d256be9c099080274`
- Integrated `llm-integration-work` HEAD: `b543a189249df7d87523564b844cb472b15fcbf3`
- Fresh clone: `/tmp/hjp-ryeong-latest`
- Upstream unit tests: 7/7 passed.
- The Ryeong demo UI, destructive migration, duplicate repository/DTO and demo chat model were not copied.

## Production path

```text
search_contacts
  -> RoomBusinessCardRepository
  -> FTS4 phrase
  -> FTS4 all terms
  -> FTS4 prefix
  -> synonym LIKE fallback
  -> SemanticRetriever (only when the compatible model and tokenizer initialize)
  -> ReciprocalRankFusion
  -> RetrievalResponse / privacy-minimized ToolResult

get_contact(card_id)
  -> current Room row existence check
  -> detailed contact data
```

The production dependency graph is assembled in `AppContainer` and has one Room keyword path.
The in-memory retriever remains only as the non-Room JVM fixture/fallback implementation. Search
results contain `card_id`, name, company, title and match provenance; phone, email and address are
returned only by `get_contact`. A missing Room row rejects a stale search ID.

## DB migration and indexing

- Database version: 2 -> 3.
- `MIGRATION_2_3` creates a real Room FTS4 table with `unicode61` and prefix indexes 2/3/4, then
  indexes all existing rows using the original Room rowid and stable string `card_id`.
- `business_cards` and `card_embeddings` are not dropped or rewritten.
- Seed, update and delete operations synchronize FTS through DAO transactions. An update retains
  the card ID and refreshes searchable text; document source hash validation invalidates changed
  embeddings.
- `fallbackToDestructiveMigration()` is not present.
- AVD migration test verified that a v2 card and its embedding survive v3 and are immediately
  searchable through FTS.

## EmbeddingGemma artifacts and cache safety

| Item | Result |
|---|---|
| Source | `/Users/byeol/Downloads/embeddinggemma-300M_seq256_mixed-precision.tflite` |
| Canonical asset | `app/src/main/assets/models/embeddinggemma-300m.tflite` |
| Size | 179,131,736 bytes |
| Source/destination SHA-256 | `37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5` (match) |
| APK compression | `.tflite` is in `noCompress`; APK entry size matches 179,131,736 bytes |
| Git protection | canonical TFLite and tokenizer paths are ignored |
| Tokenizer asset | `app/src/main/assets/models/sentencepiece.model` |
| Tokenizer size / SHA-256 | 4,683,319 bytes / `d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7` |
| Tokenizer provenance | Ryeong-required `litert-community/embeddinggemma-300m` pairing; recovered from the existing verified Ryeong device build |
| Model-backed semantic E2E | NOT RUN; blocked only by the absence of a physical ARM64 device |

The original files remain unchanged. The app copies both bundled artifacts to `filesDir/models`,
verifies their fixed SHA-256 values before initialization, and replaces only a stale internal copy.
Query/document task types are separate. Output must be dimension 768, finite and non-zero. The
embedding cache key preserves model and tokenizer hashes and each row also checks dimension and
document-text hash. A matching cache was reused with zero document inference; a changed engine
identity re-embedded all cards. Unproven Ryeong precomputed vectors were not used.

The recovered tokenizer was already validated with vocabulary 262,144, BOS=2, EOS=1, PAD=0,
UNK=3 and Korean/English encode/decode smoke in the existing Ryeong device build. Direct download
from the gated Hugging Face repository returned 401 because no local authentication was configured;
the existing build records the same model SHA, tokenizer size and tokenizer SHA used here.

An unguarded ARM64 AVD probe loaded the tokenizer/model, initialized TFLite and delegated 2,215 of
2,265 nodes before the RAG SDK JNI executed an unavailable SME2 instruction (`SIGILL` in
`kai_get_sme_vector_length_u8`). This distinguishes an AVD CPU/runtime limitation from tokenizer
parsing or model mismatch. Production now detects all emulators before JNI initialization and uses
diagnostic `KEYWORD_ONLY`; physical ARM64 remains the only semantic-success test path.

## Search quality and performance

The local standard-library evaluator uses Ryeong's isolated 50-card test fixture (never production
seed data) and 192 exact-name/company/field/phone queries.

| Metric | Previous in-memory LIKE | Latest tiered Room-equivalent FTS4 |
|---|---:|---:|
| Recall@1 | 100.0% | 100.0% |
| Recall@5 | 100.0% | 100.0% |
| MRR | 1.000 | 1.000 |
| Exact-name Recall@1 | 100.0% | 100.0% |
| Phone Recall@1 | 100.0% | 100.0% |
| Field/company Recall@1 | 100.0% | 100.0% |
| p50 | 0.492 ms | 0.061 ms |
| p95 | 0.602 ms | 0.202 ms |
| Python traced peak | 0.0125 MiB | 0.0253 MiB |

The current two-card app fixture also remained Recall@1/Recall@5/MRR = 100% for 8 queries. The JVM
adapter evaluator measured previous and integrated keyword fallback at Recall@1/5/MRR = 0.8 with no
regression; a deterministic 768-dimension fake hybrid reached 1.0 including semantic-only cases.
Its latest run measured fallback p50/p95 0.276/0.428 ms, initialization 0.769 ms and observed heap
peak 19.298 MiB. Those JVM fake/fallback measurements are not Android native EmbeddingGemma claims.

Stored results:

- `tools/ryeong_search_benchmark/results-ryeong-test50.json`
- `tools/ryeong_search_benchmark/results-current-room.json`
- `tool-contact/build/test-results/test/TEST-com.hjp.tool.contact.RyeongSearchEvaluatorTest.xml`

## Multiturn and safety

The session resolver uses only prior tool-grounded IDs. It distinguishes pronouns/ellipsis from an
explicit new name, does not carry focus across a newly introduced number/phone query, supports
ordinal selection against prior result order, and leaves duplicate names unresolved. Structured
intent remains model-owned; the resolver does not classify intent or invent contacts.

- Multiturn scenarios: 20/20 passed, including pronoun, omitted subject, honorific, explicit name
  switch, digit separation, ordinal selection and duplicate-name ambiguity.
- Wrong-person detail lookup: 0.
- Stale ID execution: 0 (unit and AVD checks).
- Search output privacy leak: 0; detailed PII remains behind `get_contact`.
- Unsafe execution and false completion in staged evaluation: 0/64 and 0/64.

## Staged agent regression

The same 64 cases were rerun using Gemma 4 E2B, LiteRT-LM 0.14.0 CPU and the unchanged expected
values. Raw output and report are:

- `tools/litertlm_benchmark/results/gemma4-staged-intent-e-64-ryeong-latest-b543a18.jsonl`
- `tools/litertlm_benchmark/reports/gemma4-staged-intent-e-64-ryeong-latest-b543a18_report.md`

| Metric | Before latest search update | After | Required floor |
|---|---:|---:|---:|
| Strict | 54/64 | 54/64 | >= 54/64 |
| Workflow | 39/40 | 39/40 | >= 39/40 |
| Multi-tool | 15/15 | 15/15 | 15/15 |
| Contact chain | 11/11 | 11/11 | 11/11 |
| Schema | 64/64 | 64/64 | 64/64 |
| Date | 10/10 | 10/10 | - |
| Unsafe | 0 | 0 | 0 |
| False completion | 0 | 0 | 0 |

The search update therefore introduces no staged-agent regression. The separate structured-model
production gate remains failed because action accuracy is 93.8%, unsupported accuracy is 57.1% and
body quality is 7.8/10; this integration does not misreport those model limitations as search faults.

## Executed verification

- `git diff 0bfd236e..b543a18` and fresh-clone HEAD verification.
- Ryeong upstream `./gradlew test`: 7/7 passed.
- `./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest`: passed; 82 JVM/unit tests,
  zero failures.
- AVD (`sdk_gphone64_arm64`, emulator only) instrumentation: 4/4 applicable tests passed and the
  physical-device-only semantic test was skipped: v2->v3 migration, tiered FTS, update/reindex and
  guarded `KEYWORD_ONLY` fallback all passed without a process crash.
- Python evaluator syntax/help and 50-card 192-query run: passed.
- Gemma 4 staged benchmark: 64/64 processes completed and evaluated.
- AVD private `filesDir/models` bootstrap: model and tokenizer sizes/hashes match the bundled pair.
- Physical ARM64 device: not attached, therefore model-backed semantic E2E was not run.

## APK and reproducible device test

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
  - 322,434,563 bytes
  - SHA-256 `fe6f45cfd549f702df93be1e17cd4da0dadec7f4153134b47ac58145e292e317`
- Test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - 1,071,586 bytes
  - SHA-256 `14cf9e77ce5945b0d2c584bc7e29ff4f3537baf2b410096a8dbf4f6598f07bdc`
- Physical-device script: `scripts/run-ryeong-arm64-e2e.sh <compatible-sentencepiece.model>`.

The script verifies one real ARM64 target, installs both APKs, provides the tokenizer only to app
private storage, and runs DB/search plus model-backed `search_contacts -> get_contact -> compose`
instrumentation. It never sends email/SMS or saves an event.

## Remaining blockers

1. No physical ARM64 Android device was attached. The ARM64 AVD keyword/migration result is not
   recorded as physical-device semantic success.
2. Consequently real model initialization time, query/document inference latency and Android native
   peak memory remain unmeasured. Keyword fallback and artifact/cache validation are verified.
