# HJP 개발 기준

HJP는 Android 앱 내부에서 동작하는 온디바이스 Single ReAct Agent다. 현재 구조와 동작은 `README.md`, 상세 분석은 `CURRENT_AGENT_ANALYSIS.md`를 기준으로 한다.

## Production 기능

- `search_contacts`: 로컬 명함 hybrid 검색
- `get_contact`: card ID 기반 상세 조회
- `update_business_card`: confirmation 후 Room 명함 수정
- `create_calendar_event`: Android 캘린더 작성 화면 열기
- `open_compose`: Android 메일/SMS 작성 화면 열기
- `get_current_datetime`: 기기 또는 지정 timezone의 현재 시각 조회

한 decision에는 tool call 1개, 한 turn에는 최대 5개를 허용한다.

## 구현 원칙

- `AppContainer`를 production composition root로 유지한다.
- `agent-core`에서 Android, LiteRT-LM, Room, 검색 backend, 구체 plugin을 import하지 않는다.
- tool은 `tool-contract`의 versioned `ToolContract`와 `TypedToolPlugin` API를 따른다.
- 모델 tool catalog는 `DefaultToolRegistry` snapshot에서 동적으로 만든다.
- 같은 contract의 backend 교체는 implementation ID와 binding만 바꾼다.
- 명함 수정은 사용자 확인 전에 실행하지 않는다.
- 캘린더 저장과 메시지 전송 완료를 주장하지 않는다.
- 검색 결과에는 전화번호와 이메일을 포함하지 않는다.
- 대화와 raw tool result를 disk나 Android backup에 저장하지 않는다.
- Emulator 지원 기능을 바꾸면 `LocalToolRoutingModelGateway`와 test도 함께 갱신한다.

## 검증

JDK 21과 Android SDK 36.1 환경에서 다음을 실행한다.

```bash
./gradlew test :app:assembleDebug
```

기기가 준비된 경우 다음도 실행한다.

```bash
./gradlew :app:connectedDebugAndroidTest
```

모델은 앱 전용 외부 files 디렉터리의 `models/hjp-agent.litertlm`에 배치한다. APK에는 포함하지 않는다.
