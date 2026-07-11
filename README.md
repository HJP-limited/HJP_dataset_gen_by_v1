# HJP 온디바이스 명함 에이전트

HJP는 Android 기기 안에서 한국어 요청을 해석하고 로컬 도구를 조합해 실행하는 Single ReAct Agent다. 실제 단말에서는 LiteRT-LM native function calling 모델을 사용하고, Android Emulator에서는 native CPU crash를 피하기 위한 규칙 기반 호환 라우터를 사용한다. 두 경로 모두 동일한 `AgentKernel`의 도구 선택 검증, 사용자 확인, timeout, 실행, 결과 관찰 과정을 거친다.

서버 API나 원격 추론은 사용하지 않는다. 명함 데이터, 검색, 모델 추론은 기기 안에서 처리하며 캘린더·메일·문자는 Android 외부 작성 화면만 연다.

## 제공 기능

현재 `AppContainer`에 등록된 production tool은 6개다.

| 기능 | 모델 tool | 동작 | 사용자 확인 |
|---|---|---|---|
| 명함 검색 | `search_contacts` | 이름, 회사, 직함, 지역, 업종, 메모, 태그 기반 로컬 검색 | 없음 |
| 명함 상세 조회 | `get_contact` | 선택한 card ID의 전화번호, 이메일 등 조회 | 없음 |
| 명함 수정 | `update_business_card` | Room DB의 일부 필드 수정 또는 삭제 | 앱에서 실행 전 확인 |
| 일정 작성 | `create_calendar_event` | 캘린더 `ACTION_INSERT` 화면을 미리 채워서 열기 | 외부 앱에서 저장 확인 |
| 메일·문자 작성 | `open_compose` | `mailto:` 또는 `smsto:` 작성 화면 열기 | 외부 앱에서 전송 확인 |
| 현재 시각 조회 | `get_current_datetime` | 기기 또는 지정 IANA timezone의 날짜·시각 반환 | 없음 |

한 사용자 요청에서 tool은 한 번에 하나씩, 최대 5회 호출할 수 있다. 같은 tool과 같은 JSON arguments의 반복 호출, 같은 call ID의 중복 실행, catalog에 없는 tool 호출은 차단한다.

## 주요 처리 예시

```text
"오성령에게 내일 오후 2시 회의 일정 만들어 줘"
  1. get_current_datetime
  2. search_contacts
  3. get_contact
  4. create_calendar_event
  5. 캘린더 작성 화면에서 사용자가 저장
```

```text
"김지원 명함 메모를 VIP로 바꿔 줘"
  1. search_contacts
  2. get_contact
  3. 앱 confirmation
  4. update_business_card
```

검색 결과에는 ID, 이름, 회사, 직함, 지역만 포함한다. 전화번호와 이메일은 대상을 특정한 뒤 `get_contact`에서만 모델에 제공한다.

## 실행 구조

```text
Compose UI
  -> AgentViewModel
  -> AgentKernel
       -> DefaultToolRegistry
       -> AgentSessionManager
       -> AgentModelGateway
            실제 단말: LiteRtAgentModelGateway
            Emulator: LocalToolRoutingModelGateway
       -> DefaultToolPolicyEngine
       -> DefaultToolExecutor
       -> ToolPlugin
  -> AgentEvent
  -> UI
```

`agent-core`는 Android, LiteRT-LM, Room, 검색 구현, 구체 tool class에 의존하지 않는다. 모든 tool은 versioned `ToolContract`와 typed codec을 가지며 실제 backend는 plugin 뒤에 위치한다. 활성 contract가 바뀌면 model session을 다시 만들고, contract가 같은 backend 교체는 별도 binding revision으로 구분한다.

## 모듈

| 모듈 | 역할 |
|---|---|
| `app` | Compose UI, ViewModel, Room DB, asset seed, production wiring, emulator router |
| `agent-contract` | model gateway, decision, UI event 계약 |
| `agent-core` | registry, policy, executor, in-memory session, ReAct loop |
| `tool-contract` | tool contract, JSON codec, plugin, 오류와 실행 결과 계약 |
| `search-core` | Java 기반 lexical + feature-hashing hybrid 검색 |
| `tool-contact` | 명함 검색·조회·수정 plugin과 repository port |
| `tool-android-intents` | 캘린더·메일·문자 Android Intent backend |
| `tool-datetime` | 현재 날짜·시각 tool |
| `llm-litert` | LiteRT-LM native function-calling adapter |

## 모델 준비

모델은 APK에 포함되지 않는다. native tool calling을 지원하는 `.litertlm` 파일을 앱 전용 외부 files 디렉터리에 `hjp-agent.litertlm` 이름으로 배치한다.

```text
/storage/emulated/0/Android/data/com.example.hjp/files/models/hjp-agent.litertlm
```

