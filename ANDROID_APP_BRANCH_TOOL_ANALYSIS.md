# android-app 브랜치 에이전트 도구 분석

## 분석 기준

- 원격 기준: `https://github.com/HJP-limited/sojung/tree/android-app`
- 임시 클론 경로: `/private/tmp/hjp-sojung-android-app-remote`
- 원격 커밋: `b18c489 Switch business card storage to Room`
- 현재 워크스페이스 기준 커밋: `993a5f5 Improve tool tester button layout`
- 현재 워크스페이스 상태: 원격 `android-app`의 단일 앱 구조가 아니라, `agent-core`, `tool-contract`, `tool-contact`, `tool-android-intents`, `llm-litert` 등이 추가된 멀티 모듈 에이전트 구조다. 다수 파일이 미커밋/신규 상태이므로, 이 문서는 “원격 브랜치 원본”과 “현재 작업 코드”를 분리해서 본다.

## 결론 요약

원격 `android-app` 브랜치는 실제 LLM 에이전트 루프가 아니라, 에이전트가 호출할 도구 계층을 단일 `app` 모듈 안에 구현한 테스트 앱이다. 핵심은 `AgentTool` 인터페이스, `ToolRegistry`, 그리고 4개 도구다.

- `create_calendar_event`: Android 캘린더 일정 작성 화면을 연다.
- `open_compose`: Android 메일/SMS 작성 화면을 연다.
- `update_business_card`: RoomDB에 저장된 명함 일부 필드를 수정하거나 비운다.
- `get_current_datetime`: 기기 또는 지정 timezone 기준 현재 날짜/시각을 반환한다.

현재 작업 코드는 이미 더 진전된 구조다. `ToolContract`, `TypedToolPlugin`, `DefaultToolRegistry`, `AgentKernel`, `LiteRtAgentModelGateway`, 에뮬레이터용 `LocalToolRoutingModelGateway`가 있고, 실제 프롬프트가 모델 또는 로컬 라우터를 거쳐 도구를 실행한다. 따라서 다음 구현에서는 원격 브랜치의 `ToolRegistry`를 그대로 가져오면 안 된다. 필요한 것은 원격 브랜치의 `update_business_card`, `get_current_datetime`, Room 기반 명함 저장소 설계를 현재의 typed plugin 구조로 이식하는 것이다.

## 원격 android-app 브랜치 구조

원격 브랜치는 단일 `app` 모듈만 포함한다.

```text
app/
  src/main/java/com/example/hjp/
    MainActivity.kt
    agent/tools/
      AgentTool.kt
      ToolRegistry.kt
      CreateCalendarEventTool.kt
      OpenComposeTool.kt
      UpdateBusinessCardTool.kt
      GetCurrentDateTimeTool.kt
    data/
      BusinessCard.kt
      BusinessCardEntity.kt
      BusinessCardDao.kt
      HjpDatabase.kt
      RoomBusinessCardStore.kt
```

Gradle 구성은 Room 사용을 위해 `ksp`, `androidx.room.runtime`, `androidx.room.ktx`, `androidx.room.compiler`를 추가한다. `settings.gradle.kts`에는 `:app`만 포함되어 있다.

## 원격 도구 공통 설계

### `AgentTool`

위치: `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/agent/tools/AgentTool.kt`

공통 인터페이스는 매우 단순하다.

- `name`: 모델이 호출할 도구 이름
- `declaration`: 모델 프롬프트에 넣을 JSON function declaration
- `execute(args: JSONObject): String`: 도구 실행 후 JSON 문자열 반환

실패도 예외로 던지지 않고 `ToolResults.error(...)` 문자열로 반환한다. 결과 형식은 다음 둘 중 하나다.

```json
{"status":"success","message":"..."}
```

```json
{"status":"error","message":"..."}
```

일부 도구는 성공 응답에 `data` 객체를 추가한다.

### `ToolRegistry`

위치: `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/agent/tools/ToolRegistry.kt`

역할은 두 가지다.

- 등록된 모든 도구의 `declaration`을 JSON 배열로 묶어 시스템 프롬프트에 넣을 수 있게 한다.
- 모델이 출력했다고 가정한 `{"name": "...", "args": {...}}` JSON을 파싱해서 해당 도구의 `execute`를 호출한다.

