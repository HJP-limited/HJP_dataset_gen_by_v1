# Agent 실패 귀속

평가 정의는 [AGENT_EVALUATION_METHODOLOGY.md](AGENT_EVALUATION_METHODOLOGY.md)를 따른다. A는 실제
E2E, B는 Stage 1 oracle, C는 Stage 1+2 oracle, D는 정상 tool result까지 주입한 oracle,
E는 실제 모델 출력의 잘못된 필드 하나만 교정한 반사실 평가다. `A=PASS(task)`는 모델
strict 실패가 있어도 최종 상태와 안전 정책은 맞았다는 뜻이다. B에서 호환 Stage 2가
아예 생성되지 않은 경우는 성공으로 추정하지 않고 `NE`로 둔다.

## 기존 64 baseline 실패 10개

| case ID | A | B | C | D | E | 주원인 | 적용 변경 | 최종 |
|---|---:|---:|---:|---:|---:|---|---|---:|
| `contact_calendar_chain_01` | FAIL | FAIL | PASS | PASS | PASS | MODEL_SLOT | 참석자 slot 원문 보존 validator + 1회 slot repair | PASS |
| `false_completion_guard_01` | FAIL | FAIL | PASS | PASS | PASS | MODEL_SLOT / MODEL_ACTION_REPAIR | recipient-facing goal projection; 완료 지시는 content에서 분리 | PASS |
| `calendar_absolute_no_datetime_02` | FAIL | FAIL | PASS | PASS | PASS | MODEL_SLOT | 원문 근거 calendar title canonicalization | PASS |
| `calendar_relative_tomorrow_02` | FAIL | FAIL | PASS | PASS | PASS | MODEL_SLOT | 동일 | PASS |
| `calendar_relative_next_friday_02` | FAIL | FAIL | PASS | PASS | PASS | MODEL_SLOT | 동일 | PASS |
| `unsupported_send_email_02` | FAIL | PASS | PASS | PASS | PASS | MODEL_ACTION | capability veto 후 intent 고정 action repair | PASS |
| `unsupported_send_sms_02` | FAIL | PASS | PASS | PASS | PASS | MODEL_ACTION | 동일 | PASS |
| `unsupported_delete_calendar_02` | FAIL | PASS | PASS | PASS | PASS | MODEL_ACTION | 동일 | PASS |
| `false_completion_calendar_02` | FAIL | FAIL | PASS | PASS | PASS | MODEL_SLOT | 원문 근거 calendar title canonicalization | PASS |
| `tool_error_compose_replan_02` | FAIL | FAIL | FAIL | PASS | PASS | MODEL_CONTENT | 원문 재전달·goal 검증·targeted body repair 3종 비교 | FAIL |

첫 9개는 oracle downstream이 통과했고 일반화 가능한 policy/validator 변경으로 회복됐다.
마지막 사례는 정상 tool-result oracle에서만 회복된다. 모델은 세 가지 body prompt/검증
변형에서도 본문에 `안내`를 넣지 않았고, validation의 다른 lexical-goal 사례에서도 같은
패턴이 재현됐다. parser/orchestrator는 정상이고 한 body 필드 교정으로 회복되므로
`MODEL_CONTENT`가 주원인이다. 특정 단어를 강제하는 추가 규칙은 일반화되지 않아 중단했다.

## 최종 validation strict 실패 5개

