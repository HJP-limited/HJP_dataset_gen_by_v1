# HJP 현재 에이전트 완전 분석서

> 분석 기준: 2026-07-11, `android-app` 브랜치, commit `993a5f5` 위의 현재 작업 트리<br>
> 판단 기준: 설명 문서보다 현재 소스 코드와 실행 결과를 우선함<br>
> 검증: `./gradlew test :app:assembleDebug lintDebug` 성공, JVM 단위 테스트 21개 통과

## 1. 이 문서의 목적과 결론

이 문서는 HJP 에이전트를 처음 접하는 사람이 이 파일 하나만 읽고도 다음을 이해할 수 있도록 작성했다.

- 이 에이전트가 무엇이며 무엇을 할 수 있는가
- 실제 단말과 에뮬레이터에서 무엇이 다르게 실행되는가
- 사용자 문장이 어떻게 모델 결정, 도구 호출, 최종 답변으로 이어지는가
- 등록된 6개 도구의 입력, 출력, 부작용, 확인 정책은 무엇인가
- 명함 데이터가 어디에 저장되고 어떻게 검색·수정되는가
- 세션, 동시성, 오류, 개인정보와 안전 정책은 어떻게 처리되는가
- 현재 테스트로 확인된 범위와 아직 확인되지 않은 범위는 무엇인가
- 현재 구현의 제약, 문서 불일치, 기술적 위험은 무엇인가

현재 HJP는 **Android 앱 안에서 동작하는 온디바이스 단일 ReAct 에이전트**다. 사용자의 한국어 요청을 모델이 해석하고, 필요하면 한 번에 하나씩 로컬 도구를 호출하며, 도구 결과를 다시 모델에 전달해 다음 행동 또는 최종 답변을 얻는다. 서버 API 호출은 구현되어 있지 않다.

현재 production composition root인 `AppContainer`에 실제 등록된 도구는 **6개**다.

1. `search_contacts`: 로컬 명함 검색
2. `get_contact`: 명함 상세 조회
3. `update_business_card`: 로컬 명함 수정
4. `create_calendar_event`: 캘린더 일정 작성 화면 열기
5. `open_compose`: 이메일 또는 문자 작성 화면 열기
6. `get_current_datetime`: 현재 날짜·시각 조회

한 사용자 turn에서 허용되는 도구 호출은 현재 **최대 5회**다. 저장소의 `README.md`와 `CLAUDE.md`에 적힌 “도구 4개, 수정/현재 시각 미구현, 최대 3회”는 현재 코드보다 오래된 정보다.

## 2. 핵심 용어

| 용어 | 이 프로젝트에서의 의미 |
|---|---|
| 에이전트 | 모델의 결정과 도구 실행을 반복해 사용자 요청을 처리하는 전체 시스템 |
| ReAct | 판단하고(Reason/decide), 행동하며(Act/tool), 결과를 관찰한 뒤 다음 판단을 하는 반복 구조 |
| turn | 사용자가 메시지 하나를 보내고 최종 답변 또는 오류가 나올 때까지의 처리 단위 |
| model gateway | 에이전트 core가 실제 LiteRT 모델 또는 에뮬레이터 호환 라우터와 대화하는 추상 인터페이스 |
| tool contract | 모델 도구 이름, 설명, JSON 입출력 schema, 부작용, PII 수준, timeout 등 안정적인 도구 규격 |
| tool plugin | contract를 실제 backend 동작에 연결하는 구현체 |
| registry snapshot | 현재 기기에서 사용 가능한 도구만 고른 불변 catalog |
| observation | 도구의 성공/실패 결과를 모델이 다시 읽을 수 있는 안전한 JSON으로 변환한 것 |
| composition root | 실제 구현체를 만들고 서로 연결하는 곳. 현재는 `AppContainer.kt` |

## 3. 사용자 관점에서 가능한 일

### 3.1 명함 검색과 조회

- 이름, 회사, 직함, 지역, 업종, 메모, 태그 등에 기반해 로컬 명함을 찾는다.
- 검색 결과에는 `card_id`, 이름, 회사, 직함, 지역, 점수만 포함한다.
- 전화번호와 이메일 같은 상세 개인정보는 검색 결과에서 제외한다.
- 특정 명함이 정해진 뒤에만 `get_contact`로 전화번호, 휴대폰, 이메일, 주소, 웹사이트, 메모 등을 조회한다.
- 검색 결과가 여러 개면 에뮬레이터 호환 라우터는 사용자가 대상을 하나로 특정하도록 요구한다.

예시 요청:

- `판교에서 만난 AI 개발자 찾아줘`
- `김지원 명함 찾아줘`
- `투자 관련 대표 명함 검색해 줘`

### 3.2 명함 수정

