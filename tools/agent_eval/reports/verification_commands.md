# Verification commands

```bash
python -m py_compile tools/litertlm_benchmark/*.py tools/agent_eval/*.py
python tools/litertlm_benchmark/run_staged_intent_benchmark.py --help
python tools/litertlm_benchmark/run_staged_intent_benchmark.py \
  --model models/gemma-4-E2B-it.litertlm \
  --tests-file tools/litertlm_benchmark/test_cases.jsonl \
  --test-case current_datetime_01 --dry-run

./gradlew :agent-contract:test :agent-core:test :llm-litert:test
./gradlew clean test :app:assembleDebug :app:assembleDebugAndroidTest

python tools/ryeong_search_benchmark/evaluate_fts.py \
  --fixture tools/ryeong_search_benchmark/fixtures/cards_test50.json \
  --output tools/ryeong_search_benchmark/results-agent-eval-final.json
```

Mac model runs are the raw `final_*.jsonl` files in `tools/agent_eval/results`.
The held-out evaluator alone receives `.sealed/held_out_reference.jsonl`.
