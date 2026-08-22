# Gemma 4 E2B 기존 intent 추출 실패 분석

## 분석 기준

- 원본: `gemma4-intent-workflow-orchestrator-policy-v2-64.jsonl`
- 평가 기준: 기존 `test_cases.jsonl`의 64개 기대값을 변경하지 않음
- 기존 결과: intent 34/64, execute 34/64, workflow 30/40
- 재검산: intent/action/recipient/date/content slot을 함께 보면 31개 case에
  명시적 intent 계층 오류가 있고, 성공 plan 중 3개는 `content_goal`이
  비어 있었다.

기존 wire schema는 `intent`, `execute`, recipient, content, date, update를
한 호출에 평탄화했다. 실패 28건은 native `submit_intent` 자체가 없었던
것이 아니다. 모델이 native call을 냈지만 schema/policy가 거부했고, 전체
객체 repair에서도 같은 오류가 반복돼 최종 plan이 없어진 사례다.

대표 원문:

```text
current_datetime_01
실제: submit_intent(GET_CURRENT_DATETIME, execute=true, recipient_type=NONE)
거부: missing:recipient_value, invalid:recipient_value
repair: 같은 누락 반복
실행 sequence: 없음

clarify_email_recipient_01
실제: submit_intent(COMPOSE_EMAIL, execute=true, recipient_type=NONE, recipient_value="")
거부: contradiction:compose_recipient_required
repair: 같은 execute/recipient 모순 반복
실행 sequence: 없음

unsupported_delete_contact_01
실제: submit_intent(UPDATE_CONTACT, execute=true, update_field=name, update_value=삭제)
거부: contradiction:unsupported_request_must_be_unsupported
repair: 같은 잘못된 update 반복
실행 sequence: 없음
```

## 유형별 분류

유형은 중복 집계한다. 예를 들어 invalid email은 execute 오류이면서
recipient 값 오류이고, 최종적으로 구조화 plan도 누락됐다.

| 유형 | 건수 | 테스트 ID | 실제 출력/sequence와 원인 | 개선 계층 |
|---|---:|---|---|---|
| intent 오분류 | 2 | `clarify_update_field_01`, `invalid_email_01` | 각각 `UPDATE_CONTACT/true`와 `COMPOSE_EMAIL/true`; tool 실행은 validator가 차단 | Stage 1 prompt/schema |
| 구조화 출력 자체 누락 | 28 | `current_datetime_01`, `clarify_email_recipient_01`, `clarify_sms_recipient_01`, `clarify_calendar_time_01`, `no_tool_greeting_01`, unsupported 7건, invalid format 4건, 연락처 fixture/error 9건, no-tool 3건 | native call은 있었지만 monolithic required/모순 검증 거부 후 accepted plan 없음; sequence 없음 | schema 분리 |
| execute 누락 또는 오류 | 18 | clarify 4건, unsupported 7건, invalid address/phone 5건, `no_tool_greeting_01`, `invalid_email_01` | `execute=true`를 고수하거나 false intent를 만들지 못함 | Stage 1 action |
| recipient 유형·값 누락 | 12 | 수신자 없는 compose 2건, invalid address/phone 5건, `missing_contact_email_01`, `missing_contact_sms_01`, `missing_contact_calendar_02`, `tool_error_search_replan_02`, `tool_error_get_replan_02` | `NONE`, 잘못된 EMAIL/PHONE, 이름을 `없음`으로 변환, 일정 참석자 누락 | intent별 Stage 2 |
| 날짜·시간 표현 누락 | 2 | `clarify_calendar_time_01`, `calendar_missing_time_relative_02` | 시각이 없는 요청을 CLARIFY로 바꾸지 못하고 plan 없음 | Stage 1 action + calendar slots |
| content goal 손실 | 3 | `compose_email_explicit_01`, `compose_sms_explicit_01`, `compose_email_screen_explicit_02` | compose intent는 성공했지만 `content_goal` 없음; 본문 모델은 원문에 의존 | compose Stage 2 |
| `ANSWER_ONLY` 실패 | 4 | `no_tool_greeting_01`, `no_tool_email_example_02`, `no_tool_name_meaning_02`, `no_tool_draft_preview_02` | execute 모순 또는 monolithic 필드 누락으로 plan 없음; sequence 없음 | Stage 1 |
| `CLARIFY` 실패 | 9 | `clarify_email_recipient_01`, `clarify_sms_recipient_01`, `clarify_calendar_time_01`, `clarify_update_field_01`, `invalid_email_01`, `invalid_email_missing_domain_02`, `invalid_email_space_02`, `invalid_phone_short_02`, `invalid_phone_letters_02` | 실행 intent를 유지하거나 주소를 보정/환각; validator가 실행 차단 | Stage 1 + stage repair |
| `UNSUPPORTED` 실패 | 7 | `unsupported_delete_contact_01`, `unsupported_phone_call_01`, `unsupported_weather_01`, `unsupported_send_email_02`, `unsupported_send_sms_02`, `unsupported_delete_calendar_02`, `unsupported_delete_contact_02` | update/compose/calendar 실행 intent 또는 invalid plan; 정책 차단으로 sequence 없음 | Stage 1 |
| repair 후에도 복구 실패 | 28 | 구조화 출력 누락 28건과 동일 | 전체 객체를 다시 요구해 같은 recipient/execute 모순 반복 | 단계별 repair |
| evaluator 오류 가능성 | 1 | `contact_calendar_chain_01` | 실제 chain은 성공했으나 title `후속 상담 일정`과 기대 `후속 상담`을 exact 비교. 기대값은 유지하되 intent 오류와 구분 필요 | evaluator 표시 |

