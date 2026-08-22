# 구조화 멀티턴 메모리 이식 분석과 적용 결과

> **후속 문서 안내(2026-08-08)**: 이 문서는 참조 브랜치와의 **차이 분석**과 1차 이식 기록이다.
> 그 뒤 세션 수명주기, transcript/컨텍스트 분리, 구조화 entity 메모리, token 예산, native
> conversation 중복 해소, router 결정 구조로 설계가 확장되면서 §2의 자료구조와 프롬프트 블록
> 이름은 바뀌었다(`[conversation_memory]` → `[session_state]` 외). **현재 구현의 기준 문서는
> `docs/AGENT_COMPLETION_REPORT.md`다.** 아래 §1 분석과 브랜치 비교는 그대로 유효하다.

작성일 2026-08-06(Asia/Seoul). 대상은 `origin/agent/structured-multiturn-memory`
(HEAD `5f2c988`)의 멀티턴 정책을 현재 `android-app` 워크트리 에이전트에 이식하는 작업이다.

두 브랜치는 **공통 조상이 없다**(`git merge-base` 없음). 따라서 merge나 cherry-pick이
불가능하고, 기능 단위로 재이식하는 방법만 유효하다.

```
origin/agent/structured-multiturn-memory: 0032ee3 → d0dd11b → 43512ce → b80a685 → d2aa709 → f98399e → 5f2c988
android-app(local):                       b79f293 → d3ae7fa → 993a5f5 (+ 대량 uncommitted 작업)
```

---

## 1. 분석

### 1.1 참조 브랜치의 멀티턴은 정확히 3개 커밋이다

| 커밋 | 제목 | 멀티턴 기여 |
|---|---|---|
| `d2aa709` | Add runtime multiturn session context | recent window + rolling summary, 모델 입력 블록 도입 |
| `f98399e` | Let conversation references reach model history | 대화 참조 발화를 명함 검색 쿼리로 오인하지 않게 차단 |
| `5f2c988` | Add structured multiturn memory | 평문 summary를 구조화 `ConversationMemory`로 대체 |

나머지 커밋(`b80a685` ryeong 검색, `43512ce` Gemma 4 빌드, `d0dd11b` 초기 에이전트)은
멀티턴과 무관하다. 브랜치에만 있는 `agent-routing`, `llm-litert-common`,
`desktop-agent-runner`, `tool-external-actions`, buildSrc variant 패키징도 멀티턴 기능이
아니므로 이번 이식 범위에서 제외했다.

### 1.2 참조 브랜치의 멀티턴 설계

**상태 모델** (`agent-contract`)

```kotlin
ConversationMemory(
  schemaVersion, topic,
  confirmedFacts, preferences, constraints,   // List<ConversationMemoryItem>
  pendingActions, resolvedActions,
  historyDigest,
)
ConversationMemoryItem(content, sourceTurnId, updatedAtEpochMillis, confidence)
MemoryConfidence { EXPLICIT, TOOL_VERIFIED }
```

**갱신 규칙** (`ConversationMemoryReducer`, 결정적. 추가 모델 호출 0회)

- `beginTurn`: 현재 요청을 `topic`으로 두고 `pendingActions`에 넣는다.
- `recordTurn`: 같은 `turnId`의 pending을 `resolvedActions`로 옮기고, 실행된 도구를
  `"도구 실행 완료: <tool>"` + `TOOL_VERIFIED`로 기록한다.
- 한국어 marker 기반 분류: 선호(`선호/좋아/싫어/말투/스타일/앞으로`),
  제약(`하지 마/말아/반드시/꼭/전에 확인/동의 없이`),
  사실(`나는 /내 이름/내 회사/내 직책/기억해/라고 불러`).
- 버킷당 8개, 항목 300자, topic 240자 상한. 내용 기준 dedup 후 tail 유지.
- recent window(기본 8개)에서 밀려난 메시지는 `historyDigest`(기본 1,500자)로 압축한다.
  넘치면 앞에서 잘라내되 줄 경계에서 자른다.

**모델 입력 형태** (`LiteRtNativeAgentModelGateway`)

```text
[conversation_memory]
schema_version: 1
topic: …
confirmed_facts: / preferences: / constraints: / pending_actions: / resolved_actions:
history_digest: …

[recent_conversation]
User: …
Assistant: …

[current_user]
<현재 요청>

[tool_session_context]
<tool이 저장한 안전한 세션 상태>
```

