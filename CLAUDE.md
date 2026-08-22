# HJP 개발 메모

HJP는 Android 앱 내부에서 실행되는 온디바이스 Single ReAct Agent다. 상세 설계의 기준 문서는 상위 디렉터리의 `HJP_Agent.md`다.

## 현재 production 기능

- `search_contacts`: ryeong 하이브리드 로컬 명함 검색
- `get_contact`: 안정적인 card ID 기반 단건 상세 조회
- `create_calendar_event`: Android 캘린더 작성 화면 열기
- `open_compose`: Android 메일/SMS 작성 화면 열기
- `update_business_card`: 로컬 명함 수정(확인 절차 필요)
- `get_current_datetime`: 현재 날짜·시각 조회

위 6개가 `AppContainer`의 `DefaultToolRegistry`에 실제로 등록된 전부다. 완료되지 않은 tool class를 production registry에 추가하지 않는다.

## 세션과 멀티턴 메모리

- 임시 세션 하나만 유지한다. `AgentSession.generation`이 교체를 표시하고, 이전 generation에서
  시작된 작업은 새 세션에 절대 기록하지 않는다. 과거 세션 저장·목록·복원은 만들지 않는다.
- 화면 transcript(`AgentSession.transcript`)와 모델 context는 분리한다. 모델 context 한도 때문에
  화면 기록을 잘라내지 않는다.
- `ConversationMemory`(schemaVersion 2)는 후속 턴에 필요한 투영만 담는다. `selectedContact`에는
  **이메일·전화번호를 넣지 않는다.** 실행 직전에 `card_id`로 `get_contact`를 다시 호출한다.
- 검색 결과가 0건이거나 2건 이상이면 `selectedContact`를 비운다. 동명이인은 질문한다.
- 실패한 턴은 transcript에 남기되 재개 가능한 pending으로 두지 않는다
  (`PENDING`/`NEEDS_CLARIFICATION`만 open).
- 실행에 **성공한** 도구만 이름으로 기록한다. tool payload는 저장하지 않는다.
- 모델 입력 순서는 `[session_state] → [relevant_history] → [recent_conversation] →
  [history_digest] → [current_user]`다. 메모리가 비면 섹션을 만들지 않아 첫 턴 프롬프트는
  단일턴과 바이트 단위로 동일해야 한다.
- native LiteRT `Conversation`은 실행 캐시다. 앱 메모리가 정본이며, 같은 이력을 매 턴 다시 넣지
  않는다(`ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP`).
- 컨텍스트 예산은 **파일명이 아니라 artifact 바이트로 결정한다**(`ModelDeploymentResolver`).
  식별은 **크기 + SHA-256**이다. 크기가 우선이고 해시가 확증이다: 크기가 같고 해시가 다르면
  거부(`REJECTED_DIGEST_MISMATCH`)하고, 해시가 맞아도 크기가 다르면 식별하지 않는다.
  `gemma-4-E2B-it`(2,588,147,712 B, SHA-256 `1819…a63c`)는 앱이 정한 안전 예산 3,072,
  legacy artifact(284,426,240 B)는 metadata가 선언한 1,024, 미식별 artifact는 1,024로 떨어진다.
  Gemma 4 artifact 자체는 context 한도를 선언하지 않으므로 3,072을 "모델 최대 한도"라고 쓰지 않는다.
- context admission은 **reserve를 포함한 하나의 식**이다(`ContextBudget.admit`).
  `raw + next_request_reserve(384) + output_reserve(256) + safety_margin(128) <= budget`.
  raw만 예산과 비교하지 않는다. 판정 지점은 요청이 실제로 prefill되는 순간(rotation 적용 후)이며,
  턴 종료 후 누적값에는 이미 모델 답변이 포함돼 있어 admission 기준이 아니다.
- 토큰 수는 앱 측 추정기가 native 입력을 재현한 **추정치**다. LiteRT tokenizer 판독이 아니다.
- 모델 세션 생성 전에 `ContextPreflight`가
  `system + tool catalog + 최소 입력 + output reserve + safety margin ≤ 한도`를 검사한다.
  성립하지 않으면 조용히 자르지 말고 진단 오류를 반환한다.
- 관련 이력은 개별 메시지가 아니라 **완결된 turn 단위**로 고르고 transcript 순서로 렌더링한다.
  `relevant_history`와 `recent_conversation`은 같은 turn을 공유하지 않는다.
- `새 대화` 이후 이전 generation의 도구는 실행하지 않는다(`TurnLease`). side-effect 도구는
  한 턴에 한 번만 실행한다(`SideEffectGuard`).
- reference 해소의 단일 권한은 `DeterministicTurnRouter`다. `ContactTurnReferenceResolver`는
  deprecated이며 커널에 연결하지 않는다.
- 기본 커널은 `AgentKernelMode.REACT`다. `STRUCTURED`는 debug 빌드에서만 선택할 수 있고,
  전환 gate는 `docs/AGENT_COMPLETION_REPORT.md` §9에 있다.
- 기준 문서: `docs/AGENT_PRE_DEVICE_COMPLETION_REPORT.md`.
  배경: `docs/AGENT_PRE_DEVICE_HARDENING_REPORT.md`, `docs/AGENT_COMPLETION_REPORT.md`,
  `docs/AGENT_MULTITURN_MEMORY_ANALYSIS.md`.

## 의존성 원칙

- `agent-core`는 Android, LiteRT-LM, ryeong, 구체 plugin을 import하지 않는다.
- Tool 구현은 `tool-contract`의 versioned `ToolPlugin` API를 따른다.
- 모델 도구 목록은 `DefaultToolRegistry` snapshot에서 동적으로 만든다.
- 동일 contract backend 교체는 `AppContainer` binding만 변경한다.
- 캘린더 저장과 메시지 전송 완료를 주장하지 않는다. 외부 작성 화면을 열었다고만 보고한다.
- 대화, raw tool result, 명함 PII를 로그나 영구 session에 저장하지 않는다.

## 주요 경로

- `agent-core/`: Registry, Policy, Executor, in-memory session, ReAct loop
- `tool-contact/`: 명함 repository/search port와 ryeong adapter
- `tool-android-intents/`: Calendar/Message port와 Android adapter
- `llm-litert/`: native tool calling LiteRT-LM adapter
- `app/src/main/java/com/example/hjp/AppContainer.kt`: 유일한 production composition root
- `app/src/main/assets/cards/business_cards.json`: 현재 명함 fixture

## 검증

JDK 21과 Android SDK 36.1 환경에서 다음을 실행한다.

```bash
./gradlew test
./gradlew :app:assembleDebug
```

모델 없는 lifecycle variant로 에뮬레이터 수명 검증을 실행한다. 모델 asset은 `src/main`이 아니라
`src/modelAssets`에 있고 debug·release만 참조하므로, lifecycle APK에는 모델이 0개 들어간다.

```bash
./gradlew -PhjpLifecycleTests :app:connectedLifecycleAndroidTest
```

**정규식은 Android에서 다시 검증한다.** desktop JVM이 받아들이는 패턴을 Android가 거부할 수 있고,
정책 정규식은 companion object에서 컴파일되므로 첫 턴 전체가 죽는다.
`RegexPortabilityInstrumentedTest`가 모든 workflow·router 패턴을 기기에서 컴파일한다.

`.litertlm` 모델은 앱 전용 외부 files 디렉터리의 `models/hjp-agent.litertlm`에 배치한다. 모델은 native tool calling을 지원해야 한다.