## 사례별 실패 상세

| 테스트 ID | 기대 | 실제 plan 또는 native 출력 요약 | 실제 tool sequence | 주원인 |
|---|---|---|---|---|
| `current_datetime_01` | datetime/execute | `recipient_value` 누락, repair 동일 | 없음 | monolithic required |
| `clarify_email_recipient_01` | compose email/clarify | compose/execute/NONE 반복 | 없음 | action |
| `clarify_sms_recipient_01` | compose sms/clarify | compose/execute/NONE 반복 | 없음 | action |
| `clarify_calendar_time_01` | calendar/clarify | calendar required slot 누락 | 없음 | action/time |
| `clarify_update_field_01` | update/clarify | update/execute, 임의 `name=명함 정보 수정 요청` | 없음 | intent/slot 환각 차단 |
| `no_tool_greeting_01` | general/answer | ANSWER_ONLY인데 execute=true | 없음 | action |
| `unsupported_delete_contact_01` | update/unsupported | `name=삭제` update | 없음 | unsupported |
| `unsupported_phone_call_01` | general/unsupported | invalid intent 객체 | 없음 | unsupported |
| `unsupported_weather_01` | general/unsupported | invalid intent 객체 | 없음 | unsupported |
| `invalid_email_01` | email/clarify | 없는 주소를 `test-at-example@example.com`로 보정 | 없음 | recipient 환각 |
| `missing_contact_email_01` | email/execute | 이름을 EMAIL `없음`으로 변환 | 없음 | recipient type |
| `missing_contact_sms_01` | sms/execute | 연락처 이름 slot을 제출하지 못함 | 없음 | recipient |
| `no_tool_email_example_02` | general/preview | accepted plan 없음 | 없음 | answer/preview |
| `no_tool_name_meaning_02` | general/answer | accepted plan 없음 | 없음 | answer |
| `no_tool_draft_preview_02` | general/preview | accepted plan 없음 | 없음 | preview |
| `duplicate_contact_email_02` | email/execute | accepted plan 없음 | 없음 | recipient |
| `duplicate_contact_sms_02` | sms/execute | accepted plan 없음 | 없음 | recipient |
| `contact_missing_email_02` | email/execute | accepted plan 없음 | 없음 | recipient |
| `contact_missing_phone_02` | sms/execute | accepted plan 없음 | 없음 | recipient |
| `missing_contact_calendar_02` | calendar/execute | recipient NONE으로 사람 이름 누락 | `create_calendar_event` | attendee slot |
| `invalid_email_missing_domain_02` | email/clarify | EMAIL `user@` 반복 | 없음 | format/action |
| `invalid_email_space_02` | email/clarify | invalid email/recipient 반복 | 없음 | format/action |
| `invalid_phone_short_02` | sms/clarify | invalid phone 반복 | 없음 | format/action |
| `invalid_phone_letters_02` | sms/clarify | invalid phone 반복 | 없음 | format/action |
| `calendar_missing_time_relative_02` | calendar/clarify | required slot 누락 | 없음 | action/time |
| `unsupported_send_email_02` | email/unsupported | invalid 실행 intent | 없음 | unsupported |
| `unsupported_send_sms_02` | sms/unsupported | invalid 실행 intent | 없음 | unsupported |
| `unsupported_delete_calendar_02` | calendar/unsupported | invalid 실행 intent | 없음 | unsupported |
| `unsupported_delete_contact_02` | update/unsupported | invalid 실행 intent | 없음 | unsupported |
| `tool_error_search_replan_02` | email/execute | contact name plan 없음 | 없음 | recipient |
| `tool_error_get_replan_02` | sms/execute | contact name plan 없음 | 없음 | recipient |

## 결론

주원인은 LiteRT native tool-call 부재가 아니라, 작은 모델에 서로 다른
의미 판단과 모든 optional/required slot을 한 번에 요구한 평탄화 schema다.
따라서 기존 orchestrator/validator를 바꾸지 않고 `intent+action`과
intent별 slot을 분리하며, 실패한 단계만 한 번 repair하는 것이 개선
대상이다.
