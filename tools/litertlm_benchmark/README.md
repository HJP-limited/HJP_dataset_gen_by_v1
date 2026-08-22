# HJP LiteRT-LM tool-calling benchmark

이 도구는 Mac에서 `.litertlm` 모델의 한국어 본문 생성과 LiteRT-LM native tool calling을 비교한다. preset의 여섯 tool은 앱의 현재 `ToolContract` schema와 이름을 맞춘 dry-run mock이며 외부 앱 실행, 전송, 저장, 명함 수정을 하지 않는다.

## 1. 환경 준비

시스템 Python은 변경하지 않는다. Python 3.10 이상 격리 환경에 현재 설치 확인 버전인 `litert-lm 0.14.0`을 설치한다.

확인된 기준 환경은 Android 의존성 `litertlm-android 0.13.1`, macOS 시스템 Python `3.9.6`, 이 도구의 `.venv` Python `3.12.13`, CLI `litert-lm 0.14.0`이다. 현재 모델은 `models/hjp-agent.litertlm`이며 SHA-256과 정확한 실행 환경은 모든 결과 JSONL 첫 행에 자동 기록된다.

```bash
cd tools/litertlm_benchmark
/opt/homebrew/bin/python3.12 -m venv .venv
.venv/bin/python -m pip install "litert-lm==0.14.0"
.venv/bin/litert-lm --version
.venv/bin/litert-lm run --help
```

모델이나 Hugging Face token은 이 디렉터리에 저장하지 않는다. 결과와 로컬 `models.json`, `.venv`는 Git에서 제외된다.

## 2. 현재 모델 실행 방법

다음 스크립트는 `models/hjp-agent.litertlm`의 존재, CLI, preset, 결과 디렉터리를 확인하고 CPU로 지정된 smoke test 3건만 실행한 뒤 평가 보고서를 만든다.

```bash
tools/litertlm_benchmark/run_current_model.sh
```

현재 artifact의 metadata에는 `max_num_tokens: 1024`가 내장돼 있으며 전체 tool catalog 입력은 이 한도를 넘는다. CLI의 `--max-num-tokens`는 KV cache 용량 옵션이지 artifact의 학습·export context를 확장하는 옵션이 아니다. `2048`로 강제했을 때 0.13.1과 0.14.0 모두 비정상 출력이 재현됐으므로 스크립트는 이 옵션을 사용하지 않는다. 이 실행은 정상 동작 확인이 아니라 원래 context 초과 오류를 보존하는 진단 실행이다. 자세한 판정은 `docs/AGENT_MODEL_RECOVERY.md`에 있다.

전체 케이스는 의도적으로 자동 실행하지 않는다. 필요할 때 실행기에서 `--test-case` 옵션을 빼고 직접 실행한다.

## 3. 새로운 모델 등록 방법

`models.example.json`을 `models.json`으로 복사하고 로컬 `.litertlm` 경로, 표시 이름, `cpu` 또는 `gpu` backend를 기록한다.

```bash
cp tools/litertlm_benchmark/models.example.json \
  tools/litertlm_benchmark/models.json
tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/run_benchmark.py
```

한 모델만 시험할 때는 config 없이 지정할 수 있다.

```bash
tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/run_benchmark.py \
  --model /absolute/path/model.litertlm \
  --model-name candidate-name \
  --backend cpu
```

## 4. CPU/GPU 실행 방법

현재 CLI의 실제 옵션인 `--backend cpu`와 `--backend gpu`만 노출한다.

```bash
tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/run_benchmark.py \
  --model models/hjp-agent.litertlm --backend cpu

tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/run_benchmark.py \
  --model /absolute/path/candidate.litertlm --backend gpu
```

backend 지원 여부는 모델 패키지와 Mac LiteRT-LM runtime에 달려 있다. 실패 시 stderr, return code, timeout이 해당 케이스 결과에 그대로 남는다.

## 5. 일부 테스트만 실행하는 방법

ID와 category 필터는 반복할 수 있다.