제약:

- contract version, capability id, effect, PII 등급, timeout, tool availability 개념이 없다.
- 결과 JSON schema 검증이 없다.
- 동일 도구 반복 호출, 한 turn 최대 호출 횟수, confirmation policy가 없다.
- 실제 LLM/ReAct loop는 없다. `MainActivity`에서 raw JSON을 직접 넣어 dispatch하는 테스트 화면이다.

## 원격 도구 상세

### `create_calendar_event`

위치: `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/agent/tools/CreateCalendarEventTool.kt`

목적은 CalendarProvider에 직접 저장하는 것이 아니라 `Intent.ACTION_INSERT`로 외부 캘린더 작성 화면을 여는 것이다.

입력:

| 필드 | 필수 | 의미 |
|---|---:|---|
| `title` | 예 | 일정 제목 |
| `start_time` | 예 | `yyyy-MM-ddTHH:mm` 또는 `yyyy-MM-ddTHH:mm:ss` |
| `end_time` | 아니요 | 생략 시 시작 1시간 후 |
| `location` | 아니요 | 장소 |
| `description` | 아니요 | 일정 메모 |
| `attendee_emails` | 아니요 | 참석자 이메일 배열 |

동작:

- `SimpleDateFormat`으로 시작/종료 시각을 파싱한다.
- 종료 시각이 없으면 1시간을 더한다.
- 종료가 시작보다 빠르면 오류를 반환한다.
- `CalendarContract.Events.TITLE`, `EXTRA_EVENT_BEGIN_TIME`, `EXTRA_EVENT_END_TIME`, `EVENT_LOCATION`, `DESCRIPTION`, `Intent.EXTRA_EMAIL`을 채운다.
- `FLAG_ACTIVITY_NEW_TASK`를 붙이고 `context.startActivity(intent)`를 실행한다.
- 성공 메시지는 “화면을 열었다”까지만 말한다. 저장 완료를 주장하지 않는다.

현재 코드와 비교:

- 현재 `tool-android-intents`의 `CreateCalendarEventPlugin`은 같은 목적을 이미 구현한다.
- 현재 구현은 `ToolContract`, `ToolInputCodec`, `ToolOutputCodec`, `TypedToolPlugin`, backend port(`CalendarComposerBackend`)로 분리되어 있다.
- 현재 구현은 `ToolExecutionContext.deviceTimeZoneId`를 사용해 기기 timezone 기준으로 파싱한다.
- 현재 구현은 availability probe가 있고, `SecurityException`도 처리한다.
- 결론: 캘린더 도구는 원격 코드를 이식할 필요가 거의 없다. 현재 구현을 유지하는 것이 낫다.

### `open_compose`

위치: `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/agent/tools/OpenComposeTool.kt`

목적은 메일 또는 SMS 작성 화면을 여는 것이다. 전송은 사용자가 외부 앱에서 한다.

입력:

| 필드 | 필수 | 의미 |
|---|---:|---|
| `channel` | 예 | `email` 또는 `sms` |
| `to` | 예 | 이메일 주소 또는 전화번호 |
| `subject` | 아니요 | 이메일 제목. SMS에서는 무시 |
| `body` | 예 | 초안 본문 |

동작:

- `channel=email`이면 `Intent.ACTION_SENDTO` + `mailto:` URI를 만든다.
- `subject`, `body`를 `mailto:` query에 `Uri.encode`로 넣고, `Intent.EXTRA_EMAIL`, `EXTRA_SUBJECT`, `EXTRA_TEXT`도 함께 넣는다.
- `channel=sms`이면 `Intent.ACTION_SENDTO` + `smsto:` URI를 만들고 `sms_body`를 넣는다.
- `ActivityNotFoundException`이면 메일/문자 앱 없음 오류를 반환한다.

현재 코드와 비교:

