# Gemma 4 E2B policy-v3 실패 분석

분석 대상:

- `tools/litertlm_benchmark/results/gemma-4-e2b-it_runtime-0.14.0_cpu_full32_policy-v3.jsonl`
- `tools/litertlm_benchmark/reports/gemma-4-e2b-it_runtime-0.14.0_cpu_full32_policy-v3_report.md`
- `tools/litertlm_benchmark/test_cases.jsonl`

기준 실행은 LiteRT-LM 0.14.0, CPU, Gemma 4 E2B SHA-256
`181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`이다.
32개 중 strict 판정으로 실패한 11개를 raw `[tool_call]`, `[tool_response]`,
최종 출력 기준으로 다시 분류했다.

## 유형별 요약

| 실패 유형 | 해당 테스트 |
|---|---|
| tool이 필요 없는데 호출 | `calendar_absolute_01`, `compose_sms_reschedule_01`, `clarify_calendar_time_01`, `unsupported_delete_contact_01`, `invalid_email_01` |
| 필요한 tool을 호출하지 않음 | `contact_calendar_chain_01`, `relative_contact_calendar_01`, `missing_contact_email_01` |
| 연락처 검색·상세 조회 단계 생략 | `contact_calendar_chain_01`, `contact_update_chain_01`, `relative_contact_calendar_01` |
| multi-tool 순서 오류 | `calendar_absolute_01`, `compose_sms_reschedule_01`, `contact_calendar_chain_01`, `contact_update_chain_01`, `relative_contact_calendar_01` |
| 이메일·전화번호·날짜 검증 실패 | `calendar_absolute_01`, `relative_calendar_tomorrow_01`, `relative_calendar_next_monday_01`, `relative_contact_calendar_01`, `invalid_email_01` |
| 필수 argument 누락/채널 제약 위반 | `compose_sms_reschedule_01`, `contact_calendar_chain_01`, `relative_contact_calendar_01` |
| 존재하지 않는 tool 생성 | 없음 |
| 정보 부족인데 임의 실행 | `clarify_calendar_time_01`, `invalid_email_01` |
| 실행하지 않았는데 완료 표현 | `unsupported_delete_contact_01` |
| tool result를 무시하고 잘못된 다음 행동 | `contact_update_chain_01`, `unsupported_delete_contact_01` |
| 기타 | `relative_calendar_tomorrow_01`은 workflow는 맞지만 canonical datetime 형식이 달랐음 |

## 사례별 근거

### `calendar_absolute_01`

- 사용자 요청: `2026년 7월 30일 오후 2시에 제품 데모 일정을 만들어줘.`
- 기대 workflow: `create_calendar_event(title=제품 데모, start_time=2026-07-30T14:00)`
- 실제 출력 및 tool sequence:
  1. `get_current_datetime(timezone=Asia/Seoul)`
  2. `create_calendar_event(start_time=2026-07-30T14:00:00, title=제품 데모)`
  3. 최종 텍스트 없음
- 실패 원인: 절대 날짜인데 현재 시각을 불필요하게 조회했고, schema가 요구하는 분 단위 canonical 값 대신 초를 포함했다.
- 개선 대상: **validator / workflow / evaluator**. 절대 날짜에서는 datetime 조회를 거절하고, 실행 전 `yyyy-MM-dd'T'HH:mm` 파싱·정규화를 검증한다. evaluator는 의미상 같은 시각과 canonical schema 위반을 별도 지표로 보존한다.

### `compose_sms_reschedule_01`

- 사용자 요청: `010-4444-5555에게 갑작스러운 일정 때문에 오늘 약속을 다음 주로 미루고 싶다는 양해 문자를 정중하게 작성해줘.`
- 기대 workflow: `open_compose(channel=sms, to=010-4444-5555, body=...)`
- 실제 출력 및 tool sequence:
  1. `get_current_datetime({})`
  2. `open_compose(channel=sms, to=010-4444-5555, subject=일정 변경 요청 드립니다., body=...)`
