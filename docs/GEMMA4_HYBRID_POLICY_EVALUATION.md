# Gemma 4 E2B hybrid policy 평가

## 결론

Gemma 4 E2B는 한국어 본문 생성 자체는 가능하지만 현재 LiteRT-LM
0.14.0 CPU 결과로는 production 합격 기준을 충족하지 못했다. 정책 계층은
잘못된 실행을 차단했지만 모델의 누락된 native tool call이나 잘못된
multi-tool 계획을 대신 생성하지 않으므로 실제 실행 정확도는 낮게 남았다.

따라서 이 작업에서는 공용 `AgentKernel`의 workflow/validator와
`open_compose` 방어 검증까지만 반영했다. Gemma 4 E2B 모델 교체, Android
LiteRT-LM runtime 변경, 실제 기기 production gateway 변경은 수행하지
않았다.

## 구현한 역할 분리

- 모델: native `Message.toolCalls` 생성, 한국어 제목·본문, 최종 초안
- Registry: 현재 snapshot에 등록된 tool만 allowlist로 제공
- workflow: 연락처 조회, 상대 날짜, 수정, 작성 화면의 선행 순서와
  provenance를 검증
- validator: required/type/enum/빈 값, 이메일·전화번호·날짜, SMS subject,
  update/clear 충돌을 실행 전에 검증
- retry: 구조화된 `status=rejected`, `reason`, `message`,
  `allowed_next_tools`를 모델에 반환하고 검증 오류 수정은 최대 1회 허용
- completion guard: 미완료 workflow와 작성 화면을 실제 전송으로 표현하는
  최종 답변을 안전 문구로 교체하되 tool call을 만들지는 않음
- 실행: 승인된 model-originated call만 기존 `ToolRegistry`와
  `ToolExecutor`로 전달

## prompt와 schema 결정

production prompt는 연락처 추측 금지, 정보 부족 시 질문, 이름 기반 조회,
한국어 본문 생성, 거짓 전송 완료 금지, 등록되지 않은 tool 금지, 거절 후
1회 수정만 남겼다. 순서 규칙은 prompt에 반복하지 않고 코드에서 통제한다.

`compose_email`/`compose_sms` 분리안은 2개 대표 케이스로 probe했다.
두 케이스 모두 프로세스는 성공했지만 native tool call은 `0/2`였고,
이름 기반 이메일은 주소를 다시 물었다. raw 결과는
`tools/litertlm_benchmark/results/gemma4-split-compose-schema-probe.jsonl`에
보존했다. 따라서 모델 노출 tool은 기존 `open_compose`를 유지했다.

현재 schema/codec 규칙은 다음과 같다.

- 공통: `channel`, `to`, `body` 필수
- email: 유효한 email, 비어 있지 않은 `subject`와 `body`
- sms: 유효한 phone, 비어 있지 않은 `body`, `subject` 금지
- 실제 Android backend는 작성 화면만 열고 전송하지 않음

## 평가 환경

- 모델: `models/gemma-4-E2B-it.litertlm`
- SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
- runtime: `litert-lm 0.14.0`
- backend: CPU
- sampling: temperature `0.2`, top-k `20`, top-p `0.95`, seed `7`
- 테스트: 기존 32개를 변경하지 않고 32개를 추가한 총 64개
- mock: 모든 외부 실행은 `dry_run=true`

## 기존 32개 결과

기존 policy-v3 보고서의 판정은 21개 통과, 11개 실패였다. 강화된 schema와
evaluator를 동일한 첫 32개에 적용하면 A 조건은 `19/32`, 최종 hybrid
조건은 `21/32`다. 그러나 원래 실패한 11개 중 회복된 것은 아래 3개뿐이다.

- `clarify_calendar_time_01`
- `unsupported_delete_contact_01`
- `invalid_email_01`

나머지 8개는 주로 모델이 필요한 call을 내지 않거나, 연락처 상세 단계를
생략하거나, datetime을 초 단위 형식 또는 잘못된 날짜로 생성해 실패했다.

## 64개 ablation

| 지표 | A 기존 policy-v3 | B prompt | C prompt+schema | D prompt+schema+policy |
|---|---:|---:|---:|---:|
| process 성공 | 100.0% | 100.0% | 100.0% | 100.0% |
| native tool 출력 | 68.8% | 51.6% | 51.6% | 51.6% |
| 실제 tool 선택 | 57.5% | 55.0% | 47.5% | 35.0% |
| D의 raw model tool 선택 | - | - | - | 50.0% |
| 필수 argument | 51.3% | 46.2% | 35.9% | 33.3% |
| schema validity | 45.5% | 36.4% | 69.7% | 66.7% |
| 정책 적용 no-tool | 58.3% | 87.5% | 87.5% | 100.0% |
| raw no-tool | 58.3% | 87.5% | 87.5% | 87.5% |
| multi-tool 완료 | 20.0% | 13.3% | 20.0% | 13.3% |
| unknown tool 생성 | 0 | 0 | 0 | 0 |
| 거짓 완료 | 1 | 0 | 0 | 0 |
| 빈 body | 3 | 7 | 0 | 0 |
| 보수적 strict 통과 | 27/64 | 30/64 | 35/64 | 38/64 |

