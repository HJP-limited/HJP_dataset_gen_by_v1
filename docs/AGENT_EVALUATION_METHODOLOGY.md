# Android 명함 에이전트 평가 방법론

## 1. 평가 단위와 고정 조건

평가 대상은 Gemma 4 E2B의 저수준 native tool-call 능력이 아니라 현재
production 설계인 `Stage 1 intent/action → intent별 Stage 2 slot → Kotlin
workflow → ToolRegistry → 최종 dry-run 상태` 전체다. 단, 한 개의 합성
"tool-call 성공률"로 합치지 않고 각 경계를 별도로 센다.

고정 baseline은 `tools/agent_eval/results/baseline_manifest.json`이다. 모델,
tokenizer, APK, raw 64 결과의 SHA-256과 dirty worktree를 함께 보존했다.
sampling은 LiteRT-LM 0.14.0 CPU, constrained decoding, `top_k=1`,
`top_p=1`, `temperature=0`, `seed=42`, 단계별 repair 1회다. Android의
LiteRT-LM 0.13.1과 RAG SDK 0.3.0은 별도 runtime으로 기록한다.

## 2. 데이터와 누출 방지

기존 staged 64, 멀티턴 40, Ryeong 192/495, JVM/AVD 자료는 회귀 set으로
유지한다. 신규 `agent-eval-v1`은 213개이며 development/validation/held-out
각 71개다. 이 중 201개는 독립 single-turn model task이고 12개는 별도
session runner용 multi-turn task다. split마다 인명, 이메일 domain, 발화 골격을 분리해 단순
paraphrase가 경계를 넘지 않게 했다.

- development: 원인 분석과 후보 변경 작성에만 사용
- validation: 변경 채택/폐기에만 사용
- held-out: 입력만 공개하며 기대값은 `.sealed/held_out_reference.jsonl`에
  분리한다. 변경을 모두 선택한 뒤 최종 evaluator가 한 번만 읽는다.

생성 직후 manifest의 파일 hash를 고정한다. held-out 기대값을 보고 prompt,
schema 또는 정책을 조정하면 해당 run은 무효다. 데이터는 single/no-tool,
direct compose, 이름 기반 3단계 chain, 일정, 수정, 부족 정보, unsupported,
invalid recipient, 0/다수/상세 누락, tool 오류, stale ID, 2~3턴을 포함한다.

## 3. Function-call 계층 평가

BFCL의 분해 원칙을 현재 구조에 맞춰 다음처럼 적용한다.

| 경계 | 지표 | 성공 조건 |
|---|---|---|
| 모델 Stage 1 | intent/action accuracy, macro-F1, confusion matrix | native constrained `submit_action`의 실제 값이 정답 |
| 모델 Stage 2 | required slot exact match, slot P/R/F1 | 모델이 실제 생성한 필드만 평가; code 보정은 모델 성공이 아님 |
| parser/schema | structured output, type/enum/format | 정확히 한 native schema call이 decode됨 |
| orchestrator | first tool, workflow, transition | 필수 안전 순서 또는 허용 trajectory와 일치 |
| validator | argument validation, policy compliance | 모든 실행 call이 schema/provenance/capability를 통과 |
| ToolRegistry/mock | execution/recovery | 실제 dry-run result와 다음 상태 전이가 정상 |
| task | final-state match | 아래 end-state와 정책을 모두 만족 |

validator가 위험 call을 막은 것은 `unsafe=0`에는 기여하지만 모델 action이나
task success를 자동으로 올리지 않는다. orchestrator가 올바른 tool을 만든
것도 모델 native tool-call 성공으로 기록하지 않는다.

## 4. End-state 판정

문자열 exact match 대신 상태를 우선한다.

- 연락처: 올바른 Room `card_id`가 선택되고 필수 `search → get` provenance가
  존재해야 한다.
- compose: 채널, 검증된 수신자, 제목/본문이 맞고 작성 화면까지만 열려야 한다.
- calendar: Asia/Seoul 절대 시각, title, 참석자가 맞아야 한다.
- update: 대상/필드/값이 맞고 기존 확인 경계를 넘지 않아야 한다.
- unsupported/no-tool: 등록되지 않은 도구와 DB mutation이 0이어야 한다.