- 기존 명함의 일부 필드를 바꾸거나 비울 수 있다.
- 수정 가능한 필드는 `name`, `name_en`, `company`, `department`, `title`, `industry`, `location`, `phone`, `mobile`, `email`, `address`, `website`, `memo`다.
- `tags`와 `id`는 수정할 수 없다.
- 로컬 DB를 바꾸는 작업이므로 실행 전에 앱이 확인 UI를 표시한다.
- 수정 성공 후 검색 backend cache를 무효화하여 다음 검색 때 DB의 최신 상태로 인덱스를 다시 만든다.
- `updated_at`은 UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` 형식으로 자동 갱신한다.

예시 요청:

- `김지원 명함의 메모를 VIP로 변경해 줘`
- `C001 명함의 전화번호를 지워 줘`

### 3.3 캘린더 작성

- Android 캘린더 앱의 `ACTION_INSERT` 화면을 연다.
- 제목, 시작·종료 시각, 장소, 설명, 참석자 이메일을 미리 채운다.
- 종료 시각을 생략하면 시작 1시간 후로 설정한다.
- `오늘`, `내일`, `모레` 같은 상대 날짜는 먼저 `get_current_datetime`을 호출한 후 절대 현지 시각으로 변환한다.
- 명함 이름이 포함된 일정은 검색 → 상세 조회로 이메일을 얻어 참석자에 추가할 수 있다.
- HJP가 일정을 저장하는 것은 아니다. 최종 저장은 외부 캘린더 앱에서 사용자가 한다.

### 3.4 이메일·문자 작성

- 이메일은 `mailto:`, 문자는 `smsto:` 기반 `ACTION_SENDTO`로 외부 작성 화면을 연다.
- 수신자, 이메일 제목, 본문 또는 문자 본문을 미리 채운다.
- 이름만 주어진 경우 검색 → 단건 상세 조회 → 이메일/전화번호 선택 → 작성 화면 열기 순서로 진행할 수 있다.
- HJP가 메시지를 전송하는 것은 아니다. 최종 전송은 외부 앱에서 사용자가 한다.

### 3.5 현재 날짜·시각 조회

- 기기 기본 timezone 또는 요청한 정확한 IANA timezone 기준 현재 시각을 반환한다.
- 날짜, 시각, offset 포함 ISO datetime, timezone ID, epoch milliseconds, UTC offset을 제공한다.
- 존재하지 않는 timezone ID는 안전한 입력 오류로 반환한다.

## 4. 실행 환경에 따른 두 모델 경로

### 4.1 실제 Android 단말

실제 단말에서는 `LiteRtAgentModelGateway`를 사용한다.

- 모델 파일: 앱 전용 외부 files 디렉터리의 `models/hjp-agent.litertlm`
- 일반적인 절대 경로: `/storage/emulated/0/Android/data/com.example.hjp/files/models/hjp-agent.litertlm`
- cache: 앱 cache 디렉터리 아래 `litertlm`
- backend: 현재 `AppContainer`가 `CPU_ONLY`를 명시하므로 GPU fallback 경로는 사용하지 않는다.
- LiteRT-LM version: `0.13.1`
- `automaticToolCalling = false`: LiteRT가 plugin을 직접 실행하지 않고 `AgentKernel`이 수동으로 정책과 실행을 통제한다.
- 모델에 주는 도구 선언은 활성 contract의 이름, 설명, JSON input schema에서 동적으로 생성한다.

현재 저장소에는 git에서 제외된 `models/hjp-agent.litertlm` 파일이 존재한다.

- 크기: 약 271 MiB
- SHA-256: `510c8257d1f9d12b5be630b5d6a593732e10896e9ee0b3f3d20b2eebff8e1b13`
- `file` 명령 결과: 일반 `data`

코드와 저장소에는 이 binary의 정확한 모델 계열, 원본 배포 위치, 학습 정보, 라이선스, 내부 version을 검증할 metadata가 없다. README는 FunctionGemma 사용을 의도한다고 설명하지만, 위 hash의 binary가 정확히 어떤 checkpoint인지 소스만으로 증명할 수는 없다. 운영 배포 전 provenance와 license를 별도로 기록해야 한다.

### 4.2 Android 에뮬레이터

다음 조건 중 하나면 `LocalToolRoutingModelGateway`를 사용한다.

- hardware가 `ranchu` 또는 `goldfish`
- model이 `sdk_gphone`으로 시작
- product가 `sdk_gphone` 포함
- fingerprint가 `generic`으로 시작

이 경로는 LLM이 아니다. 한국어 문장을 정규식과 규칙으로 분석하는 결정적 호환 라우터다. 도입 이유는 일부 ARM64 emulator가 SME CPU feature를 잘못 광고하여 LiteRT/XNNPACK 초기화 중 native `SIGILL`이 발생할 수 있기 때문이다.

호환 라우터는 다음 intent를 순서대로 판별한다.

1. 현재 날짜·시각
2. 명함 수정
3. 이메일·문자 작성
4. 캘린더 작성
5. 명함 검색

지원하는 표현은 코드에 정의된 정규식 범위로 제한된다. 절대 일정 시각은 ISO 유사 형식 또는 `yyyy년 M월 d일 H시 m분`, 상대 날짜는 오늘·내일·모레만 명시적으로 처리한다. 일반 대화 능력이나 자유로운 언어 추론은 실제 모델과 동등하지 않다.

중요한 점은 호환 라우터도 tool backend를 직접 호출하지 않는다는 것이다. 동일한 `ModelToolCall`을 만들고, production과 같은 registry, policy, codec validation, timeout, executor, observation mapper를 거친다.

## 5. 시스템 지침과 모델 설정

`AppContainer.SYSTEM_INSTRUCTION`의 실제 지침은 다음 원칙을 모델에 준다.

- Android 기기 안에서만 동작하는 HJP 명함 에이전트라고 정의
- 한국어로 간결하고 정확하게 답변
- 현재 native tool catalog에 있는 기능만 사용
- 연락처는 `search_contacts`로 찾고 필요할 때만 `get_contact` 사용
- 명함 수정 전 대상을 먼저 특정
- 캘린더와 메시지는 외부 작성 화면만 열며 저장·전송 완료를 주장하지 않음
- 상대 날짜 일정은 현재 시각 조회 후 절대 시각으로 변환

기본 sampling profile은 temperature `0.2`, top-K `20`, top-P `0.95`이며 LiteRT `SamplerConfig`에 그대로 전달한다.

최종 답변 API는 `Flow<String>`이지만 현재 두 gateway 모두 초안 전체를 `flowOf` 한 번으로 반환한다. 따라서 UI는 token event를 처리할 수 있으나 현재 구현은 실제 token 단위 streaming을 하지 않는다.

## 6. 전체 아키텍처

```text
사용자
  ↓
