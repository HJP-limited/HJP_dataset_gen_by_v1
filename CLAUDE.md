# HJP 개발 메모

HJP는 Android 앱 내부에서 실행되는 온디바이스 Single ReAct Agent다. 상세 설계의 기준 문서는 상위 디렉터리의 `HJP_Agent.md`다.

## 현재 production 기능

- `search_contacts`: ryeong 하이브리드 로컬 명함 검색
- `get_contact`: 안정적인 card ID 기반 단건 상세 조회
- `update_business_card`: Room-backed local business card 수정
- `create_calendar_event`: Android 캘린더 작성 화면 열기
- `open_compose`: Android 메일/SMS 작성 화면 열기
- `get_current_datetime`: 기기 현재 날짜/시각 조회

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
- `llm-litert/`: Gemma 3 LiteRT-LM adapter
- `app/src/main/java/com/example/hjp/AppContainer.kt`: 유일한 production composition root
- `app/src/main/assets/cards/business_cards.json`: 현재 명함 fixture

## 검증

JDK 21과 Android SDK 36.1 환경에서 다음을 실행한다.

```bash
./gradlew test
./gradlew :app:assembleDebug
```

Gemma 3 1B IT Q4 `.litertlm` 모델은 앱 전용 외부 files 디렉터리의 `models/gemma3-1b-it-int4.litertlm`에 배치한다. 앱 기능 요청은 deterministic router가 먼저 `ModelToolCall`로 변환하므로 chat 모델이 native function-call 형식을 직접 선택하지 않아도 production 도구 경로가 동작한다.