- 현재 `OpenComposePlugin`과 `AndroidMessageComposerBackend`가 같은 기능을 이미 구현한다.
- 현재 구현은 availability probe가 있고, channel별 availability도 검사한다.
- 결론: 메일/SMS 도구도 현재 구현을 유지하는 것이 좋다. 다만 원격 declaration의 “본문은 모델이 직접 작성해서 전달” 같은 설명은 현재 `AndroidIntentToolContracts.Compose.description`에 일부 반영할 수 있다.

### `update_business_card`

위치: `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/agent/tools/UpdateBusinessCardTool.kt`

목적은 로컬 DB에 저장된 기존 명함의 일부 필드를 수정하거나 비우는 것이다.

입력:

| 필드 | 필수 | 의미 |
|---|---:|---|
| `card_id` | 예 | 수정 대상 명함 ID |
| `updates` | 아니요 | 변경할 필드와 값 |
| `clear_fields` | 아니요 | 값을 `null`로 비울 필드 목록 |

지원 필드:

```text
name, company, department, title, phone, mobile, email, address, website, memo
```

검증:

- `card_id`가 없으면 오류
- `updates`에 허용되지 않은 필드가 있으면 오류
- `clear_fields`에 허용되지 않은 값이 있으면 오류
- `updates`와 `clear_fields`가 모두 비어 있으면 오류
- 대상 명함이 없으면 오류

성공 결과:

- `status: success`
- `message: Business card updated.`
- `data.before`: 수정 전 명함 JSON
- `data.after`: 수정 후 명함 JSON

중요한 현재 코드와의 차이:

- 현재 `tool-contact`에는 `search_contacts`, `get_contact`만 있다.
- 현재 명함 데이터는 `app/src/main/assets/cards/business_cards.json`에서 읽는 read-only fixture다.
- 현재 `BusinessCardRecord`에는 `mobile`, `website`, `updatedAt`이 없고, 대신 `nameEn`, `industry`, `location`, `tags`가 있다.
- 현재 `DefaultToolPolicyEngine`은 `ToolEffect.LOCAL_MUTATION`이면 confirmation을 요구한다. 하지만 현재 `AndroidAgentRuntimeEnvironment.confirmationGateway`는 항상 `false`를 반환한다. 따라서 update 도구를 `LOCAL_MUTATION`으로 정상 등록하면 지금 UI에서는 실행 직전 취소될 가능성이 높다.

결론:

`update_business_card`는 원격 브랜치에서 실제로 가져와야 할 핵심 기능이다. 단, 현재 구조에 그대로 붙이는 것이 아니라 `ToolContract` + `TypedToolPlugin` 형태로 새로 구현해야 한다. 동시에 mutable 저장소와 confirmation 정책을 같이 해결해야 한다.

### `get_current_datetime`

위치: `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/agent/tools/GetCurrentDateTimeTool.kt`

목적은 상대 날짜 해석 전에 현재 날짜/시각 기준점을 제공하는 것이다.

입력:

| 필드 | 필수 | 의미 |
|---|---:|---|
| `timezone` | 아니요 | IANA timezone ID. 예: `Asia/Seoul` |

출력:

| 필드 | 의미 |
|---|---|
| `date` | `yyyy-MM-dd` |
| `time` | `HH:mm:ss` |
| `datetime` | `yyyy-MM-ddTHH:mm:ssXXX` |
| `timezone` | 적용된 timezone ID |
| `epoch_millis` | epoch milliseconds |
| `utc_offset` | `+09:00` 형식 |

검증:

- timezone이 주어지면 `TimeZone.getAvailableIDs()`에 정확히 존재하는지 확인한다.
- 알 수 없는 timezone이면 오류를 반환한다.

현재 코드와 비교:

- 현재 `AppContainer.SYSTEM_INSTRUCTION`은 “현재 날짜·시각 조회는 제공되지 않습니다”라고 명시한다.
- 현재 캘린더 라우터는 사용자가 절대 날짜/시각을 입력해야만 `create_calendar_event`를 호출한다.
- 실제 LiteRT 모델이 상대 날짜를 처리하려면 이 도구가 필요하다.

결론:

`get_current_datetime`도 현재 구조에 반드시 이식해야 할 핵심 기능이다. 구현 난이도는 낮고, read-only 도구라 policy 충돌도 적다.

## 원격 Room 명함 저장소