MainActivity / Compose UI
  ↓ send()
AgentViewModel
  ↓ runTurn(userText)
AgentKernel
  ├─ AgentSessionManager ── AgentModelGateway
  │                         ├─ 실제 단말: LiteRtAgentModelGateway
  │                         └─ 에뮬레이터: LocalToolRoutingModelGateway
  ├─ DefaultToolRegistry
  ├─ DefaultToolPolicyEngine
  ├─ DefaultToolExecutor
  └─ DefaultToolObservationMapper
          ↓
      ToolPlugin
          ├─ 명함: Room repository + ryeong 검색
          ├─ 캘린더/메시지: Android Intent
          └─ 날짜·시각: 기기 clock/timezone
```

### 6.1 모듈별 책임

| 모듈 | 책임 | Android 의존 |
|---|---|---|
| `agent-contract` | 모델 gateway/session, decision, tool response, UI event 계약 | 없음 |
| `tool-contract` | tool contract, schema codec, plugin, 실행 결과, 오류, session state 계약 | 없음 |
| `agent-core` | registry, policy, executor, session, ReAct loop, observation 변환 | 없음 |
| `search-core` | Java 기반 로컬 hybrid 검색 엔진 | 없음 |
| `tool-contact` | 명함 domain/repository port와 검색·조회·수정 plugin | 없음 |
| `tool-android-intents` | 캘린더/메일/SMS port와 Android Intent backend | 있음 |
| `tool-datetime` | 현재 날짜·시각 tool | 없음 |
| `llm-litert` | LiteRT-LM native function-calling adapter | 있음 |
| `app` | Compose UI, ViewModel, Room, asset seed, composition root, emulator router | 있음 |

핵심 설계상 `agent-core`는 Android, LiteRT, Room, ryeong, 구체 tool class를 import하지 않는다. backend 교체는 port 구현과 `AppContainer` binding을 바꾸는 방식이다.

## 7. 한 turn의 정확한 처리 순서

1. `AgentViewModel.send()`가 공백 입력, busy 상태, model 미준비 상태를 차단한다.
2. 사용자 메시지를 UI state에 추가하고 busy/status를 설정한다.
3. `AgentKernel.runTurn()`이 입력을 trim하고 빈 문자열이면 오류를 낸다.
4. kernel의 `turnMutex`를 획득한다. 앱 전체의 동일 kernel에서는 동시에 한 turn만 실행된다.
5. UUID `turnId`를 만들고 `TurnStarted` event를 낸다.
6. in-memory agent session을 가져오거나 UUID session을 만든다.
7. registry가 plugin 활성 설정과 availability를 검사하여 활성 tool snapshot을 만든다.
8. snapshot의 contract revision이 기존 model session과 다르면 기존 conversation을 닫고 새 model session을 연다.
9. session에 저장된 만료되지 않은 capability state를 `safeCapabilityContext`로 모델에 전달한다.
10. 모델 또는 호환 라우터가 `FinalCandidate`, `ToolCalls`, `Invalid` 중 하나를 반환한다.
11. 최종 후보면 token event와 final message를 내고 종료한다.
12. tool call이면 아래 protocol guard를 검사한다.
13. 정책 엔진이 effect와 confirmation policy를 평가한다.
14. 필요하면 앱에 confirmation event를 보낸다.
15. 실행 가능하면 `ToolStarted` 후 executor가 contract timeout 안에서 plugin을 실행한다.
16. 성공 session update를 in-memory session에 적용한다.
17. 결과를 안전한 observation JSON으로 바꾸고 `ToolFinished`를 보낸다.
18. observation을 모델에 돌려주고 10번부터 반복한다.

### 7.1 protocol guard

- 한 decision에서 정확히 한 tool call만 허용한다.
- 한 turn에서 최대 5회까지만 tool을 호출한다.
- 같은 tool 이름과 canonical JSON arguments 조합의 반복 호출을 차단한다.
- 동일 `sessionId:turnId:callId` 재실행도 executor에서 한 번 더 차단한다.
- 활성 catalog에 없는 모델 tool 이름은 실행하지 않는다.
- contract의 JSON codec이 실제 argument type, 필수값, 범위를 다시 검증한다.
- plugin 실행은 contract별 timeout을 적용한다.
- timeout, 일반 exception, unavailable 상태를 구조화된 안전 오류로 변환한다.
- coroutine cancellation과 `VirtualMachineError`는 일반 오류로 숨기지 않고 다시 던진다.

## 8. Registry와 version 관리

각 tool은 다음 두 identity를 가진다.

- capability/contract identity: 기능 의미, 모델 이름, version, 설명, schema
- implementation identity: 현재 연결된 구체 backend 구현

registry는 매 turn 다음을 수행한다.

- `enabled()`가 true인지 검사
- `availability()`가 `Ready`인지 검사
- 같은 capability 후보가 여러 개면 priority가 가장 높은 구현 선택
- 최고 priority가 동률이면 모호한 binding으로 보고 실패
- 활성 model tool 이름 중복을 금지

snapshot에는 두 hash가 있다.

- `revision`: 모델에게 보이는 contract 내용의 SHA-256. contract가 같으면 implementation을 교체해도 동일하다.
- `bindingRevision`: capability/version/implementation binding의 SHA-256. backend가 바뀌면 달라진다.

model session 재생성 여부는 `revision`만 본다. 즉 모델이 보는 schema가 같고 backend만 교체되면 conversation을 유지할 수 있다.

## 9. 등록된 6개 도구 상세

### 9.1 공통 요약

| 모델 이름 | capability | version | effect | 확인 | timeout | PII 입력 → 출력 |
|---|---|---:|---|---|---:|---|
| `search_contacts` | `contact.search` | 1.0 | `READ_ONLY` | 없음 | 5초 | 없음 → 기본 연락처 |
| `get_contact` | `contact.get` | 1.0 | `READ_ONLY` | 없음 | 3초 | 기본 연락처 → 민감 연락처 |
| `update_business_card` | `contact.update` | 1.0 | `LOCAL_MUTATION` | 실행 전 확인 | 5초 | 민감 연락처 → 민감 연락처 |
| `create_calendar_event` | `calendar.open_insert` | 1.0 | `EXTERNAL_UI` | 외부 앱에서 최종 확인 | 3초 | 민감 연락처 → 없음 |
| `open_compose` | `message.open_compose` | 1.0 | `EXTERNAL_UI` | 외부 앱에서 최종 확인 | 3초 | 민감 연락처 → 없음 |
| `get_current_datetime` | `datetime.current` | 1.0 | `READ_ONLY` | 없음 | 1초 | 없음 → 없음 |

현재 앱은 Android runtime permission을 요청하지 않는다. 캘린더/메시지는 provider 데이터를 직접 읽거나 쓰지 않고 외부 UI를 여는 방식이라 calendar/contact permission이 필요하지 않다.

### 9.2 `search_contacts`

입력:

```json
{
  "query": "판교 AI 개발자",
  "limit": 5
}
```

- `query`: 필수, 공백 불가
- `limit`: 선택, 기본 5, 1~10

출력:

```json
{
  "results": [
    {
      "card_id": "C002",
      "name": "오성령",
      "company": "코어AI",
      "title": "AI 엔지니어",
      "location": "판교",
      "score": 123.4
    }
  ],
  "count": 1,
  "engine": "LocalEmbeddingEngine"
}
```

구현 ID는 `ryeong.hybrid.local.v1`이다. 성공하면 검색된 card ID 목록을 session의 `contact.last_search_results`에 저장한다. 전화번호와 이메일은 출력하지 않는다.

### 9.3 `get_contact`

입력:

```json
{
  "card_id": "C002",
  "purpose": "email"
}
```

- `card_id`: 필수
- `purpose`: 선택, 기본 `display`, 허용값 `display|email|sms|calendar`

출력은 `card_id`, 이름/영문명, 회사, 직함, 부서, 업종, 지역, 전화, 휴대폰, 이메일, 주소, 웹사이트, 메모, 태그, 수정 시각을 포함한다. 없는 ID면 `contact.not_found`를 반환한다. 성공하면 `contact.selected_contact` session state를 갱신한다.

구현 ID는 `ryeong.lookup.local.v1`이다.

### 9.4 `update_business_card`

입력:

```json
{
  "card_id": "C001",
  "updates": {
    "memo": "VIP 고객",
    "company": "새 회사"
  },
  "clear_fields": ["phone"]
}
```

- `card_id`: 필수
- `updates`와 `clear_fields` 중 적어도 하나는 비어 있지 않아야 함
- 지원하지 않는 field, 문자열이 아닌 수정값은 거부
- 결과는 수정 전 `before`와 수정 후 `after` 전체 명함

구현 ID는 `contact.update.local.v1`이다. policy가 `LOCAL_MUTATION` 또는 `BEFORE_EXECUTION`을 보고 confirmation을 요구한다. 사용자가 거절하면 `policy.confirmation_rejected` observation을 모델에 돌려준다.

### 9.5 `create_calendar_event`

입력:

```json
{
  "title": "오성령 회의",
  "start_time": "2026-07-12T14:00",
  "end_time": "2026-07-12T15:00",
  "location": "판교",
  "description": "프로젝트 논의",
  "attendee_emails": ["ai@example.com"]
}
```

- `title`, `start_time`: 필수
- 시각은 기기 timezone 기준 `yyyy-MM-dd'T'HH:mm` 또는 codec 구현상 초를 포함한 형식도 처리
- end가 없으면 1시간 뒤
- end는 start보다 늦어야 함
- 출력: `opened`, `destination=calendar`, `requires_user_confirmation=true`

구현 ID는 `android.calendar.intent.v1`이다. `CalendarContract.Events.CONTENT_URI`의 `ACTION_INSERT`를 사용한다. 캘린더 앱이 없거나 `ActivityNotFoundException`/`SecurityException`이 발생하면 안전 실패를 반환한다.

### 9.6 `open_compose`

입력:

```json
{
  "channel": "email",
  "to": "ai@example.com",
  "subject": "미팅 안내",
  "body": "내일 오후 2시에 뵙겠습니다."
}
```

- `channel`: 필수, `email|sms`
- `to`: 필수
- `subject`: email에서 선택
- `body`: 선택이며 생략 시 빈 문자열
- 출력: `opened`, `destination=email|sms`, `requires_user_confirmation=true`

구현 ID는 `android.message.intent.v1`이다. 외부 앱을 연 뒤 실제 전송 여부를 알지 못하며 전송 완료라고 답해서는 안 된다.

### 9.7 `get_current_datetime`

입력:

```json
{
  "timezone": "Asia/Seoul"
}
```

`timezone`은 선택이다. 지정할 때는 Java runtime이 아는 정확한 IANA ID여야 한다.

출력 예:

```json
{
  "date": "2026-07-11",
  "time": "13:45:12",
  "datetime": "2026-07-11T13:45:12+09:00",
  "timezone": "Asia/Seoul",
  "epoch_millis": 1783745112000,
  "utc_offset": "+09:00"
}
```

구현 ID는 `datetime.current.device.v1`이다.

## 10. 명함 데이터와 검색 방식

### 10.1 저장

초기 fixture는 `app/src/main/assets/cards/business_cards.json`이며 현재 2건이다.

- `C001` 김지원, 비전글로벌, 대표이사, finance, 서울
- `C002` 오성령, 코어AI, AI 엔지니어, it, 판교

앱은 Room database `hjp-agent.db`를 사용한다. DB가 비어 있을 때만 asset을 읽어 seed하며, 이후 조회와 수정은 Room을 기준으로 한다. 앱을 업데이트해 asset이 바뀌어도 기존 DB가 비어 있지 않으면 자동 재seed 또는 migration merge를 하지 않는다.

Room schema version은 1이고 schema export는 꺼져 있다. DB backup과 device transfer는 manifest 및 XML rule에서 제외되어 있다. Android app backup 자체도 `allowBackup=false`다.

### 10.2 검색

검색은 `SearchLookupService`와 `LocalEmbeddingEngine`을 결합한 로컬 hybrid 방식이다.

- lexical 점수: 이름 +50, 회사 +35, 직함 +25, 업종 +20, 기타 text +12
- semantic 점수: cosine이 0.04보다 크면 `cosine * 45`
- 192차원 feature hashing vector
- word feature, 2~3글자 n-gram, 수작업 concept map 사용
- `투자`, `제조`, `개발`, `행사`, `보안`, `연동`, `디자인` concept을 확장
- 별도 ML embedding model을 쓰지 않으며 `isModelBacked=false`

따라서 모듈/구현 이름에 “embedding” 또는 “hybrid”가 있어도 신경망 임베딩 검색은 아니다. 작은 고정 차원의 hashing 기반 의미 유사도다.

짧은 한글 이름(2~4글자)은 저장된 이름에 lexical match가 없으면 semantic false positive를 반환하지 않도록 별도 차단한다.

검색 service는 직전 조건 state를 내부에 유지한다. query에 `그중`, `이전`, `거기서`가 없으면 state를 초기화하고, 이 표현이 있으면 지역·업종·직함·semantic tag filter를 이어간다. 이 상태는 agent session store가 아니라 검색 service instance에 있으므로 “새 대화”로 agent session을 reset해도 검색 backend instance의 state 자체는 reset되지 않는다. 일반적인 새 검색어는 자동 clear되지만 엄밀한 세션 격리는 아니다.

## 11. 세션과 대화 상태

agent session은 `InMemoryAgentSessionStore`에만 존재한다.

- 앱 프로세스가 종료되면 session ID와 capability state는 사라진다.
- 대화 transcript나 raw observation을 disk에 영구 저장하지 않는다.
- `새 대화`는 model conversation을 닫고 in-memory session을 삭제하며 UI message도 초기화한다.
- 다음 요청 때 새 UUID session과 새 model conversation을 만든다.

도구가 저장하는 capability state:

- 검색 성공: `contact.last_search_results = {card_ids:[...]}`
- 상세 조회/수정 성공: `contact.selected_contact = {card_id:"..."}`

이 state는 다음 사용자 입력 뒤 `[tool_session_context]` JSON으로 모델에 붙는다. 만료 시각이 있는 state는 만료 후 제외하지만 현재 contact update에는 만료 시각이 없다.

모델 conversation 자체는 session 안에서 누적되므로 실제 단말 모델은 이전 turn 문맥을 보유한다. 에뮬레이터 라우터는 `pendingAction`만 tool chain 동안 유지하며 일반 대화 history를 해석하지 않는다.

## 12. 정책, 확인, 권한, 안전

### 12.1 effect별 정책

- `READ_ONLY`: 바로 실행
- `EXTERNAL_UI`: HJP 내부 confirmation 없이 외부 작성 화면을 열고, 외부 앱에서 사용자가 저장/전송 확인
- `LOCAL_MUTATION`: HJP UI에서 실행 전 confirmation 필수
- `EXTERNAL_MUTATION`: 현재 policy가 무조건 거부

확인 요청은 `CompletableDeferred<Boolean>`로 turn을 일시 정지한다. 새 확인 요청이 오면 기존 pending 요청을 false로 완료한다. 사용자가 실행/취소 버튼을 누르면 재개한다. 별도 confirmation timeout은 없다.

### 12.2 오류 observation

성공:

```json
{
  "ok": true,
  "capability": "contact.search",
  "contract_version": "1.0",
  "data": {}
}
```

실패:

```json
{
  "ok": false,
  "capability": "contact.search",
  "contract_version": "1.0",
  "error": {
    "code": "tool.invalid_arguments",
    "message_ko": "안전한 사용자용 메시지",
    "retryable": false
  }
}
```

exception stack trace나 임의 backend detail을 모델/UI에 직접 노출하지 않는다. 다만 LiteRT engine 초기화 실패는 Android log에 backend와 exception을 `Log.w`로 기록한다.

### 12.3 개인정보 경계

- 검색 결과는 전화번호와 이메일을 제외한다.
- 상세 조회와 수정 결과는 민감 연락처 전체를 모델 observation에 전달한다.
- 데이터와 모델 추론은 기기 안에서 이루어진다.
- 네트워크 client나 서버 전송 코드는 현재 없다.
- DB는 backup/transfer에서 제외된다.
- 외부 캘린더/메일/SMS 앱을 열 때 필요한 연락처 정보와 본문은 Android Intent로 해당 앱에 전달된다.

“온디바이스”가 민감 정보가 모델에 전혀 전달되지 않는다는 뜻은 아니다. 상세 조회 결과는 로컬 모델 context에 들어가며, 외부 UI tool 사용 시 관련 정보가 선택한 외부 앱으로 넘어간다.

## 13. UI와 사용자 상태

화면은 Jetpack Compose 기반 단일 chat 화면이다.

- 제목: `HJP Agent`
- 모델 준비 상태 표시
- 사용자/assistant 메시지 목록
- 현재 처리 상태 문구
- 명함 수정 confirmation 카드
- 2~5줄 요청 입력창
- 보내기, 취소, 새 대화 버튼

모델 readiness는 다음처럼 판단한다.

- emulator compatibility mode면 항상 ready
- 실제 단말이면 model file이 존재하고 읽을 수 있으면 ready

이 검사는 파일 형식, 크기, hash, 모델 호환성까지 확인하지 않는다. 읽을 수 있는 잘못된 파일도 초기 UI에서는 ready로 보이고, 실제 model session을 열 때 실패할 수 있다.

`AgentEvent`와 UI 반응:

| event | UI 반응 |
|---|---|
| `TurnStarted` | 별도 변화 없음 |
| `ToolStarted` | 실행 중 상태 표시 |
| `ToolFinished` | 성공/실패 상태 표시 |
| `ConfirmationRequested` | 실행/취소 카드 표시 |
| `Token` | assistant message 생성 또는 이어붙임 |
| `FinalMessage` | token이 없었던 경우 assistant message 추가 |
| `UserError` | assistant 오류 메시지 추가 |

ViewModel은 busy 중 중복 전송을 막는다. 취소하면 confirmation을 false로 완료하고 coroutine job을 cancel한다. 입력 draft는 `rememberSaveable`이라 Activity recreation 후에도 유지된다.

## 14. 빌드, 설치, 운영 전제

### 14.1 주요 version

- compile SDK: Android 36.1
- min SDK: 24
- target SDK: 36
- Gradle JVM toolchain: JDK 21
- Android Java source/target compatibility와 `search-core` Java release: 11
- Android Gradle Plugin: 9.2.1
- Kotlin: 2.2.10
- Compose BOM: 2026.02.01
- coroutines: 1.10.2
- kotlinx serialization: 1.9.0
- Room: 2.8.3
- LiteRT-LM Android: 0.13.1

### 14.2 빌드

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew test :app:assembleDebug
```

