# `/chat` 계약 (양쪽 소스에서 재구성)

`POST http://127.0.0.1:8100/chat`, `Content-Type: application/json`, 클라이언트 timeout **600초**.
근거: `eval_multiturn.py:393-401`(요청), `hybrid_server.py:947/984/1073/1172/1296`(응답).

## Request — 6개 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| `question` | string | 이번 턴 사용자 발화 |
| `history` | array | `{q, a, filter_terms}`. dry-run에서는 답변이 없어 `a`를 `hybrid_top` 이름들로 채운다 — 안 그러면 "직전 답변 재사용" 분기가 평가에서만 안 타는 착시가 생긴다. `filter_terms`는 앱의 `AgentSession.KEY_LAST_FILTER_TERMS`와 같은 역할(원본 주석 명시) |
| `focus` | string \| null | 직전 응답의 `focus`를 이어서 보냄 |
| `prev_card_ids` | array\<string\> | 직전 응답의 `card_ids` |
| `conversation_memory` | object \| null | evaluator에게 **불투명** — 그대로 왕복만 함 |
| `dry_run` | boolean | true면 검색·라우팅만, 생성 없음 |

## Response — evaluator가 실제로 읽는 필드

| 필드 | 타입 | 쓰임 |
|---|---|---|
| `route` | string | 라우팅 정확도. 관측값 `search`/`followup`/`empty_result`/`abstain`/`blank` |
| `field_filters` | object | **JGA·슬롯 F1의 유일한 근거.** 읽는 키는 `name`/`title`/`location` |
| `card_ids` | array\<string\> | 턴별 R@5 — `card_ids[:5]` |
| `focus` | string \| null | 시나리오가 `focus` 슬롯을 명시할 때만 비교, 아니면 다음 턴으로 전달 |
| `abstained` | boolean | 체크리스트 버킷(LLM/결정적) 결정 |
| `answer` | string | `must`/`must_not` 검사 |
| `hybrid_top` | array\<string\> | dry-run history의 assistant 자리 채움 |
| `gen_ms` | number | `--latency`에서만. **데스크톱 성능이며 실기기 지연 아님** |
| `conversation_memory` | object | 다음 요청으로 되돌림 |

읽지 않는 필드: `error`, `resolved_query`, `changed`, `keyword_top`, `semantic_top`,
`kw_ms`, `sem_ms`, `identifier_routed`, `filtered_out`, `conversational_followup`,
`history`, `cards`.

## dry-run vs generation

| | dry-run (기본) | `--generate` |
|---|---|---|
| `answer` | 빈 문자열 | 생성된 텍스트 |
| `gen_ms` | 0.0 | 실제 값 |
| LLM 서버 | 접촉 안 함 | 9379 필요 |

## 오류 응답

별도 error envelope이 없다. 빈 질문은 `route: "blank"`와 안내 문구를 담은
**정상 형태의 객체**로 돌아온다.

## 기계 판독용

`ENDPOINT_CONTRACT.json`에 request/response JSON Schema 상당 구조가 있다.