위치:

- `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/data/BusinessCard.kt`
- `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/data/BusinessCardEntity.kt`
- `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/data/BusinessCardDao.kt`
- `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/data/HjpDatabase.kt`
- `/private/tmp/hjp-sojung-android-app-remote/app/src/main/java/com/example/hjp/data/RoomBusinessCardStore.kt`

모델:

```text
id, name, company, department, title, phone, mobile, email, address, website, memo, updatedAt
```

DAO:

- `findById(id)`
- `count()`
- `insertAll(cards)`
- `update(card)`

저장소:

- `BusinessCardStore.findById`
- `BusinessCardStore.update`
- DB가 비어 있으면 샘플 명함 5개를 `OnConflictStrategy.IGNORE`로 seed
- update는 기존 값을 copy한 뒤 `updates`와 `clearFields`를 적용하고 `updatedAt`을 UTC ISO 문자열로 기록

현재 코드와 비교:

- 현재 `AssetBusinessCardRepository`는 asset JSON을 lazy-load하고 캐시한다.
- 현재 검색 backend는 `BusinessCardRepository.loadAll()`과 `getById()`만 요구한다.
- 현재 asset fixture는 2개이고 `C001`, `C002` ID를 쓴다.
- 원격 Room 샘플은 5개이고 `sample-card-1`부터 `sample-card-5`를 쓴다.

중요한 설계 판단:

현재 앱을 원격 `android-app` 기능으로 확장하려면, 단순히 `RoomBusinessCardStore`를 복사하는 것보다 현재 `tool-contact`의 검색 backend가 사용할 수 있는 repository 형태로 Room 구현을 맞추는 것이 낫다. 즉, Room repository가 현재 `BusinessCardRepository`를 구현하고, 추가로 update용 mutable port를 제공하는 방식이 가장 자연스럽다.

## 현재 작업 코드 구조

현재 워크스페이스는 원격 브랜치보다 구조적으로 더 크다.

```text
agent-contract        모델/에이전트 계약
agent-core            ReAct loop, registry snapshot, policy, executor, session
tool-contract         versioned tool contract와 typed plugin API
tool-contact          명함 검색/상세 조회 plugin
tool-android-intents  캘린더/메일/SMS Android Intent plugin
llm-litert            LiteRT-LM native tool calling adapter
search-core           로컬 명함 검색 core
app                   UI, composition root, asset repository, emulator router
```

현재 production 등록 도구:

| 모델 tool name | capability | 현재 구현 |
|---|---|---|
| `search_contacts` | `contact.search` | `SearchContactsPlugin` + `RyeongContactSearchBackend` |
| `get_contact` | `contact.get` | `GetContactPlugin` + `RyeongContactSearchBackend` |
| `create_calendar_event` | `calendar.open_insert` | `CreateCalendarEventPlugin` + Android Intent backend |
| `open_compose` | `message.open_compose` | `OpenComposePlugin` + Android Intent backend |

현재 누락된 원격 기능:

| 원격 기능 | 현재 상태 |
|---|---|
| `update_business_card` | 구현/등록 없음 |
| `get_current_datetime` | 구현/등록 없음 |
| Room 기반 mutable 명함 저장소 | 없음. 현재 asset read-only |
| 명함 5개 seed | 없음. 현재 asset 2개 |

## 핵심 구조 차이

| 항목 | 원격 android-app | 현재 작업 코드 |
|---|---|---|
| 모듈 구조 | 단일 `app` 모듈 | 멀티 모듈 |
| 도구 인터페이스 | `AgentTool.execute(JSONObject): String` | `ToolPlugin.execute(ToolRequest, ToolExecutionContext): ToolExecutionResult` |
| 도구 선언 | 각 도구가 raw `JSONObject declaration` 보유 | `ToolContract`가 input/output schema, effect, PII, timeout, presentation 포함 |
| Registry | 이름 기반 map dispatch | availability/device capability 기준 snapshot + binding |
| 모델 연결 | 없음. 수동 JSON 테스트 화면 | LiteRT-LM adapter + emulator local router |
| 실행 루프 | 없음 | `AgentKernel` ReAct loop |
| 결과 검증 | 없음 | input codec/output codec, normalized observation |
| 안전 정책 | 없음 | permission, confirmation, mutation deny/confirm, 반복 호출 차단 |
| 명함 데이터 | Room mutable DB | asset read-only fixture |
| 명함 검색 | 없음 | ryeong hybrid local search |
| 현재 시각 | 있음 | 없음 |
| 명함 수정 | 있음 | 없음 |