2026-07-11 확인 결과 build 성공이며 debug APK는 `app/build/outputs/apk/debug/app-debug.apk`, 크기는 약 62 MiB다. `.litertlm` 모델은 APK에 포함되지 않는다.

### 14.3 설치

`scripts/install-debug-with-model.sh [model-path] [apk-path]`는 다음을 자동 수행한다.

1. 연결되고 승인된 Android device가 정확히 하나인지 확인
2. APK를 `adb install -r`
3. 앱 전용 외부 model 디렉터리 생성
4. model push
5. host/device SHA-256 비교
6. 앱 force-stop 후 MainActivity 시작

## 15. 테스트와 확인된 범위

이번 분석에서 직접 실행한 명령:

```text
./gradlew test :app:assembleDebug
BUILD SUCCESSFUL in 16s
106 actionable tasks: 106 executed
```

통과한 JVM test는 총 21개다.

| 영역 | 개수 | 확인 내용 |
|---|---:|---|
| `agent-core` kernel | 2 | tool observation이 모델로 돌아가는 흐름, implementation swap revision |
| session manager | 1 | reset 시 conversation close와 새 session 생성 |
| emulator router | 9 | 검색, 절대/상대 일정, 현재 시각, 이메일, 문자, 명함 기반 compose, 수정 chain |
| prompt parser | 3 | 한국어 명함 검색어 추출과 무관 문장 제외 |
| search core | 2 | hybrid 검색/ID 조회, 모르는 한글 이름 false positive 차단 |
| contact plugin | 2 | 검색 PII 최소화/상세 조회, 수정 before/after와 mutation |
| datetime plugin | 2 | 기기 timezone 결과, 잘못된 timezone 거부 |

