# coverage matrix — Ryeong compatibility vs Production Agent Multiturn V4

Ryeong 축이 검사하지 않는 행은 **`NOT COVERED`**다. PASS로 표시하지 않는다.
Ryeong compatibility evaluator 때문에 기존 production 평가 계약을 약화하지 않았다.

| 계약 | Ryeong compatibility | Production Agent V4 |
|---|---|---|
| search routing | **부분** — `search`/`followup`만 채점, 나머지 6개 route는 `NOT_SCORABLE` | COVERED (`TurnSpec.expectedAct`, `DialogueAct` 15값) |
| follow-up | **부분** — `followup` → `CONTACT_DETAIL`/`CONTACT_SELECTION` | COVERED |
| focus | COVERED — `selectedContact.cardId` 관측 | COVERED (`expectedSelectedCardId`, `expectNoSelectedContact`) |
| JGA | **NOT COVERED** — 관측 seam 없음 | COVERED (`expectedArgs`) |
| R@5 | COVERED — production backend ranking 관측 | **NOT COVERED** — V4는 순위가 아니라 도구·결과 계약을 본다 |
| memory | **NOT COVERED** — upstream이 `conversation_memory`를 불투명하게 왕복만 시킨다 | COVERED (`ConversationMemory` 9필드) |
| target provenance | **NOT COVERED** | COVERED (`MemoryProvenance`, fail-closed) |
| stale ID | **NOT COVERED** | COVERED (실행 직전 `get_contact` 재조회) |
| clarification | **NOT COVERED** — `abstain`이 1:다라 제외 | COVERED (`CLARIFICATION_REQUIRED`) |
| get_contact | **NOT COVERED** — 채점 대상 아님(추적만) | COVERED |
| compose | **NOT COVERED** — upstream에 개념 없음. 실행되면 `unexpected_side_effect_tool`로 위반 기록 | COVERED |
| calendar | **NOT COVERED** — 위와 같다 | COVERED |
| update | **NOT COVERED** — 위와 같다 | COVERED |
| side effect | **NOT COVERED (금지만 검사)** — 검색 턴에서 action tool 실행을 위반으로 셈 | COVERED (`expectedSideEffects`, `SideEffectGuard`) |
| typed outcome | **NOT COVERED** — 기록만 | COVERED (`TurnOutcomeType` 10값) |
| nested args | **NOT COVERED** | COVERED (`JsonArgumentComparator` 재귀 비교) |
| false completion | **NOT COVERED** | COVERED (열기 ≠ 발송/저장) |
| actual Gemma continuation | **NOT RUN** — 이번 작업에서 모델 미실행 | **NOT RUN** |
| new conversation reset | **부분** — 시나리오 간 완전 초기화만 검사 | COVERED (`resetBefore`, `TurnLease`) |

## 결론

Ryeong 축은 **검색·문맥 추적의 하위 평가**다. 19개 행 중 COVERED는 3개(focus, R@5, 그리고
search routing·follow-up의 부분 커버)뿐이고, 도구·outcome·메모리·provenance·side-effect는
전부 `NOT COVERED`다.

**Ryeong 130개 시나리오는 Production Agent Multiturn V4를 대신하지 않는다.**
두 축의 수치를 같은 표에 합치지 않는다.