## 이식 방향

### 1. 원격 `ToolRegistry`는 가져오지 않는다

현재 `AgentKernel` 구조를 유지해야 한다. 원격 `ToolRegistry`로 되돌리면 다음 기능이 퇴화한다.

- LiteRT tool calling 연결
- tool catalog revision/binding revision
- availability filtering
- typed input/output validation
- timeout
- policy/confirmation
- session state
- emulator compatibility router

따라서 이식 대상은 원격의 “기능 의미와 입력/출력 schema”이지, raw JSON dispatch 구조가 아니다.

### 2. `get_current_datetime`을 typed plugin으로 추가

권장 위치:

```text
tool-datetime/
  src/main/kotlin/com/hjp/tool/datetime/DateTimeToolContracts.kt
  src/main/kotlin/com/hjp/tool/datetime/DateTimePlugins.kt
```

또는 작은 범위로 시작하려면 `tool-system` 같은 모듈명도 가능하다.

권장 contract:

- capability: `datetime.current`
- modelName: `get_current_datetime`
- effect: `READ_ONLY`
- confirmationPolicy: `NONE`
- inputPii/outputPii: `NONE`
- timeout: 1~3초
- input schema: optional `timezone`
- output schema: `date`, `time`, `datetime`, `timezone`, `epoch_millis`, `utc_offset`

등록 필요 지점:

- `settings.gradle.kts`
- root/app Gradle dependency
- `AppContainer.plugins`
- `AppContainer.SYSTEM_INSTRUCTION`: “현재 날짜·시각 조회는 제공되지 않습니다” 문구 제거
- `LocalToolRoutingModelGateway`: “현재 시각 알려줘”, 상대 날짜 일정 요청을 처리할지 결정

### 3. `update_business_card`를 typed plugin으로 추가

권장 위치:

```text
tool-contact/src/main/kotlin/com/hjp/tool/contact/ContactUpdateToolContracts.kt
tool-contact/src/main/kotlin/com/hjp/tool/contact/UpdateBusinessCardPlugin.kt
```

권장 contract:

- capability: `contact.update`
- modelName: `update_business_card`
- effect: `LOCAL_MUTATION`
- confirmationPolicy: `BEFORE_EXECUTION`
- inputPii: `SENSITIVE_CONTACT`
- outputPii: `SENSITIVE_CONTACT`
- timeout: 3~5초

주의:

현재 `DefaultToolPolicyEngine`은 `LOCAL_MUTATION`에 confirmation을 요구한다. 지금 `AndroidAgentRuntimeEnvironment.confirmationGateway`는 항상 `false`라서 update 실행이 막힌다. 다음 중 하나를 반드시 선택해야 한다.

1. UI에 confirmation dialog를 구현하고 `ConfirmationGateway`를 실제 사용자 응답으로 연결한다.
2. 명함 수정만 별도 policy로 허용한다. 단, 개인정보 변경 작업이라 권장하지 않는다.
3. update 도구를 일단 등록하지 않고 backend/contract/test만 먼저 만든다.

정확성을 우선하면 1번이 맞다.

### 4. Room 저장소는 현재 검색 포트에 맞춰 이식한다

현재 `tool-contact`는 `BusinessCardRepository`를 다음처럼 기대한다.

```kotlin
interface BusinessCardRepository {
    suspend fun loadAll(): List<BusinessCardRecord>
    suspend fun getById(cardId: String): BusinessCardRecord?
}
```

update까지 고려하면 새 mutable port가 필요하다.

```kotlin
interface MutableBusinessCardRepository : BusinessCardRepository {
    suspend fun update(
        cardId: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
        updatedAt: String,
    ): BusinessCardUpdateResult?
}
```