작성되어 있지만 이번에 실행하지 않은 Android instrumented test는 6개다.

- 화면 제목 표시
- model readiness에 따른 입력 활성화
- Activity recreation 후 draft 유지
- 새 대화 시 draft/UI session 초기화
- 캘린더/메일/SMS Intent resolver 존재
- 명함 prompt가 crash 없이 검색되는지

### 15.1 아직 검증되지 않은 것

- 실제 단말에서 현재 271 MiB model의 engine 초기화와 native function calling 정확도
- 긴 대화에서 모델 품질, context limit, 지연 시간, 메모리 사용량, 발열
- 실제 캘린더/메일/SMS 앱별 extra 호환성
- Room migration과 앱 upgrade 시 기존 사용자 데이터 보존
- process death, configuration change, confirmation 대기 중 lifecycle edge case
- timeout, 반복 call, 5회 초과, 다중 tool call에 대한 전용 test
- `tool-android-intents`와 `llm-litert`의 unit test
- 실제 Android instrumented test 실행 결과

## 16. 현재 구현의 중요한 제약과 위험

### 16.1 문서가 현재 코드와 불일치

`README.md`와 `CLAUDE.md`는 도구 4개, 최대 3회, 명함 수정/현재 시각 미등록이라고 설명한다. 현재 코드는 도구 6개와 최대 5회다. 상위 디렉터리 `HJP_Agent.md`를 기준 문서라고 적지만 이 저장소 내부에는 그 파일이 없다. 운영 판단은 이 문서와 현재 소스를 기준으로 해야 하며 기존 문서는 갱신이 필요하다.

