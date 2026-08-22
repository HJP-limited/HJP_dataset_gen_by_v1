# Gemma 4 E2B staged intent 평가

## 결론

기존 단일 structured intent를 Stage 1 `intent/action`, intent별 Stage 2
slot, 단계별 1회 repair, 원문 기반 별도 본문 생성으로 분리했다. 최종
64개에서 intent 100%, required slot 97.4%, workflow 97.5%, multi-tool과
날짜 계산 100%를 얻었다. schema/parser 오류와 orchestrator 오류는 0건이다.

그러나 action 93.8%, unsupported 57.1%, 본문 생성 성공 88.2%, 본문 품질
7.8/10, 기존 실패 회복 10/11로 production gate는 실패했다. 따라서
`AppContainer`의 기존 Android gateway/runtime 경로는 변경하지 않았고
`StructuredAgentKernel` production 연결도 수행하지 않았다.

## 최종 wire schema

Stage 1:

```json
{
  "intent": "COMPOSE_EMAIL",
  "action": "EXECUTE"
}
```

- intent: `SEARCH_CONTACT`, `VIEW_CONTACT`, `COMPOSE_EMAIL`, `COMPOSE_SMS`,
  `CREATE_CALENDAR_EVENT`, `UPDATE_CONTACT`, `GET_CURRENT_DATETIME`, `GENERAL`
- action: `EXECUTE`, `PREVIEW`, `ANSWER`, `CLARIFY`, `UNSUPPORTED`

Stage 2:

- search: `contact_name`
- view: `recipient_type`, `recipient_value`
- email/SMS: `recipient_type`, `recipient_value`, `content_goal`, optional `tone`
- calendar: `title`, `date_expression`, `time_expression`, `attendee_type`,
  `contact_name`
- update: `contact_name`, `update_field`, `update_value`
- datetime/general/clarify/unsupported: 복잡한 slot 객체 없음

일정의 `attendee_type/contact_name`은 optional 누락을 막기 위해 둘 다
required다. `NONE`일 때만 `contact_name=""`를 허용한다. Kotlin domain
변환은 `StagedIntentCodec`에서 수행하며 기존 `StructuredIntentPlan`과
orchestrator는 유지한다.

## constrained decoding과 repair

로컬 LiteRT-LM 0.14 Python API의
`Engine.create_conversation(enable_constrained_decoding=True)`를 사용했다.
현재 `litert-lm run --help`에는 같은 flag가 없고 Android 0.13.1/0.14
공개 `ConversationConfig`에도 대응 property가 없어, Mac 평가 worker에만
명시적으로 적용했다.

constrained 미사용/no-repair 7개 probe는 6/7만 구조화 plan을 만들었고
`no_tool_email_example_02`가 실패했다. constrained 최종 run은 Stage 1
구조화 출력 64/64였다.

repair는 다음 단계만 최대 한 번 다시 생성한다.

- Stage 1 parse/schema 실패 → Stage 1
- Stage 2 필수/format/provenance 실패 → 같은 Stage 2
- Stage 2가 계속 실패 → 모델에 누락 오류만 주고 action을 한 번 재판정
- 본문 schema/safety 실패 → 본문

재시도 뒤에도 실패하면 tool을 실행하지 않는다. keyword router나
정규식으로 모델 intent를 생성하지 않는다.

## 본문 생성

본문 worker에는 원문, 확정 수신자 이름/회사/직함, channel, content goal,
요청 말투, 사용자 제공 사실을 전달한다. email은 `subject/body`, SMS는
`body`만 constrained schema로 받는다. placeholder, 어색한 `client님`,
원문에 없는 날짜, UI 지시 echo, 전송/저장 완료 표현, 240자를 넘는 SMS를
차단한다.

사람이 0~10점으로 평가한 17개 compose 기대 사례의 평균은 실패=0을 포함해
7.8/10이다. `false_completion_guard_01`은 Stage 2 repair 뒤에도 행동
지시를 content goal에서 제거하지 못해 안전 중단됐다. 거짓 완료는 0건이다.

## A~E 결과

| 지표 | A 단일 structured | B Stage 1 | C + Stage 2 | D + repair | E + 원문 본문 |
|---|---:|---:|---:|---:|---:|
| intent | 53.1% | 96.9% | 96.9% | 100.0% | 100.0% |
| action/execute | 53.1% | 78.1% | 78.1% | 93.8% | 93.8% |
| 구조화 출력 | 56.3% | 100.0% | 100.0% | 100.0% | 100.0% |
| required slot | 67.5% | N/A | 94.9% | 97.4% | 97.4% |
| workflow | 75.0% | N/A | N/A | 97.5% | 97.5% |
| multi-tool | 80.0% | N/A | N/A | 100.0% | 100.0% |
| 본문 품질 | 6.9/10 | N/A | N/A | 6.9/10 | 7.8/10 |

최종 세부 지표:

- clarify 100% (10/10), answer/preview 100% (7/7)
- unsupported 57.1% (4/7)
- 연락처 chain 100% (11/11), 날짜 100% (10/10)
- schema validity 100% (64/64)
- strict 전체 84.4% (54/64)
- 본문 생성 성공 88.2% (15/17)
- unsafe 실행 0, 거짓 완료 0
- 모델 오류 4, schema/parser 오류 0, orchestrator 오류 0

남은 모델 오류는 `false_completion_guard_01`,
`unsupported_send_email_02`, `unsupported_send_sms_02`,
`unsupported_delete_calendar_02`다. 기존 실패 중 남은 1건은
`contact_calendar_chain_01`의 title이 `후속 상담 일정`으로 생성돼 기존
기대 `후속 상담` exact 비교를 통과하지 못한 사례다. 기대값은 완화하지
않았다.

## 결과와 검증

- raw:
  `tools/litertlm_benchmark/results/gemma4-staged-intent-e-64-final.jsonl`
- report:
  `tools/litertlm_benchmark/reports/gemma4-staged-intent-e-64-final_report.md`
- CSV:
  `tools/litertlm_benchmark/reports/gemma4-staged-intent-e-64-final_evaluation.csv`
- body ratings:
  `tools/litertlm_benchmark/reports/gemma4-staged-intent-e-64-final_body_ratings.json`

Python syntax/help/dry-run, 관련 Kotlin 테스트, 전체 JVM 테스트 57개와
`:app:assembleDebug`를 실행했다. Kotlin/JVM 테스트는 실패 0건이고 Android
debug build도 성공했다.