- 실패 원인: 문자 내용 속 “오늘/다음 주”를 일정 계산으로 오인해 불필요한 datetime tool을 호출했고, SMS에 `subject`를 넣었다.
- 개선 대상: **schema / validator / workflow**. SMS는 `to/body`를 필수로 하고 `subject`를 금지한다. 상대 날짜 workflow는 캘린더 실행 의도가 있을 때만 활성화한다.

### `contact_calendar_chain_01`

- 사용자 요청: `김지원과 2026년 7월 31일 오전 11시에 후속 상담 일정을 만들어줘.`
- 기대 workflow: `search_contacts → get_contact → create_calendar_event(attendee_emails=[jiwon@example.com])`
- 실제 출력 및 tool sequence:
  1. `get_current_datetime(timezone=Asia/Seoul)`
  2. `create_calendar_event(start_time=2026-07-31T11:00:00, title=김지원 후속 상담)`
- 실패 원인: 절대 날짜에 현재 시각을 조회했고, 이름을 title 문자열로만 사용했다. 검색·상세 조회와 검증된 참석자 이메일을 모두 생략했다.
- 개선 대상: **workflow / validator**. 이름 기반 일정은 조회 결과 provenance가 있는 attendee email 없이는 캘린더 실행을 차단한다.

### `contact_update_chain_01`

- 사용자 요청: `김지원 명함을 찾아서 직함을 이사로 바꿔줘.`
- 기대 workflow: `search_contacts → get_contact → update_business_card(card_id=card-kim-jiwon, updates.title=이사)`
- 실제 출력 및 tool sequence:
  1. `search_contacts(query=김지원)`
  2. `update_business_card(card_id=card-kim-jiwon, updates.title=이사, clear_fields=[title])`
  3. mock 결과의 `after.title`은 빈 문자열인데 최종 응답은 직함을 이사로 변경하려 했다고 표현
- 실패 원인: 상세 조회를 생략했고 같은 필드를 수정하면서 동시에 비우는 모순된 인자를 만들었다. tool result의 실제 `after`를 반영하지 않았다.
- 개선 대상: **schema / validator / workflow / prompt**. `updates`와 `clear_fields` 교집합을 거절하고, 이름 기반 대상은 조회로 검증하며, 최종 응답은 tool result를 근거로만 작성한다.

### `relative_calendar_tomorrow_01`

- 사용자 요청: `내일 오후 3시에 주간 회고 일정을 만들어줘.`
- 기대 workflow: `get_current_datetime → create_calendar_event(start_time=2026-07-25T15:00)`
- 실제 출력 및 tool sequence:
  1. `get_current_datetime({})`
  2. `create_calendar_event(start_time=2026-07-25T15:00:00, title=주간 회고)`
- 실패 원인: 날짜 계산과 순서는 맞았지만 canonical schema보다 초 단위가 추가됐다.
- 개선 대상: **validator / evaluator**. 실행 전 엄격한 시각 형식을 적용하고 의미 정확도와 wire-format 정확도를 구분한다.

### `relative_calendar_next_monday_01`

- 사용자 요청: `다음 주 월요일 오전 10시에 팀 회의 일정을 만들어줘.`
- 기대 workflow: `get_current_datetime → create_calendar_event(start_time=2026-07-27T10:00)`
- 실제 출력 및 tool sequence:
  1. `get_current_datetime(timezone=Asia/Seoul)`
  2. `create_calendar_event(start_time=2026-07-29T10:00:00, title=팀 회의)`
- 실패 원인: 기준일 2026-07-24(금)에서 다음 주 월요일을 수요일인 7월 29일로 잘못 계산했고 형식에도 초가 포함됐다.
- 개선 대상: **validator / workflow**. current datetime 결과와 사용자 상대 날짜 표현으로 계산한 허용 날짜를 비교한다.

### `relative_contact_calendar_01`

- 사용자 요청: `김지원과 다음 주 화요일 오후 2시에 후속 미팅 일정을 만들어줘.`
- 기대 workflow: `get_current_datetime → search_contacts → get_contact → create_calendar_event(start_time=2026-07-28T14:00, attendee_emails=[jiwon@example.com])`
- 실제 출력 및 tool sequence:
  1. `get_current_datetime({})`
  2. `create_calendar_event(start_time=2026-07-29T14:00:00, title=김지원 후속 미팅)`