### 16.2 실제 모델 identity 불명확

model binary의 source, license, version, expected prompt template이 저장소 metadata로 고정되어 있지 않다. hash만으로 모델 정체를 알 수 없다.

### 16.3 emulator와 실제 단말 행동 차이

에뮬레이터는 정규식 라우터, 실제 단말은 LLM이다. emulator E2E 성공이 실제 모델의 tool selection 성공을 보장하지 않으며, 반대로 자연스러운 표현을 emulator가 이해하지 못할 수 있다.

### 16.4 readiness 검사가 얕음

file readability만 보고 ready로 표시한다. 손상 파일, 잘못된 model, 호환되지 않는 version은 첫 요청 때야 발견된다.

### 16.5 현재 streaming과 token 제한

현재 최종 답변은 한 chunk로 반환한다.

### 16.6 session 경계가 완전하지 않음

agent session은 reset되지만 `RyeongContactSearchBackend`의 내부 검색 state는 backend instance에 남는다. 대부분의 새 query는 state를 지우지만 `그중/이전/거기서`로 시작하는 새 대화는 이전 filter가 이어질 가능성이 있다.

### 16.7 명함 무결성 검증 부족

초기 asset은 `id`와 `name`을 필수로 검증하지만 update schema는 `name`을 비우는 것을 허용한다. 이메일/전화/website 형식도 update 시 검증하지 않는다. 같은 field가 `updates`와 `clear_fields`에 동시에 들어오면 repository는 `updates` 값을 우선한다. 이 정책은 schema 설명에 명시되어 있지 않다.