```bash
tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/run_benchmark.py \
  --model models/hjp-agent.litertlm --backend cpu \
  --test-case current_datetime_01 \
  --test-case compose_email_context_01

tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/run_benchmark.py \
  --model models/hjp-agent.litertlm --backend cpu \
  --category no_tool --limit 3
```

실행하지 않고 명령만 검토하려면 같은 명령에 `--dry-run`을 붙인다.

## 6. 평가 보고서 생성 방법

결과 JSONL을 지정하면 같은 이름의 Markdown 보고서와 사람이 본문 품질을 0~10점으로 기록할 CSV가 생성된다. 입력을 생략하면 가장 최근 결과를 사용한다.

```bash
tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/evaluate_results.py \
  tools/litertlm_benchmark/results/<result>.jsonl
```

## 7. native tool call 성공 판정 기준

stdout에 LiteRT-LM CLI가 출력한 `[tool_call]` 표식이 있고 뒤의 event JSON과 `arguments`가 파싱될 때만 native 호출로 인정한다. 일반 답변에 JSON이나 tool 이름이 출력돼도 `[tool_call]`이 없으면 인정하지 않는다. 선택 정확도는 기대 tool 순서와 실제 호출 순서를 비교하고, schema 평가는 현재 앱의 필수 필드·타입·enum·허용 필드로 검사한다.

hybrid preset에서는 raw model tool call, 정책 승인·거절, 실제 dry-run 실행 sequence를 별도 필드로 평가한다. 정책이 차단한 raw call을 native 선택 성공으로 바꾸지는 않는다.

## 8. Android 앱 실행과 Mac CLI mock 실험의 차이

Mac CLI는 같은 이름과 핵심 schema를 제공하지만 모든 tool 결과에 `dry_run: true`를 넣는 고정 fixture다. 김지원 명함 조회와 현재 시각도 고정값이며 이메일·문자 작성 화면, 캘린더, 명함 저장을 실제로 실행하지 않는다. 현재 Mac CLI는 `0.14.0`, Android 의존성은 `0.13.1`이므로 runtime 버전까지 동일한 동등성 시험도 아니다. 따라서 이 벤치마크는 모델의 native tool event, 인자, 본문, chain 능력을 비교하는 실험이지 Android Intent, Room 저장소, 기기 권한, 앱 UI 통합의 성공을 증명하지 않는다.

## 9. Structured intent + workflow 실험

Gemma가 low-level Android tool을 선택하지 않고 상위 intent만 제출하고,
코드 controller가 workflow를 실행하는 비교는 다음 명령으로 재현한다.

```bash
tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/run_intent_orchestrator_benchmark.py \
  --model models/gemma-4-E2B-it.litertlm --backend cpu

tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/evaluate_intent_orchestrator.py \
  tools/litertlm_benchmark/results/gemma4-intent-workflow-orchestrator-64.jsonl
```

`hjp_intent_preset.py`는 상위 intent schema만, `hjp_content_preset.py`는
email/SMS 본문 schema만 노출한다. raw intent/content 출력, 정책 결정,
실제 mock 실행 sequence는 JSONL의 서로 다른 필드에 보존된다.

## 10. Staged intent + constrained decoding

Stage 1 `intent/action`, intent별 Stage 2 slot, 별도 본문 schema를
LiteRT-LM 0.14 Python API의 constrained decoding으로 실행한다.

```bash
tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/run_staged_intent_benchmark.py \
  --model models/gemma-4-E2B-it.litertlm --backend cpu \
  --output tools/litertlm_benchmark/results/gemma4-staged-intent-e-64-final.jsonl

tools/litertlm_benchmark/.venv/bin/python \
  tools/litertlm_benchmark/evaluate_staged_intent.py \
  tools/litertlm_benchmark/results/gemma4-staged-intent-e-64-final.jsonl \
  --ratings tools/litertlm_benchmark/reports/gemma4-staged-intent-e-64-final_body_ratings.json
```

`--no-constrained`, `--no-repair`, `--test-case ID`, `--limit N`으로
ablation과 부분 실행을 할 수 있다. 각 단계는 실제 native schema tool
call만 성공으로 인정하며 raw 응답과 parsing/validation 오류를 보존한다.