Room 구현체는 `app` 모듈에 두고, `tool-contact`에는 Android/Room import가 들어가지 않게 유지하는 것이 현재 모듈 경계와 맞다.

권장 app 파일:

```text
app/src/main/java/com/example/hjp/data/BusinessCardEntity.kt
app/src/main/java/com/example/hjp/data/BusinessCardDao.kt
app/src/main/java/com/example/hjp/data/HjpDatabase.kt
app/src/main/java/com/example/hjp/data/RoomBusinessCardRepository.kt
```

`RoomBusinessCardRepository`는 `BusinessCardRecord`로 변환해서 현재 검색 backend가 그대로 사용할 수 있게 해야 한다.

### 5. 명함 schema를 통합해야 한다

원격 Room schema:

```text
id, name, company, department, title, phone, mobile, email, address, website, memo, updatedAt
```

현재 검색 schema:

```text
id, name, nameEn, company, title, department, industry, location, phone, email, address, memo, tags
```

바로 합치려면 다음 통합 schema가 필요하다.

```text
id, name, nameEn, company, title, department, industry, location,
phone, mobile, email, address, website, memo, tags, updatedAt
```

Room에는 `tags` 저장 방식도 정해야 한다.

- 빠른 구현: JSON 문자열 컬럼으로 저장
- 정규화 구현: 별도 `business_card_tags` 테이블

현재 검색 품질과 구현 속도를 고려하면 JSON 문자열 컬럼이 먼저 현실적이다.

### 6. 에뮬레이터 라우터도 새 도구를 알아야 한다

현재 에뮬레이터에서는 native LiteRT를 피하기 위해 `LocalToolRoutingModelGateway`가 직접 tool call을 만든다. 새 도구를 등록해도 이 라우터가 모르면 에뮬레이터 프롬프트 테스트에서는 작동하지 않는다.

추가할 라우팅:

- `현재 시간 알려줘`, `오늘 날짜 알려줘` -> `get_current_datetime`
- `내일 오후 2시 회의 일정 만들어줘` 같은 상대 날짜 -> 우선 `get_current_datetime` 호출 후, 다음 tool call에서 `create_calendar_event`로 변환하거나 사용자에게 절대 시각 확인 요청
- `김지원 명함 메모를 ...로 수정해줘` -> `search_contacts` -> `get_contact` -> `update_business_card`

단, update는 confirmation policy와 연결되어야 하므로 local router 테스트도 그 흐름을 반영해야 한다.

## 현재 도구별 유지/변경 판단

| 기능 | 판단 | 이유 |
|---|---|---|
| `create_calendar_event` | 현재 구현 유지 | 원격과 기능 동일, 현재가 typed contract/availability/timezone/backend 분리로 더 안전 |
| `open_compose` | 현재 구현 유지 | 원격과 기능 동일, 현재가 channel availability와 backend 분리 제공 |
| `search_contacts` | 현재 구현 유지 | 원격에는 검색 도구 없음 |
| `get_contact` | 현재 구현 유지하되 Room repository로 backend 교체 가능 | 원격에는 ID 기반 update store만 있고 상세 조회 도구는 없음 |
| `get_current_datetime` | 원격 기능을 새 typed plugin으로 이식 | 현재 없음. 상대 날짜 처리를 위해 필요 |
| `update_business_card` | 원격 기능을 새 typed plugin으로 이식 | 현재 없음. Room/mutable repository/confirmation이 함께 필요 |
| Room 저장소 | 현재 app data layer에 새로 도입 | 현재 asset read-only라 update 불가 |

## 구현 순서 제안

1. `get_current_datetime` typed plugin 추가
2. `AppContainer` 등록 및 시스템 지침 수정
3. `LocalToolRoutingModelGateway`에 현재 날짜/시각 요청 처리 추가
4. datetime 단위 테스트와 app 라우터 테스트 추가
5. Room 의존성/KSP 추가
6. app에 Room entity/DAO/database/repository 추가
7. `RyeongContactSearchBackend` 입력 repository를 asset에서 Room repository로 교체
8. `update_business_card` contract/plugin 추가
9. confirmation UI 또는 confirmation gateway 구현
10. `AppContainer`에 update plugin 등록
11. `LocalToolRoutingModelGateway`에 명함 수정 라우팅 추가
12. 검색/상세/수정/캘린더/메일/SMS 전체 테스트 실행