### 16.8 외부 앱 실행의 의미

Intent `startActivity()` 성공은 화면을 열었다는 뜻일 뿐 저장/전송 성공을 뜻하지 않는다. 외부 앱에서 사용자가 취소했는지도 HJP는 알 수 없다.

### 16.9 confirmation 대기 timeout 없음

명함 수정 확인 UI에 응답하지 않으면 turn coroutine이 계속 대기한다. lifecycle이 끊길 때의 pending confirmation 복구도 영구 상태로 관리하지 않는다.

### 16.10 test 공백

핵심 happy path는 test되지만 정책 거부, timeout, JSON schema edge case, 실제 native adapter, Intent backend는 자동 test가 충분하지 않다.

### 16.11 상세 조회 목적은 접근 제어가 아님

`get_contact.purpose`는 `display|email|sms|calendar` 중 하나인지 검사하지만, 목적에 따라 반환 field를 줄이지는 않는다. 어떤 purpose든 전화번호, 이메일, 주소, 메모를 포함한 동일한 전체 상세 record를 모델에 반환한다. 현재 PII 최소화 경계는 검색 결과와 상세 조회 사이에만 있으며, 상세 조회 내부의 목적별 최소 권한은 구현되어 있지 않다.

## 17. 새 기능이나 backend를 추가하는 방법

