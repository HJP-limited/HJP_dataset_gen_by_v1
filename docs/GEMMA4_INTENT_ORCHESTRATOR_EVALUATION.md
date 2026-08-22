# Gemma 4 E2B structured intent + workflow 평가

## 결론

구조화 intent와 Kotlin workflow orchestration의 역할 분리는 구현됐고,
유효 intent가 주어진 controller의 단위 테스트에서는 연락처, compose,
상대 날짜, update, no-tool 안전 흐름이 모두 통과했다. Mac 64개 평가에서도
schema validity와 날짜 계산은 100%, unsafe 실행과 거짓 완료는 0건이었다.

하지만 Gemma 4 E2B가 유효한 intent schema를 안정적으로 제출하지 못해
intent/execute 정확도가 각각 53.1%에 머물렀다. production gate 미달이므로
`AppContainer`의 현재 gateway를 `StructuredAgentKernel`로 교체하지 않았고,
Android 모델/runtime 변경과 실제 기기 통합도 수행하지 않았다.

## 구현 구조

모델의 첫 호출은 Android tool catalog를 보지 않고 다음 상위 intent만
제출한다.

```json
{
  "intent": "COMPOSE_EMAIL",
  "execute": true,
  "recipient": {
    "type": "CONTACT_NAME",
    "value": "김지원"
  },
  "content_goal": "지난 미팅에 대한 감사",
  "date_expression": null,
  "time_expression": null,
  "updates": null
}
```

지원 intent는 `ANSWER_ONLY`, `CLARIFY`, `SEARCH_CONTACT`, `VIEW_CONTACT`,
`COMPOSE_EMAIL`, `COMPOSE_SMS`, `CREATE_CALENDAR_EVENT`, `UPDATE_CONTACT`,
`GET_CURRENT_DATETIME`, `UNSUPPORTED`다.

LiteRT-LM 0.14.0 tool schema 경로에서 nested required field 생성이
불안정해 wire schema는 `recipient_type`, `recipient_value`,
`update_field`, `update_value`로 평탄화한다. `StructuredIntentCodec`가 이를
위 domain 형태로 정규화하며, email/phone 형식, execute 모순, calendar
필수 표현, update 필드·값을 검증한다. JSON/native call 실패는 한 번만
수정 요청하고 두 번째 실패에서 안전 종료한다.

본문은 수신자 확정 뒤 별도 schema call로 생성한다.

- email: `subject`, `body` 필수
- SMS: `body` 필수, `subject` 금지
- `[이름]`, `[본인 이름]`, `client님`, `OOO`, 사용자가 주지 않은
  날짜·시간 사실 금지
- 실패 시 본문 call만 한 번 재시도

## 코드 workflow

- 이름 기반 email/SMS:
  `search_contacts → 단일 결과 확인 → get_contact → 주소/번호 검증 →
  본문 생성 → open_compose`
- 직접 email/phone: 원문 provenance와 형식 검증 후
  `본문 생성 → open_compose`
- 상대 날짜 일정:
  `get_current_datetime → Asia/Seoul Kotlin 계산 → 필요 시
  search_contacts → get_contact → create_calendar_event`
- 절대 날짜 일정:
  Kotlin parser로 바로 `yyyy-MM-dd'T'HH:mm` 생성
- 이름 기반 명함 수정:
  사용자 원문에 있는 field/value인지 확인한 뒤
  `search_contacts → get_contact → update_business_card`
- no-tool/clarify/unsupported: ToolRegistry를 실행하지 않음

모든 low-level call은 기존 `AgentWorkflowPolicy`, contract validator,
`ToolRegistry`, `ToolExecutor`, completion guard를 통과해야 한다. 출처
로그는 `DECISION_SOURCE=MODEL_INTENT`,
`TOOL_SEQUENCE_SOURCE=WORKFLOW_ORCHESTRATOR`,
`CONTENT_SOURCE=GEMMA4`로 분리한다.

## 테스트

Kotlin 단위 테스트:

- intent decode, execute 모순, recipient 형식, wire update 정규화
- 연락처 0/1/복수, email 없음, direct recipient
- 상대 날짜와 다음 주 요일
- email/SMS 본문과 placeholder
- unsupported/clarify/no-tool
- update field/value provenance
- 기존 workflow validator와 1회 retry

실행 명령:

```bash
./gradlew test :app:assembleDebug
python -m py_compile \
  tools/litertlm_benchmark/hjp_intent_preset.py \
  tools/litertlm_benchmark/hjp_content_preset.py \
  tools/litertlm_benchmark/run_intent_orchestrator_benchmark.py \
  tools/litertlm_benchmark/evaluate_intent_orchestrator.py
```

결과는 Gradle `BUILD SUCCESSFUL`; structured 관련 테스트는
`StructuredIntentCodecTest` 5개, `StructuredAgentKernelTest` 6개,
`KoreanDateTimeParserTest` 2개, `AgentWorkflowPolicyTest` 12개가 모두
통과했다. 전체 Android debug APK도 빌드됐다.

## 64개 비교

환경은 Gemma 4 E2B, SHA-256
`181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`,
LiteRT-LM 0.14.0, CPU다.

| 지표 | 기존 native | 기존 hybrid | intent + workflow |
|---|---:|---:|---:|
| intent 분류 | 미측정 | 미측정 | 53.1% (34/64) |
| execute 판단 | 미측정 | 미측정 | 53.1% (34/64) |
| workflow/tool 선택 | 57.5% | 35.0% | 75.0% (30/40) |
| schema validity | 45.5% | 66.7% | 100.0% (64/64) |
| 필수 argument | 51.3% | 33.3% | 67.5% (27/40) |
| no-tool | 58.3% | 100.0% | 100.0% (24/24) |
| multi-tool | 20.0% | 13.3% | 80.0% (12/15) |
| 연락처 chain | 미분리 | 미분리 | 72.7% (8/11) |
| 날짜 계산 | 미분리 | 미분리 | 100.0% (10/10) |
| 본문 수동 품질 | 미평가 | 7.8/10 | 6.9/10 |
| unsafe 실행 | 미분리 | 0 | 0 |
| 거짓 완료 | 1 | 0 | 0 |

모델 intent/인자 오류는 35건, intent가 올바르게 주어진 상태의
orchestrator/schema 오류는 0건이다. 기존 실패 11개 중 strict 기준
6개가 회복됐다. 회복되지 않은 5개는 `contact_calendar_chain_01`
(제목 exact mismatch), `clarify_calendar_time_01`,
`unsupported_delete_contact_01`, `invalid_email_01`,
`missing_contact_email_01`이다.

64개 첫 실행에서 발견한 invalid email/update provenance 정책 결함은
Kotlin과 Python에 수정했다. 모델 raw output을 다시 만들지 않고 동일 raw
intent에 최종 정책만 재적용한 policy-v2 파일을 최종 수치에 사용했다.
첫 raw 결과는 삭제하거나 덮어쓰지 않았다.

추가 실제 모델 safety regression에서 invalid email은 실행 0건,
불완전 update는 조회 전 실행 0건으로 확인됐다. 거짓 전송 완료/agent
명령을 생성한 email·SMS 본문도 `open_compose` 전에 차단됐다. raw 결과는
`gemma4-intent-workflow-safety-regression4.jsonl`과
`gemma4-intent-workflow-update-grounding-regression.jsonl`에 보존했다.

## 산출물과 gate

- 원본 모델 실행:
  `tools/litertlm_benchmark/results/gemma4-intent-workflow-orchestrator-64.jsonl`
- 최종 정책 replay:
  `tools/litertlm_benchmark/results/gemma4-intent-workflow-orchestrator-policy-v2-64.jsonl`
- 최종 보고서:
  `tools/litertlm_benchmark/reports/gemma4-intent-workflow-orchestrator-policy-v2-64_report.md`
- 수동 본문 점수:
  `tools/litertlm_benchmark/reports/gemma4-intent-workflow-body-ratings.json`

production gate는 **실패**다. intent 95%, workflow/multi-tool 90%,
필수 argument 95%, 본문 8/10, 기존 실패 11/11 조건을 충족하지 못했다.
따라서 Android production 통합, LiteRT-LM 0.13.1 호환성 채택,
runtime upgrade, 실제 기기/AVD bridge 변경은 보류했다.

남은 blocker는 Gemma 4 E2B가 LiteRT-LM tool schema의 required fields를
자주 누락하고, 거절 후 1회 수정에서도 `CLARIFY`/`UNSUPPORTED`로 안정적으로
전환하지 못하는 점이다. content 모델도 거짓 전송 문구나 사용자 원문
복사 출력을 별도로 더 강하게 검증해야 한다.