빈 메모리는 빈 문자열로 렌더링되므로 **첫 턴 프롬프트는 단일턴과 완전히 동일하다.**

**대화 참조 정책** (`f98399e`): `방금/아까/앞서/이전/지금까지/그동안` + `찾은/검색한/조회한`
+ `사람/명함/연락처` 패턴은 검색 쿼리 추출에서 제외한다. 그렇지 않으면
"방금 찾은 사람 누구야?"가 `query="방금 사람"` 같은 잘못된 검색으로 실행된다.

### 1.3 이식 전 현재 에이전트의 실제 상태

현재 워크트리에는 커널이 둘 있다. **프로덕션 조립 경로는 ReAct 커널 하나뿐이다.**

| 구성요소 | 조립 위치 | 멀티턴 상태(이식 전) |
|---|---|---|
| `AgentKernel` (ReAct + native tool calling) | `AppContainer.kt:93` → 앱이 실제로 사용 | 대화 이력 **전무**. `ModelInput.User(text, safeCapabilityContext)`만 전달 |
| `StructuredAgentKernel` (2-stage intent orchestrator) | 조립 안 됨(테스트에서만 생성) | 턴마다 conversation을 새로 열어 **완전 stateless** |
| `LiteRtAgentModelGateway` | `AppContainer` | `[tool_session_context]`만 렌더링 |
| `LiteRtStructuredAgentModelGateway` | 조립 안 됨 | stage별 새 conversation, 이력 없음 |
| `LocalToolRoutingModelGateway`(에뮬레이터) | `AppContainer` | 턴 간 상태 없음, 대화 참조 가드 없음 |

이식 전 에이전트가 실제로 가지고 있던 "멀티턴"은 다음 3개뿐이었다.

1. **tool 세션 상태**: `search_contacts` → `contact.last_search_results`,
   `get_contact` → `contact.selected_contact`를 `SessionStateUpdate`로 저장하고
   `AgentKernel.buildCapabilityContext()`가 `[tool_session_context]`로 넘긴다.
   대화 내용이 아니라 **도구 산출물**만 담긴다.
2. **`ContactTurnReferenceResolver`**: "그 사람 / 그분 / 두 번째" 같은 지시 표현을
   세션 상태에 근거해 이름으로 치환한다. 그러나 이 코드는 `StructuredAgentKernel`에서만
   호출되므로 **앱에서는 동작하지 않는다.**
3. **LiteRT-LM conversation의 native 이력**: `AgentSessionManager`가 모델 세션을 재사용하는
   동안에만 유지된다. 카탈로그 revision이 바뀌면 세션을 다시 열어 이력이 사라지고,
   에뮬레이터 라우터에는 애초에 이력 개념이 없다.

**추가로 확인된 사실**: `search-core`의 `AgentSessionState`(40개 대명사 회귀 테스트
`ContactMultiTurnSessionTest`가 검증하는 클래스)는 `SearchLookupService.retrieveForAgent`
에서만 참조되고, 에이전트 런타임(`RyeongContactSearchBackend`)은 `retrieve`만 호출한다.
즉 **"멀티턴 40/40"은 에이전트 실행 경로에 연결되지 않은 라이브러리 회귀 테스트**다.
이 수치는 실제 앱의 멀티턴 동작을 보증하지 않는다.

### 1.4 결론적 격차

| 항목 | 참조 브랜치 | 이식 전 현재 | 격차 |
|---|---|---|---|
| 이전 턴 발화 전달 | recent 8개 | 없음 | 치명적 |
| 오래된 대화 압축 | history digest 1,500자 | 없음 | 치명적 |
| 선호·제약 누적 | marker 기반 구조화 | 없음 | 큼 |
| 미완료 작업 이어가기 | pending/resolved 전이 | 없음 | 큼 |
| 도구 실행 이력 | 이름만 `TOOL_VERIFIED` | 없음 | 중간 |
| 도구 산출물 상태 | 있음 | **있음** | 없음 |
| 지시 표현 해소 | (없음) | `ContactTurnReferenceResolver` | 현재가 우위(단, 미조립) |
| 대화 참조 오라우팅 차단 | 있음 | 없음 | 중간 |

---

## 2. 적용