## 필수 테스트 목록

새 구현 후 최소한 다음을 검증해야 한다.

- `get_current_datetime`:
  - timezone 생략 시 device timezone 사용
  - `Asia/Seoul` 입력 시 `+09:00`
  - 잘못된 timezone 오류
- `update_business_card`:
  - 일부 필드 update
  - `clear_fields`로 null 처리
  - 알 수 없는 필드 거부
  - 없는 card id 오류
  - update 전후 JSON 반환
  - confirmation 거부 시 DB 미변경
- Room repository:
  - 최초 실행 시 seed
  - `loadAll()` 검색 backend 연동
  - `getById()` 상세 조회
  - update 후 검색/상세 조회에 반영
- Agent loop:
  - `search_contacts -> get_contact -> open_compose`
  - `search_contacts -> get_contact -> update_business_card`
  - `get_current_datetime -> create_calendar_event`
- Android instrumentation:
  - 캘린더/메일/SMS intent resolve
  - update confirmation UI

## 주요 위험

- **명함 수정은 개인정보 mutation이다.** 현재 policy 구조상 confirmation을 구현하지 않고 억지로 허용하면 안전 설계가 후퇴한다.
- **Room 도입은 데이터 소스 전환이다.** 현재 asset fixture와 ID 체계가 바뀌면 기존 검색 테스트와 emulator prompt 테스트가 깨질 수 있다.
- **schema가 다르다.** 원격의 `mobile`, `website`, `updatedAt`과 현재의 `nameEn`, `industry`, `location`, `tags`를 통합해야 한다.
- **상대 날짜는 모델 품질에 좌우된다.** `get_current_datetime`을 추가해도 모델이 항상 올바르게 호출한다고 보장할 수 없으므로 system instruction과 LocalToolRoutingModelGateway 테스트가 필요하다.
- **Android backup 정책을 재검토해야 한다.** Room에 명함 PII를 저장하면 backup/data extraction 정책에서 제외하거나 암호화 정책을 정해야 한다.

## 다음 구현에서 바로 바꿀 파일 후보

현재 구조를 유지하면서 원격 기능을 반영하려면 다음 파일/모듈을 만지는 것이 적절하다.

```text
settings.gradle.kts
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/java/com/example/hjp/AppContainer.kt
app/src/main/java/com/example/hjp/LocalToolRoutingModelGateway.kt
app/src/main/AndroidManifest.xml
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/main/java/com/example/hjp/data/BusinessCardEntity.kt
app/src/main/java/com/example/hjp/data/BusinessCardDao.kt
app/src/main/java/com/example/hjp/data/HjpDatabase.kt
app/src/main/java/com/example/hjp/data/RoomBusinessCardRepository.kt
tool-contact/src/main/kotlin/com/hjp/tool/contact/ContactDomain.kt
tool-contact/src/main/kotlin/com/hjp/tool/contact/ContactUpdateToolContracts.kt
tool-contact/src/main/kotlin/com/hjp/tool/contact/UpdateBusinessCardPlugin.kt
tool-datetime/src/main/kotlin/com/hjp/tool/datetime/DateTimeToolContracts.kt
tool-datetime/src/main/kotlin/com/hjp/tool/datetime/DateTimePlugins.kt
```

## 최종 판단

원격 `android-app` 브랜치는 “도구 기능의 원형”으로 보면 가치가 크다. 특히 `update_business_card`, `get_current_datetime`, Room seed/update 흐름은 현재 에이전트에 추가해야 할 기능이다.

다만 현재 워크스페이스는 이미 더 정확한 agent/tool runtime으로 발전해 있으므로, 원격 구조로 되돌리면 안 된다. 구현 방향은 다음 한 문장으로 정리된다.

> 원격 브랜치의 기능 의미와 Room 저장소 아이디어를 가져오되, 현재의 `ToolContract`/`TypedToolPlugin`/`AgentKernel` 구조 안에 새 plugin과 repository로 재작성한다.