`allowed_trajectories`에 명시된 대체 순서는 최종 상태와 필수 정책이 같으면
허용한다. 이름 기반 compose에서 `search_contacts` 또는 `get_contact`를
생략한 경로, stale ID 실행, 잘못된 사람 상세 조회는 결과가 우연히 같아도
실패다. 의도적으로 주입한 backend 오류는 오류를 안전하게 노출하고 후속
실행을 멈춘 상태가 정답이며 tool 실행 성공으로 세지 않는다.

## 5. 신뢰성과 통계

대표 category별 case를 최소 5회 독립 실행한다. 각 task 첫 run 성공률은
`pass@1`, 처음 3회가 모두 성공한 task 비율은 `pass^3`, 처음 5회가 모두
성공한 비율은 `pass^5`다. 성공/실패 binary metric은 case 단위 10,000회
bootstrap percentile 95% CI를 제시한다. timeout, crash, 출력/순위 변동,
p50/p95 latency를 별도로 기록한다.

본문은 빈 값, placeholder, 거짓 완료, 원문에 없는 날짜/약속, 핵심 사실
보존을 규칙으로 검사한다. 사람 평가는 case ID를 가리고 무작위 순서로
자연스러움·사실성·목적 반영·즉시 사용 가능성을 0~10점으로 매긴다.
생성 실패/미평가는 전체 분모에서 0점이다. LLM judge 단독 점수는 사용하지
않는다.

## 6. Oracle A–E와 원인 귀속

- A: 실제 모델 → 실제 orchestrator → 실제 mock tool
- B: 정답 Stage 1만 주입; Stage 2/content/workflow는 실제 경로
- C: 정답 Stage 1+2를 주입; orchestrator/validator/tool은 실제 경로
- D: 정답 모델 출력과 정상 tool result를 주입
- E: 실제 모델 출력 중 잘못된 한 필드만 정답으로 교정

실행할 수 없는 ablation은 성공으로 간주하지 않고 `NOT_EVALUABLE` 또는
`NOT RUN`으로 남긴다. 각 실패는 `MODEL_INTENT`, `MODEL_ACTION`,
`MODEL_SLOT`, `MODEL_CONTENT`, `SCHEMA_OR_PARSER`, `ORCHESTRATOR`,
`VALIDATOR_POLICY`, `TOOL_BACKEND`, `RETRIEVAL`, `SESSION_MEMORY`,
`RUNTIME_ENVIRONMENT`, `EVALUATOR_OR_EXPECTATION` 중 주원인 하나와 선택적
보조 원인을 갖는다.

모델 한계는 oracle downstream 성공, schema/parser 정상, 짧은 constrained
schema와 targeted repair 적용, 서로 다른 일반화 실험 3개 이상, validation과
held-out 개선 1%p 미만, 여러 paraphrase/반복에서 동일 실패라는 조건을 모두
만족할 때만 확정한다. oracle 모델 출력을 넣어도 실패하면 구조 문제다.

## 7. 변경 채택 절차

각 ablation은 한 계층만 바꾼다: 실패 재현 → 회귀 test 추가 → development
→ validation → 채택/폐기. 채택 조건은 목표 metric과 validation 개선,
unsafe/wrong-person/false-completion 비증가, 기존 64·40·검색 회귀 없음이다.
held-out은 모든 변경 선택 후 한 번 평가한다. latency/memory 악화도 함께
기록한다. 특정 문장이나 test ID 분기는 금지한다.

## 8. Android/검색 환경 해석

AVD에서는 DB migration, Room FTS, keyword fallback, workflow, crash만
검증한다. AVD deterministic embedding이나 `KEYWORD_ONLY`를 semantic
성능으로 합산하지 않는다. 실제 EmbeddingGemma의 768차원 query/document
추론, cache, RRF, native memory는 물리 ARM64 장치에서만 측정하며 장치가
없으면 `NOT RUN`이다.