1. 안정적인 capability ID와 model tool 이름을 정한다.
2. `ToolContract`에 version, description, input/output JSON schema, effect, confirmation, PII, timeout을 정의한다.
3. `ToolInputCodec`과 `ToolOutputCodec`을 작성한다.
4. 가능하면 `TypedToolPlugin<I,O>`를 상속해 validation과 결과 정규화를 재사용한다.
5. Android/DB/외부 SDK는 core가 아니라 별도 backend port 뒤에 둔다.
6. plugin `availability()`가 실제 사용 가능 여부를 올바르게 반환하게 한다.
7. `AppContainer.plugins`에 `ToolImplementationCandidate`로 등록한다.
8. 실제 모델의 system instruction에 행동 원칙이 필요하면 갱신한다.
9. emulator에서도 써야 하면 `LocalToolRoutingModelGateway`의 parser와 chain을 함께 확장한다.
10. codec, plugin, policy, kernel observation, emulator route, Android E2E test를 추가한다.
11. `./gradlew test :app:assembleDebug`와 실기기 검증을 수행한다.

contract의 major version은 breaking schema/semantic change에 사용하고, 같은 contract에 backend만 바꿀 때는 implementation ID와 registry priority를 바꾸는 것이 현재 설계에 맞다.

## 18. 코드 탐색 지도

| 알고 싶은 내용 | 파일 |
|---|---|
| production wiring, system prompt, tool 목록 | `app/src/main/java/com/example/hjp/AppContainer.kt` |
| ReAct loop와 5회 제한 | `agent-core/src/main/kotlin/com/hjp/agent/core/AgentKernel.kt` |
| model/session 계약 | `agent-contract/src/main/kotlin/com/hjp/agent/contract/AgentModel.kt` |
| UI event 계약 | `agent-contract/src/main/kotlin/com/hjp/agent/contract/AgentEvent.kt` |
| registry 선택과 revision | `agent-core/src/main/kotlin/com/hjp/agent/core/DefaultToolRegistry.kt` |
| confirmation/effect 정책 | `agent-core/src/main/kotlin/com/hjp/agent/core/AgentPolicy.kt` |
| timeout과 중복 실행 방지 | `agent-core/src/main/kotlin/com/hjp/agent/core/ToolRuntime.kt` |
| observation JSON | `agent-core/src/main/kotlin/com/hjp/agent/core/ToolObservationMapper.kt` |
| in-memory session | `agent-core/src/main/kotlin/com/hjp/agent/core/AgentSession.kt` |
| tool 공통 규격 | `tool-contract/src/main/kotlin/com/hjp/tool/contract/` |
| 명함 contract/plugin | `tool-contact/src/main/kotlin/com/hjp/tool/contact/` |
| 검색 알고리즘 | `search-core/src/main/java/com/hjp/searchlookup/` |
| Room 저장소 | `app/src/main/java/com/example/hjp/data/` |
| 캘린더/메시지 | `tool-android-intents/src/main/kotlin/com/hjp/tool/android/` |
| 날짜·시각 | `tool-datetime/src/main/kotlin/com/hjp/tool/datetime/` |
| 실제 LiteRT adapter | `llm-litert/src/main/kotlin/com/hjp/agent/litert/LiteRtAgentModelGateway.kt` |
| emulator router/parser | `app/src/main/java/com/example/hjp/LocalToolRoutingModelGateway.kt` |
| UI state와 event 처리 | `app/src/main/java/com/example/hjp/AgentViewModel.kt` |
| Compose 화면 | `app/src/main/java/com/example/hjp/MainActivity.kt` |
| 초기 명함 fixture | `app/src/main/assets/cards/business_cards.json` |
| model 포함 설치 | `scripts/install-debug-with-model.sh` |

## 19. 최종 평가

현재 HJP는 단순한 tool demo가 아니라, model adapter와 tool runtime을 분리한 실제 온디바이스 single-agent 구조다. contract 기반 registry, 수동 tool execution, effect 기반 confirmation, timeout, 중복 호출 차단, PII를 줄인 검색 출력, in-memory session, Room 기반 수정 가능한 명함 저장소를 갖춘 점은 구조적으로 명확하다.

반면 실제 제품 준비 수준을 판단할 때는 다음을 분리해서 보아야 한다.

- **코드로 확인됨:** 6개 tool 등록, 최대 5회 ReAct loop, 명함 DB 검색/조회/수정, 외부 작성 UI, 현재 시각, 안전 observation, 단위 test와 debug build 성공
- **제한적으로 확인됨:** emulator의 규칙 기반 한국어 route와 주요 multi-tool chain
- **아직 확인 필요:** 실제 model provenance와 native tool-calling 품질, 실기기 E2E, 성능/메모리, lifecycle 복구, 데이터 migration, 정책 edge case test

따라서 현재 에이전트를 정확히 한 문장으로 정의하면 다음과 같다.

> HJP는 실제 단말에서는 LiteRT-LM native function calling 모델을, emulator에서는 규칙 기반 호환 라우터를 사용하며, 동일한 contract·policy·executor 기반 ReAct kernel을 통해 로컬 명함 3개 기능, Android 외부 작성 UI 2개 기능, 현재 시각 1개 기능을 최대 5회 조합 실행하는 한국어 온디바이스 Android 에이전트다.