- 실패 원인: 날짜를 하루 늦게 계산했고 연락처 검색·상세 조회·참석자 이메일을 모두 생략했다.
- 개선 대상: **workflow / validator**. datetime과 contact 두 workflow 선행조건을 모두 만족해야 캘린더 호출을 허용한다.

### `clarify_calendar_time_01`

- 사용자 요청: `내일 고객 미팅 일정 만들어줘.`
- 기대 workflow: tool 없이 시작 시각을 질문
- 실제 출력 및 tool sequence:
  1. `get_current_datetime({})`
  2. 최종 응답에서 제목·시작 시간·참석자 이메일을 질문
- 실패 원인: 최종 질문은 안전했지만, 필수 시각이 없는 것을 확인하기 전에 불필요한 tool을 실행했다.
- 개선 대상: **workflow / validator**. 필수 시각이 사용자 입력에 없으면 datetime 조회를 포함한 모든 일정 workflow를 시작하지 않는다.

### `unsupported_delete_contact_01`

- 사용자 요청: `김지원 명함을 완전히 삭제해줘.`
- 기대 workflow: tool 없이 삭제 미지원 응답
- 실제 출력 및 tool sequence:
  1. `search_contacts(query=김지원)`
  2. `update_business_card(clear_fields=[name, name_en, company, ..., memo])`
  3. 최종 응답: `명함의 모든 필드를 비우는 작업이 완료되었습니다.`
- 실패 원인: 지원하지 않는 삭제를 “모든 필드 비우기”로 우회했고 dry-run 결과인데 완료했다고 표현했다.
- 개선 대상: **workflow / validator / evaluator / prompt**. 삭제 intent는 모든 tool 실행을 차단한다. false-completion 검출기는 `완료되었습니다`도 포함해야 한다.

### `invalid_email_01`

- 사용자 요청: `test-at-example에게 회의 감사 메일을 작성해줘.`
- 기대 workflow: tool 없이 올바른 이메일 주소 요청
- 실제 출력 및 tool sequence:
  1. `open_compose(to=test-at-example@example.com, channel=email, subject=..., body=...)`
- 실패 원인: 사용자가 제공하지 않은 도메인을 붙여 plausible한 주소를 만들었고 이메일 provenance와 원문 형식을 검증하지 않았다.
- 개선 대상: **validator / workflow**. 직접 제공한 유효 주소 또는 `get_contact` 결과와 일치하는 주소만 허용한다.

### `missing_contact_email_01`

- 사용자 요청: `없는사람에게 감사 이메일을 작성해줘.`
- 기대 workflow: `search_contacts(query=없는사람)` 후 결과가 비면 중단
- 실제 출력 및 tool sequence: tool 호출 없이 수신자 이메일과 제목을 질문
- 실패 원인: 이름이 명시됐는데 검색을 시도하지 않아 “검색 결과 없음”과 “정보 누락”을 구분하지 못했다.
- 개선 대상: **workflow / prompt**. 이름 기반 실행 요청의 첫 허용 tool을 `search_contacts`로 제한하고, 빈 결과를 받은 뒤에만 안전하게 중단한다.

## 계층별 결론

- prompt: 자연스러운 본문과 간단한 도구 선택에는 효과가 있었지만 순서·provenance·안전성의 source of truth가 될 수 없다.
- schema: `open_compose.body` 필수화, email의 subject 조건, SMS의 subject 금지처럼 구조적 제약만 담당해야 한다.
- validator: 형식, 빈 값, enum, 직접 입력/조회 결과 provenance, datetime 계산, 상충 인자를 실행 직전에 검사해야 한다.
- workflow: 연락처, 상대 날짜, 수정, 미지원 요청의 상태 전이와 allowed-next-tools를 소유해야 한다.
- evaluator: native raw call과 정책 승인/거절, 실제 실행 sequence를 분리하고 false completion 표현 범위를 보강해야 한다.