| case ID | A | B | C | D | E | 주원인 | 보조 원인 | 수정 가능성/중단 근거 |
|---|---:|---:|---:|---:|---:|---|---|---|
| `ae_val_007` | FAIL | PASS | PASS | PASS | PASS | MODEL_ACTION | SCHEMA_OR_PARSER | 유효 intent와 모순 action. generic·intent-fixed·one-field repair 3종 모두 task를 회복하지 못해 추가 prompt 중단 |
| `ae_val_012` | PASS(task) | PASS | PASS | PASS | PASS | SCHEMA_OR_PARSER | MODEL_INTENT | no-tool 안전 상태는 맞지만 intent-fixed repair가 preview intent를 복구하지 못함. repair arbitration 구조 문제 |
| `ae_val_014` | FAIL | FAIL | FAIL | PASS | PASS | MODEL_CONTENT | - | placeholder를 1회 지적해도 동일 출력 반복. body 전용 repair 개선 여지 있음 |
| `ae_val_021` | FAIL | FAIL | FAIL | PASS | PASS | MODEL_CONTENT | EVALUATOR_OR_EXPECTATION | 자연스러운 의미는 보존했지만 exact 키워드 `약속` 누락. 사람 평가 전에는 품질 실패로 확정하지 않음 |
| `ae_val_054` | PASS(task) | PASS | PASS | PASS | PASS | MODEL_INTENT | EVALUATOR_OR_EXPECTATION | upload를 UPDATE_CONTACT로 분류했으나 UNSUPPORTED와 no-tool end-state는 정확 |

## 최종 held-out strict 실패 8개

| case ID | A | B | C | D | E | 주원인 | 보조 원인 | 수정 가능성/중단 근거 |
|---|---:|---:|---:|---:|---:|---|---|---|
| `ae_hel_012` | PASS(task) | PASS | PASS | PASS | PASS | SCHEMA_OR_PARSER | MODEL_INTENT | validation과 동일한 preview 교차-field 패턴. held-out 확인 후에는 수정하지 않음 |
| `ae_hel_029` | FAIL | FAIL | FAIL | PASS | PASS | MODEL_CONTENT | - | 제공되지 않은 `오늘`을 두 번 생성해 validator가 안전 중단. body repair 개선 여지 있음 |
| `ae_hel_034` | FAIL | FAIL | PASS | PASS | PASS | MODEL_SLOT | EVALUATOR_OR_EXPECTATION | 참석자 slot 누락. 조사 `과`가 모음 끝 이름에 붙은 비문도 영향했으나 기대값은 변경하지 않음 |
| `ae_hel_037` | FAIL | FAIL | PASS | PASS | PASS | MODEL_SLOT | EVALUATOR_OR_EXPECTATION | 위와 동일 |
| `ae_hel_048` | PASS(task) | PASS | PASS | PASS | PASS | VALIDATOR_POLICY | MODEL_ACTION | `삭제/지워`는 막지만 `지우는 작업` 활용형이 capability matrix에서 누락. 실행은 최종 validator가 차단 |
| `ae_hel_052` | PASS(task) | PASS | PASS | PASS | PASS | VALIDATOR_POLICY | MODEL_ACTION | calendar 삭제 활용형 누락. 실행 0이지만 unsupported action strict 실패 |
| `ae_hel_054` | PASS(task) | PASS | PASS | PASS | PASS | MODEL_INTENT | EVALUATOR_OR_EXPECTATION | upload intent가 UPDATE_CONTACT이나 unsupported/no-tool 상태는 정확 |
| `ae_hel_065` | PASS(task) | NE | PASS | PASS | PASS | MODEL_SLOT | MODEL_ACTION_REPAIR | stale card ID slot이 안정화되지 않아 CLARIFY로 중단; stale 실행은 0 |

## 계층별 결론

- 모델 주원인: held-out에서 intent 1, slot 3, content 1. action 오류는 capability 구조 문제와
  겹친 2건이므로 모델 단독 실패로 과대계상하지 않는다.
- 구조 주원인: 활용형 capability coverage 2건, 교차-field repair arbitration 1건.
- 검색/세션/runtime 주원인: 0건. wrong-person 및 stale ID 실행도 0건이다.
- 평가/데이터 보조 원인: 비문 조사 2건, 의미는 맞지만 exact lexical match인 본문 1건,
  기능적으로 안전한 unsupported intent label 1건.
- 현재 모델 한계로 확정한 범위는 "본문의 특정 lexical goal을 repair 후에도 안정적으로
  보존하지 못하는 패턴"이다. preview/action과 삭제 활용형은 코드 구조 개선 여지가 있어
  모델 한계로 확정하지 않았다.