앱은 실제 단말에서 파일이 없거나 읽을 수 없으면 입력을 비활성화하고 필요한 절대 경로를 화면에 표시한다. 현재 readiness 검사는 파일의 존재와 읽기 가능 여부만 확인하므로, 실제 배포 전 모델 출처·라이선스·SHA-256과 LiteRT 호환성을 별도로 관리해야 한다.

Android Emulator는 `ranchu`, `goldfish`, `sdk_gphone`, generic fingerprint를 감지하면 `.litertlm`을 열지 않고 `LocalToolRoutingModelGateway`를 사용한다. 이 라우터는 테스트용 LLM이 아니라 제한된 한국어 표현을 처리하는 결정적 parser다.

## 명함 데이터

초기 데이터는 [business_cards.json](app/src/main/assets/cards/business_cards.json)에 있다. 첫 실행 시 Room database `hjp-agent.db`가 비어 있을 때만 asset 2건을 seed하고, 이후 검색·조회·수정은 DB를 기준으로 한다.

검색 엔진은 별도 ML embedding model을 사용하지 않는다. 192차원 feature hashing, 2~3글자 n-gram, lexical field weight, 제한된 한국어 concept/synonym을 결합한다. 명함 수정 후 검색 cache를 폐기하여 다음 검색에서 DB를 다시 인덱싱한다.

DB는 Android backup과 device transfer에서 제외되며 app backup도 비활성화되어 있다. 대화와 raw tool result는 disk에 저장하지 않고 agent session은 process-local memory에만 유지한다.

## 안전 규칙

- 명함 수정처럼 `LOCAL_MUTATION`인 tool은 실행 전에 confirmation이 필요하다.
- `EXTERNAL_MUTATION`은 현재 policy가 거부한다.
- 캘린더 저장과 메시지 전송 완료를 주장하지 않는다.
- tool별 1~5초 timeout을 적용한다.
- backend exception은 stack trace 대신 안전한 구조화 오류로 모델과 UI에 전달한다.
- 한 decision의 여러 tool call, 동일 호출 반복, turn당 5회 초과를 차단한다.
- 외부 작성 화면을 열 때 수신자와 초안 내용은 해당 Android 앱에 Intent로 전달된다.

## 개발 환경

- JDK 21
- Android SDK 36.1
- min SDK 24 / target SDK 36
- Android Gradle Plugin 9.2.1
- Kotlin 2.2.10
- LiteRT-LM Android 0.13.1
- Room 2.8.3

## 빌드와 테스트

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew test :app:assembleDebug
```

debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

제품 단위 테스트는 kernel의 tool observation loop와 session reset, registry revision, 명함 PII 경계와 수정, 검색 false positive 방지, 현재 시각, emulator의 주요 multi-tool route를 검사한다.

Android 기기 또는 Emulator가 준비되어 있으면 계측 테스트를 별도로 실행한다.

```bash
./gradlew :app:connectedDebugAndroidTest
```

계측 테스트는 UI readiness, 입력 draft 복원, session reset, Android Intent resolver, 명함 요청 end-to-end 흐름을 확인한다.

AVD 생성부터 수동 기능 검증, `offline` 복구, 자동 계측 테스트까지의 전체 절차는 [ANDROID_STUDIO_EMULATOR_TEST_GUIDE.md](ANDROID_STUDIO_EMULATOR_TEST_GUIDE.md)를 참고한다.

## 설치 스크립트

APK와 모델을 승인된 단일 기기에 설치하려면 다음을 실행한다.

```bash
./scripts/install-debug-with-model.sh [model-path] [apk-path]
```

인자를 생략하면 각각 `models/hjp-agent.litertlm`, `app/build/outputs/apk/debug/app-debug.apk`를 사용한다. 스크립트는 APK 설치, model push, host/device SHA-256 검증, 앱 재시작을 수행한다.

## 현재 한계

- Emulator 호환 라우터와 실제 단말 LLM의 언어 이해 범위는 동일하지 않다.
- 실제 모델 binary의 provenance와 license는 저장소 코드만으로 확인할 수 없다.
- model readiness는 형식이나 hash를 검증하지 않는다.
- 최종 답변 API는 `Flow`지만 현재 adapter는 완성된 답변을 한 chunk로 반환한다.
- Room schema migration과 기존 DB에 대한 asset 재동기화 정책은 아직 없다.
- 실제 단말의 모델 정확도, 지연 시간, 메모리, 발열은 별도 실기기 검증이 필요하다.

구현 세부 사항, tool별 JSON schema, 정책과 알려진 위험은 [CURRENT_AGENT_ANALYSIS.md](CURRENT_AGENT_ANALYSIS.md)를 참고한다.