### 2.1 적용 원칙

1. 참조 브랜치의 자료구조·reducer·프롬프트 블록은 **의미를 바꾸지 않고 그대로** 옮긴다.
2. 앱이 실제로 쓰는 `AgentKernel` 경로를 1순위로 연결한다.
3. 평가가 고정된 구조화 파이프라인은 **빈 메모리에서 프롬프트가 바이트 단위로 동일**하도록
   만들어 기존 단일턴 평가 결과를 무효화하지 않는다.
4. 대화·명함 PII는 in-memory 세션에만 두고 로그·영구 저장에 남기지 않는다(CLAUDE.md 준수).

### 2.2 변경 파일

**계약 (`agent-contract`)**

- `AgentModel.kt`
  - `ConversationMemory`, `ConversationMemoryItem`, `MemoryConfidence`,
    `ModelConversationMessage`, `ModelConversationRole` 추가(브랜치와 동일 정의).
  - `ModelInput.User`에 `conversationMemory`, `recentMessages` 추가(기본값 있음 → 기존 호출부 무변경).
  - `ConversationContext(memory, recentMessages)` 신설. native conversation을 갖지 않는
    구조화 stage에 이력을 넘기기 위한 값 객체이며 `isEmpty`를 제공한다.
- `StructuredAgentModel.kt`
  - `analyzeIntent(userText, conversation = ConversationContext())`로 확장.
  - `ComposeContentRequest`, `StructuredFinalRequest`에 `conversation` 필드 추가.

**코어 (`agent-core`)**

- `AgentSession.kt`
  - `AgentSession`에 `conversationMemory`, `recentMessages`, `conversationContext()` 추가.
  - `ConversationMemoryPolicy(maxRecentMessages = 8, maxHistoryDigestChars = 1_500)` 신설.
  - `AgentSessionStore.beginConversationTurn` / `recordConversationTurn` 확장 함수로
    두 커널이 같은 갱신 로직을 공유한다.
  - `AgentSessionManager`에 `clockMillis`, `beginTurn`, `recordTurn` 추가.
  - `ConversationMemoryReducer` 이식(`beginTurn`, `reduceCompletedTurn`, `mergeHistoryDigest`).
    브랜치에서 manager private 메서드였던 digest 병합만 reducer로 옮겨 중복을 없앴다.
- `AgentKernel.kt`
  - `AgentTurnPolicy.conversationMemory: ConversationMemoryPolicy` 추가.
  - 턴 시작 시 `beginTurn`, `decide`에 메모리·recent 전달, 최종 응답 후 `recordTurn`.
  - `executedTools`를 별도로 수집한다.
- `StructuredAgentKernel.kt`
  - `memoryPolicy`, `clockMillis` 파라미터 추가.
  - `beginConversationTurn` → `session.conversationContext()`를 `analyzeIntent`,
    `generateComposeContent`, `generateFinalText`에 전달.
  - 실행 성공 도구만 `executedTools`에 수집하고 완료 시 `recordConversationTurn`.

**모델 게이트웨이 (`llm-litert`)**

- `ConversationPromptFormat.kt` **신규**: `formatConversationMemory`,
  `formatRecentConversation`, `ConversationContext.toPromptPrefix()`.
  브랜치의 `LiteRtNativeAgentModelGateway` 내부 private 함수를 모듈 공용으로 승격했다.
- `LiteRtAgentModelGateway.kt`: 요청을
  `memory + recent + text + tool_session_context` 순으로 조립.
- `LiteRtStructuredAgentModelGateway.kt`: stage1/stage2/content/final 프롬프트 앞에
  `toPromptPrefix()`를 붙인다. 빈 컨텍스트면 빈 문자열이므로 기존 프롬프트와 동일하다.

**에뮬레이터 라우터 (`app`)**

- `LocalToolRoutingModelGateway.kt`
  - `ContactSearchPromptParser`에 브랜치의 `conversationReferenceIntent` 정규식을 그대로
    이식하고 `isConversationReference()`로 노출.
  - 라우팅 우선순위(DateTime → Update → Compose → Calendar → ContactSearch)는 그대로 두어
    "방금 찾은 사람에게 메일 보내줘" 같은 실행 요청은 계속 compose로 간다.
  - 어떤 파서도 매칭되지 않은 대화 참조 질문은 일반 안내문 대신
    `conversationRecap()`으로 **이 세션의 직전 assistant 메시지에 근거해서만** 답한다.
    이력이 없으면 없다고 말하고 이름을 지어내지 않는다.