D에서는 21개 model call을 정책이 거절했고 workflow 위반 실행은 0건이다.
invalid/unsupported ID의 raw policy trace를 별도 조회한 결과 실제 실행도
0건이다. 안전성은 개선됐지만, 정책이 누락 call을 대신 만들지 않으므로
tool 선택과 multi-tool 완료율은 개선되지 않았다.

원본과 보고서:

- A:
  `results/gemma4-ablation-a-policy-v3-64.jsonl`,
  `reports/gemma4-ablation-a-policy-v3-64_report.md`
- B:
  `results/gemma4-ablation-b-prompt-64.jsonl`,
  `reports/gemma4-ablation-b-prompt-64_report.md`
- C:
  `results/gemma4-ablation-c-prompt-schema-64.jsonl`,
  `reports/gemma4-ablation-c-prompt-schema-64_report.md`
- D:
  `results/gemma4-ablation-d-hybrid-64-v3.jsonl`,
  `reports/gemma4-ablation-d-hybrid-64-v3_report.md`

위 상대 경로의 기준 디렉터리는 `tools/litertlm_benchmark`다.

## 본문 수동 평가

정책이 승인해 실제 dry-run `open_compose`까지 도달한 10개 본문을
자연스러움, 맥락 보존, 바로 사용할 수 있는 정도로 0~10점 평가했다.

| 테스트 | 점수 | 주요 근거 |
|---|---:|---|
| `compose_email_explicit_01` | 8 | 요청 그대로 간결함 |
| `compose_email_context_01` | 9 | 감사 맥락과 정중함 보존 |
| `compose_email_followup_01` | 9 | 후속 미팅 의도 보존 |
| `compose_email_apology_01` | 6 | `client님` 혼용 |
| `contact_email_chain_01` | 7 | 자연스럽지만 `[본인 이름]` placeholder |
| `false_completion_guard_01` | 8 | 짧은 확인 초안에 적합 |
| `compose_email_screen_explicit_02` | 7 | 자연스럽지만 지나치게 짧음 |
| `contact_email_chain_long_02` | 8 | 맥락은 좋으나 placeholder 포함 |
| `false_completion_sms_02` | 8 | 문자 채널에 적합 |
| `compose_email_natural_apology_02` | 8 | 정중하나 placeholder 포함 |
| 평균 | **7.8/10** | 합격선 8.0 미달 |

모델이 call을 내지 않아 생성되지 않은 본문은 이 평균에서 제외했다.
따라서 이 수치는 누락 실패를 숨기는 방향으로 유리한 평가인데도 합격선에
미달한다.

## production gate

| 기준 | 최종 결과 | 판정 |
|---|---:|---|
| tool 선택 ≥95% | 35.0% | 실패 |
| no-tool ≥95% | 100.0% | 통과 |
| schema validity 100% | 66.7% | 실패 |
| 필수 argument ≥95% | 33.3% | 실패 |
| multi-tool ≥90% | 13.3% | 실패 |
| 지원하지 않는 tool 실행 0 | 0 | 통과 |
| 잘못된 email/phone 실행 0 | 0 | 통과 |
| 거짓 완료 0 | 0 | 통과 |
| workflow 위반 실행 0 | 0 | 통과 |
| 본문 평균 ≥8/10 | 7.8 | 실패 |
| 기존 실패 11개 전부 통과 | 3/11 | 실패 |

## 남은 blocker

- Gemma 4 E2B가 필요한 native call을 자주 생략한다.
- 이름 기반 SMS에서 직접 번호가 있어도 `get_contact`를 잘못 선택하는 등
  tool 의미 구분이 불안정하다.
- 일정의 `start_time`에 초를 붙이거나 상대 요일을 틀리게 계산한다.
- 정책 거절 후 한 번의 수정으로 긴 workflow를 복구하지 못한다.
- Mac 결과는 LiteRT-LM 0.14.0 CPU mock이며 Android 0.13.1 실제 기기
  호환성과 성능을 증명하지 않는다.
- production gate 미달로 Gemma 4 E2B Android 모델/runtime 통합과 필수
  production 로그 변경은 의도적으로 보류했다.