### 2.3 브랜치와 의도적으로 다르게 한 부분

| 항목 | 브랜치 | 이번 적용 | 이유 |
|---|---|---|---|
| `executedTools` 산정 | `observations.map { it.modelToolName }` | **실행 성공한 도구만** | 현재 커널은 rejection 응답도 `observations`에 넣는다. 그대로 쓰면 거부·실패한 호출이 `"도구 실행 완료"`로 기록되어 다음 턴에 거짓 완료를 주장하게 된다 |
| digest 병합 위치 | manager private 메서드 | reducer로 이동 | 두 커널이 공유 |
| 정책 파라미터 | `AgentTurnPolicy`의 평면 필드 2개 | `ConversationMemoryPolicy`로 묶음 | 두 커널이 같은 정책 객체를 받도록 |
| 구조화 파이프라인 | 대상 아님 | 컨텍스트 주입 | 현 저장소에 존재하는 두 번째 커널까지 멀티턴을 일관되게 적용 |
| 에뮬레이터 대화 참조 | 가드만(뒤에 모델이 있음) | 가드 + 세션 근거 recap | 에뮬레이터에는 뒤를 받아줄 모델이 없다 |

### 2.4 안전성·평가 영향

- **첫 턴 프롬프트 불변**: `ConversationMemory()`와 빈 recent에서 두 포맷터가 빈 문자열을
  반환하므로, 단일턴 프롬프트는 이식 전과 바이트 단위로 같다. 기존 held-out/validation
  단일턴 평가 수치는 그대로 유효하다. 멀티턴 세션 태스크의 프롬프트만 달라진다.
- **PII**: 메모리는 `InMemoryAgentSessionStore`에만 존재하고 세션 초기화·프로세스 종료 시
  사라진다. 도구 결과 payload와 명함 필드는 메모리에 복사하지 않고 **도구 이름만** 남긴다.
  이번 변경으로 추가된 로그 출력은 없다(`llm-litert`의 기존 backend 초기화 경고만 존재).
- **추가 추론 비용 0**: 요약은 결정적 reducer로 만들며 별도 모델 호출을 하지 않는다.
  증가하는 것은 프롬프트 길이뿐이고 recent 8개·digest 1,500자·메시지 1,000자 상한으로 묶인다.

---

## 3. 결과

### 3.1 검증

| 항목 | 결과 |
|---|---|
| `./gradlew test` (JDK 21) | **BUILD SUCCESSFUL** |
| JVM 테스트 총계 | **103개, 실패 0, 오류 0, skip 0** (이식 전 93개) |
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** |
| APK | `app/build/outputs/apk/debug/app-debug.apk`, 322,567,309 bytes |
| APK SHA-256 | `f37b476e0edf4d0499be099ebef7b078ab21737118723e4673bce933fc77fbab` |

기존 93개 테스트는 하나도 수정하지 않았고 모두 통과한다.

### 3.2 추가된 회귀 테스트 10개

`AgentKernelTest` (ReAct 프로덕션 경로, 브랜치 테스트 이식 + 1개 신규)

1. `recent conversation is passed to the next model turn` — 2번째 턴 입력에 직전 user/assistant 2개가 실린다.
2. `completed turns update structured memory and older conversation rolls into digest` —
   window 2로 좁혔을 때 1턴이 digest로 밀리고 `resolvedActions`에 직전 요청이 남는다.
3. `explicit preferences and constraints are retained as structured memory` —
   "앞으로 정중한 말투를 선호해. 전송 전에 꼭 확인해줘."가 preference 1 + constraint 1로 분리된다.
4. `failed turn remains pending for the next turn` — `Invalid`로 끝난 턴은 `pendingActions`에 남는다.
5. `only successfully executed tools become tool verified memory` **(신규)** —
   도구 실행이 실패하면 `"도구 실행 완료"` 항목을 만들지 않는다.

`StructuredAgentKernelTest` (구조화 경로, 신규 2개)

6. `completed structured turn is carried into the next turn as memory` —
   2번째 턴의 `analyzeIntent`/`generateFinalText`가 동일한 `ConversationContext`를 받고,
   그 안에 recent 2개 + `도구 실행 완료: search_contacts` + 현재 요청 pending이 들어 있다.
7. `structured turn that fails before completion stays pending` —
   슬롯 부족으로 중단된 턴은 pending으로 남고 recent에는 들어가지 않는다.

`ContactSearchPromptParserTest` / `LocalToolRoutingModelGatewayTest` (에뮬레이터 라우터, 신규 3개)

8. `does not route conversation references as contact search` — 브랜치 f98399e 회귀.
9. `conversation reference is answered from this session instead of a new search` —
   직전 assistant 메시지를 근거로 답한다.
10. `conversation reference without any prior turn does not invent a result` —
    이력이 없으면 없다고 답한다.

### 3.3 이식으로 실제로 달라지는 동작

- 앱에서 두 번째 턴부터 모델이 직전 대화를 본다. 이식 전에는 도구 세션 상태만 보였다.
- 사용자가 말한 말투·확인 절차 요구가 세션 내내 `preferences`/`constraints`로 유지된다.
- 실패·취소된 요청이 `pendingActions`에 남아 다음 턴에서 이어갈 근거가 된다.
- 에뮬레이터에서 "방금 찾은 사람 누구야?"가 엉뚱한 명함 검색으로 실행되지 않는다.
- 카탈로그 revision 변경으로 모델 세션이 재생성되어도 대화 맥락이 유지된다
  (메모리는 `AgentSession`에 있고 모델 conversation과 수명이 분리되어 있다).

### 3.4 남은 항목

1. **`StructuredAgentKernel` 미조립(의도된 보류)**: 이번 작업으로 구조화 커널도 멀티턴을
   지원하지만, `AppContainer`는 여전히 `AgentKernel`을 조립한다. 이는 방치된 죽은 코드가
   아니라 **production gate 미통과로 명시적으로 보류된 후보 구현**이다.
   `docs/GEMMA4_INTENT_ORCHESTRATOR_EVALUATION.md:11`과
   `docs/GEMMA4_STAGED_INTENT_EVALUATION.md:11-13`이 "production gate 미달이므로
   `AppContainer`의 gateway를 `StructuredAgentKernel`로 교체하지 않았다"고 기록한다.
   전환 판단은 gate 재평가의 몫이며 이번 변경 범위가 아니다.
   다만 `LiteRtStructuredAgentModelGateway`는 main·test 어디에서도 인스턴스화되지 않아
   (선언부 외 참조 0) 한 번도 실행된 적이 없다. 전환 시 첫 번째 검증 대상이다.
2. **`ContactTurnReferenceResolver` 적용 범위**: 지시 표현 해소는 여전히 구조화 커널에만
   있다. ReAct 경로에서는 대명사 해소를 모델이 recent 대화를 보고 직접 수행한다.
3. **`search-core.AgentSessionState` 사문화**: 40개 멀티턴 회귀가 검증하는 클래스가
   런타임에서 호출되지 않는다. 삭제하거나 실제 경로에 연결하는 정리가 필요하다.
4. **온디바이스 멀티턴 재평가 미실시**: 이번 문서의 결과는 JVM 테스트와 빌드까지다.
   물리 ARM64에서 Gemma 4 E2B로 멀티턴 세션 태스크 12개를 다시 돌린 수치는 없다.
   단일턴 프롬프트가 불변이므로 기존 단일턴 수치는 유효하지만, 멀티턴 세션 지표는
   재측정 대상이다.
5. **marker 기반 분류의 한계**: 선호·제약 판정이 한국어 어휘 목록에 의존하므로
   완곡 표현("굳이 안 그래도 돼")은 잡히지 않는다. 브랜치 설계 그대로의 제약이다.

---

## 4. 재현 방법

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test
./gradlew :agent-core:test --tests "com.hjp.agent.core.AgentKernelTest"
./gradlew :agent-core:test --tests "com.hjp.agent.core.StructuredAgentKernelTest"
./gradlew :app:assembleDebug
```

참조 브랜치 원본을 다시 보려면:

```bash
git fetch origin 'refs/heads/agent/structured-multiturn-memory:refs/remotes/origin/agent/structured-multiturn-memory'
git show 5f2c988 -- agent-core/src/main/kotlin/com/hjp/agent/core/AgentSession.kt
```
