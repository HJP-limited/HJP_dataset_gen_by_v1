# HJP Desktop Agent Runner 실행·검증 가이드

## 1. 목적

`desktop-agent-runner`는 Android Studio, 에뮬레이터, 실기기 없이 Apple Silicon Mac에서 Android 앱의 에이전트 계약과 실제 도구 pipeline을 검증하는 Kotlin/JVM CLI다.

```text
사용자 입력
→ 실행 모드별 모델/routing gateway
→ ModelToolCall
→ AgentKernel
→ validation / policy
→ DefaultToolRegistry / DefaultToolExecutor
→ 실제 명함 도구 또는 Desktop-safe mock
→ ModelToolResponse
→ 같은 모델 conversation 또는 grounded formatter
→ 최종 응답
```

## 2. 네 가지 실행 모드

| 모드 | deterministic router | 실제 모델 | AgentKernel/ToolRegistry | 용도 |
|---|---:|---:|---:|---|
| `full` | 먼저 사용 | router가 처리하지 못할 때 CLI subprocess | 사용 | 기존 routing-first 전체 흐름 |
| `router-only` | 사용 | 사용 안 함 | 사용 | 빠르고 결정적인 pipeline 회귀 검증 |
| `model-only` | 사용 안 함 | CLI subprocess | 사용 안 함 | 모델 raw text만 확인 |
| `model-agent` | **완전 우회** | **공식 Kotlin/JVM API로 항상 사용** | **사용** | 모델 자신의 tool 선택·argument·연속 호출 평가 |

`model-only` 성공은 에이전트 tool-calling 성공이 아니다. 실제 모델이 tool을 고르는지는 반드시 `model-agent`로 확인한다.

### 2.1 `full`의 메일·SMS hybrid compose 경로

메일·SMS는 Gemma 3의 native `Message.toolCalls`에 의존하지 않는다.

```text
compose intent 및 수신자 parsing
→ 직접 주소 사용 또는 search_contacts → get_contact
→ 사용자가 쓴 본문은 원문 보존
→ 목적만 있으면 Gemma 3에 JSON 초안 생성 요청
→ 초안 validation / safe fallback
→ application_orchestrator가 ModelToolCall(open_compose) 생성
→ 기존 AgentKernel / DefaultToolRegistry / OpenComposePlugin
→ Desktop mock 또는 Android Intent backend
```

Gemma 3에는 이 단계에서 tool schema를 주지 않는다. 메일은 `subject`와 `body`, SMS는 `body`만 JSON으로 요청한다. debug 로그에서 다음 두 값을 구분한다.

```text
draft_source=gemma3-1b-it-int4
tool_call_source=application_orchestrator
```

직접 이메일:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode full --debug --prompt "test@example.com한테 지난번 미팅에 대한 감사 내용을 담아서 메일 작성해줘."'
```

이름 기반 SMS:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode full --debug --prompt "김지원에게 지난 상담에 감사하고 다음 주에 다시 연락드리겠다는 문자를 작성해줘."'
```

명시 본문:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode full --debug --prompt "test@example.com에게 내용은 안녕하세요 라고 메일 작성해줘."'
```

명시 본문에서는 `[DRAFT_MODEL] draft_source=user_provided`이며 실제 모델 subprocess를 호출하지 않는다.

compose debug 단계:

```text
[COMPOSE_INTENT]
[RECIPIENT_RESOLUTION]
[DRAFT_REQUEST]
[DRAFT_MODEL]
[DRAFT_RAW_OUTPUT]
[DRAFT_VALIDATION]
[GENERATED_TOOL_CALL]
[TOOL_EXECUTION]
[TOOL_RESULT]
[FINAL_RESPONSE]
```

수신자가 이름이면 검색 1건일 때만 진행한다. 검색 0건, 여러 건, 명함에 필요한 이메일/전화번호가 없는 경우에는 `open_compose`를 만들지 않는다. 주소는 tool result에 있는 값만 사용할 수 있다.

이전 대화는 현재 compose 요청이 `지난`, `앞서`, `그때`처럼 참조할 때만 사용한다. 최근 3개 사용자 입력 중 관련 미팅/상담/회의 맥락 하나를 최대 500자로 제한해 draft request에 넣으며, 저장되지 않은 상세 내용은 만들지 않는다.

## 3. model-agent의 정확한 구조

`model-agent`는 `RoutingFirstAgentModelGateway`와 `LocalToolRoutingModelGateway`를 생성하지 않는다.

```text
LiteRT-LM Engine
→ ConversationConfig(
    systemInstruction,
    tools = ToolContract 기반 OpenAPI definitions,
    automaticToolCalling = false
  )
→ sendMessage(user input)
→ Message.toolCalls
→ 공통 ModelToolCall
→ 기존 AgentKernel
→ 기존 DefaultToolRegistry
→ 실제 plugin 실행
→ Content.ToolResponse
→ 동일 Conversation.sendMessage
→ 후속 Message.toolCalls 또는 최종 message
→ grounding validation
```

사용되는 실제 plugin은 다음과 같다.

- `SearchContactsPlugin`
- `GetContactPlugin`
- `UpdateBusinessCardPlugin`
- `GetCurrentDateTimePlugin`
- `CreateCalendarEventPlugin` + Desktop mock backend
- `OpenComposePlugin` + Desktop mock backend

명함 데이터는 Android와 동일한 `app/src/main/assets/cards/business_cards.json`을 읽는다.

## 4. 공식 LiteRT-LM JVM API

현재 Gradle 의존성은 다음과 같다.

```text
com.google.ai.edge.litertlm:litertlm-jvm:0.14.0
com.google.ai.edge.litertlm:litertlm-android:0.14.0
```

공식 JVM jar에는 macOS arm64 네이티브 라이브러리와 `Engine`, `ConversationConfig`, `Message.toolCalls`, `Content.ToolResponse`, `OpenApiTool`이 포함돼 있다. 공통 변환 코드는 `llm-litert-common`에 있고 Android `llm-litert` facade와 Desktop이 함께 사용한다.

`full`과 `model-only`의 호환 경로만 Python CLI subprocess를 사용한다.

## 5. 필요한 환경

- Apple Silicon Mac
- JDK 21
- 저장소 Gradle wrapper
- `models/gemma3-1b-it-int4.litertlm`
- subprocess 모드 사용 시 `$LITERT_LM_BIN` 0.14.0

선택 검증된 Gemma 4 파일:

```text
model_id=gemma4-e2b-it
file_name=gemma-4-E2B-it.litertlm
size=2,588,147,712 bytes
```

Gemma 4 파일은 저장소에 포함하지 않는다. 실제 절대 경로는 환경마다 다르므로
`--model` 또는 `LITERT_LM_MODEL`로 지정한다.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

CLI 설치 확인:

```bash
"$LITERT_LM_BIN" --version
"$LITERT_LM_BIN" --help
"$LITERT_LM_BIN" run --help
```

## 6. 경로와 환경변수

```bash
export LITERT_LM_BIN="$HOME/litert-lm-env/bin/litert-lm"
export LITERT_LM_MODEL="$PWD/models/gemma3-1b-it-int4.litertlm"
export LITERT_LM_MODEL_ID="gemma3-1b-it-int4"
export LITERT_LM_BACKEND=cpu
export LITERT_LM_TIMEOUT_SECONDS=900
export LITERT_LM_CACHE_DIR="$PWD/desktop-agent-runner/build/cache/litertlm"
export HJP_CARD_DATA="$PWD/app/src/main/assets/cards/business_cards.json"
```

환경변수가 없으면 위와 같은 저장소·home 기반 기본값을 계산한다. CLI 옵션
`--litert-bin`, `--model`, `--model-id`, `--backend`, `--timeout-seconds`,
`--cache-dir`, `--cards`가 환경변수보다 우선한다.

Gemma 4 선택 예:

```bash
export LITERT_LM_MODEL="/absolute/path/gemma-4-E2B-it.litertlm"
export LITERT_LM_MODEL_ID="gemma4-e2b-it"
```

또는:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode model-agent --debug --model "/absolute/path/gemma-4-E2B-it.litertlm" --model-id gemma4-e2b-it --prompt "김지원 명함을 찾아줘."'
```

`--model-id gemma4-e2b-it`에 대응하는 파일이 없으면 Gemma 3로 fallback하지
않고 정확한 누락 경로를 출력한다. 기본 모델은 평가 기준 미달 때문에 계속
`gemma3-1b-it-int4`다.

한글·공백이 있는 경로는 shell 문자열로 조합하지 않는다. subprocess도 `ProcessBuilder(List<String>)`에 각 인자를 따로 전달한다.

## 7. 실행 명령

대화형 모드는 여러 문장을 처리하며 `exit` 또는 `quit`으로 종료한다.

```bash
./gradlew :desktop-agent-runner:run
./gradlew :desktop-agent-runner:run --args='--mode router-only'
./gradlew :desktop-agent-runner:run --args='--mode model-agent --debug'
```

단일 prompt:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode model-agent --debug --prompt "김지원 명함을 찾아줘."'

./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --prompt "김지원 명함을 찾아줘."'

./gradlew :desktop-agent-runner:run \
  --args='--mode model-only --prompt "한 문장으로 인사해 주세요."'
```

`model-only`는 시작 시 ToolRegistry와 명함 DB를 사용하지 않는다는 경고를 출력한다.

## 8. model-agent debug 로그

`--debug`에서 다음 stage를 확인할 수 있다.

```text
[INPUT]
[ROUTING_POLICY]
[MODEL_REQUEST]
[MODEL_TOOL_DEFINITIONS]
[MODEL_RAW_OUTPUT]
[MODEL_DECISION_SOURCE]
[PARSED_TOOL_CALL]
[TOOL_EXECUTION]
[TOOL_RESULT]
[TOOL_RESPONSE_SENT_TO_MODEL]
[MODEL_FINAL_RESPONSE]
[GROUNDING_VALIDATION]
[FINAL_RESPONSE]
```

model-agent에는 반드시 다음 값이 보인다.

```text
deterministic_router_bypassed=true
decision_source=gemma3-1b-it-int4
```

모델이 tool call을 만들지 않으면 해당 단계는 `none`, `not_executed`, `not_sent`로 표시된다. router가 만든 call과 모델 call은 각각 `라우팅 결과`와 `모델 도구 호출`로 구분한다.

## 9. tool definition과 argument validation

`ToolCatalogSnapshot.contractsByModelName`을 이름순으로 정렬하고 각 `ToolContract`를 다음 구조의 OpenAPI tool description으로 변환한다.

```json
{
  "name": "search_contacts",
  "description": "이름, 회사, 직함, 지역, 업종, 메모를 기준으로 로컬 명함을 검색합니다.",
  "parameters": {
    "type": "object",
    "properties": {},
    "required": ["query"],
    "additionalProperties": false
  }
}
```

LiteRT-LM의 `automaticToolCalling`은 `false`다. 모델이 만든 arguments는 `ModelToolCall`로 변환된 뒤 기존 codec과 `DefaultToolExecutor`가 required field, 타입, enum, 빈 값, 알 수 없는 tool을 검증한다.

## 10. Grounding 정책

연락처 tool 결과는 반드시 동일 model conversation에 `Content.ToolResponse`로 전달한다. 후속 모델 응답이 또 다른 `Message.toolCalls`를 가지면 AgentKernel이 다음 tool을 실행한다.

연락처 관련 최종 문장은 `GroundedToolResultFormatter`가 tool payload만으로 만든다.

- 결과에 없는 이름·회사·직책·전화번호·이메일을 출력하지 않음
- 검색 결과 0개면 `찾지 못했습니다`
- 여러 결과면 모두 표시하고 임의로 한 명을 고르지 않음
- 모델이 tool-required 요청에 tool을 전혀 호출하지 않으면 안전 메시지로 차단
- 모델의 raw hallucination은 debug/eval 보고서에는 남지만 사용자 최종 응답으로는 노출하지 않음

Desktop 명함 수정은 실제 `UpdateBusinessCardPlugin`을 실행하지만 원본 Android asset을 수정하지 않고 현재 프로세스 메모리에만 유지한다.

## 11. Android 전용 기능

Desktop은 Android `Context`, `Intent`, `Activity`, 권한 요청, 실제 Calendar/Email/SMS 앱을 실행하지 않는다.

- 캘린더: `DesktopCalendarComposerBackend`
- 이메일/SMS: `DesktopMessageComposerBackend`
- 현재 시각: 실제 JVM clock과 Mac timezone

mock 결과에는 `status=mock_success`, tool 이름, 일정 또는 메시지 초안, 실제 Intent를 실행하지 않았다는 설명이 포함된다. Android 앱은 계속 `tool-android-intents`의 실제 backend를 사용한다.

## 12. 테스트

일반 단위·통합 테스트는 실제 584MB 모델을 실행하지 않는다.

```bash
./gradlew :desktop-agent-runner:test
./gradlew :agent-routing:test :agent-core:test :tool-contact:test :tool-datetime:test :search-core:test
```

검증 항목:

- subprocess child stdin EOF
- stdout/stderr 수집
- timeout 시 child와 reader thread 정리
- 한글·공백 모델 경로 인자 보존
- model-agent deterministic router 우회
- 모델-originated tool call의 AgentKernel/Registry 실행
- missing/empty/wrong-type arguments
- unknown tool
- 실제 Android 명함 asset 로딩과 검색
- 결과 0개 grounding
- no-tool fixture
- Desktop calendar/email/SMS mock

실제 모델 smoke:

```bash
RUN_LITERT_LM_SMOKE_TEST=1 ./gradlew :desktop-agent-runner:modelSmokeTest
```

## 13. 실제 모델 평가

평가 dataset:

```text
desktop-agent-runner/src/modelEval/resources/gemma3_tool_eval.jsonl
```

53개 case가 다음 범주를 포함한다. email/SMS 11건은 `full` hybrid compose 경로로, 나머지는 `model-agent` native tool-calling 경로로 평가한다.

```text
contact_search, contact_lookup, contact_update, calendar, email, sms,
current_datetime, no_tool, ambiguous, nonexistent_contact, multi_step
```

실행:

```bash
./gradlew :desktop-agent-runner:modelAgentEval
```

결과:

```text
desktop-agent-runner/build/reports/model-eval/result.json
desktop-agent-runner/build/reports/model-eval/result.csv
```

계산 지표:

```text
tool_call_parse_rate
tool_selection_accuracy
argument_accuracy
tool_sequence_accuracy
tool_execution_success_rate
no_tool_false_positive_rate
grounded_answer_rate
hallucinated_contact_count
compose_intent_accuracy
recipient_resolution_accuracy
draft_generation_success_rate
draft_json_parse_rate
open_compose_execution_rate
recipient_hallucination_count
```

각 실패에는 prompt, expected tools, actual tools, actual arguments, raw model output, tool result, final answer, failure reason이 기록된다.

## 14. 현재 모델의 실제 결과

2026-07-23 CPU 평가에서 `gemma3-1b-it-int4`는 tool-required 47건 모두 일반 텍스트를 만들었고 native `Message.toolCalls`는 0건이었다. no-tool 4건만 tool을 호출하지 않는 결정을 올바르게 냈다.

이 결과는 gateway 초기화 실패나 parser 실패가 아니다. `[MODEL_RAW_OUTPUT]`에 실제 model message가 있고, 공식 API의 `Message.toolCalls`가 비어 있었다. 현재 모델은 Gemma 3 IT 모델이며, LiteRT-LM 공식 문서가 tool use 예시로 드는 FunctionGemma 계열과 동일한 tool-calling 학습을 전제할 수 없다.

안전 게이트 적용 후 사용자가 보는 연락처 환각은 0건이다. 다만 모델이 tool call을 생성하지 않았으므로 실제 모델-originated tool execution 성공률은 0이다.

## 15. subprocess blocker 수정

CLI는 비-TTY stdin에서 `sys.stdin.read()`를 호출한다. runner는 `ProcessBuilder.start()` 직후 다음을 실행한다.

```kotlin
process.outputStream.close()
```

따라서 EOF를 기다리는 CLI가 즉시 진행한다. stdout/stderr는 전용 daemon reader 두 개가 병렬 수집하며 timeout 시 process destroy/forcible destroy, stream close, future cancel, executor shutdown/await까지 수행한다.

subprocess cache는 `memory`를 사용하므로 모델 디렉터리에 새 XNNPACK disk cache를 만들지 않는다.

## 16. XNNPACK cache와 standalone asset

공식 JVM Engine cache는 다음으로 분리했다.

```text
desktop-agent-runner/build/cache/litertlm
```

Android standalone source set은 `models/` 전체를 보지 않는다. `stageStandaloneModelAsset`이 정확히 모델 하나만 generated asset 디렉터리에 복사한다. `verifyStandaloneMergedAssets`는 merge 결과가 다음 두 파일과 정확히 일치하는지 자동 검사한다.

```text
gemma3-1b-it-int4.litertlm
cards/business_cards.json
```

`*.xnnpack_cache_*`는 허용되지 않는다.

```bash
./gradlew :app:mergeStandaloneAssets
```

## 17. 문제 해결

### LiteRT-LM 실행 파일을 찾을 수 없습니다

`full`/`model-only`용 CLI 경로를 확인한다.

```bash
test -x "$LITERT_LM_BIN"
"$LITERT_LM_BIN" --version
```

### 모델 파일을 찾을 수 없습니다

```bash
test -r "$LITERT_LM_MODEL"
ls -lh "$LITERT_LM_MODEL"
```

### 한글 또는 공백 경로

환경변수 값 전체를 quote한다. 내부에서는 인자 배열을 사용하므로 경로를 shell split하지 않는다.

### subprocess 종료 코드가 0이 아님

오류 메시지의 exit code와 stderr 마지막 부분을 확인한다. 같은 모델/backend의 짧은 `model-only` 요청으로 재현한다.

### subprocess timeout

`--timeout-seconds 900`을 확인한다. timeout 뒤 process와 reader thread는 정리된다. 첫 CPU 실행은 cache 생성으로 더 오래 걸릴 수 있다.

### 모델 출력이 tool call이 아님

`model-agent`는 문자열 JSON parser가 아니라 `Message.toolCalls`를 사용한다. `[MODEL_RAW_OUTPUT]`, `[PARSED_TOOL_CALL] none`, `[GROUNDING_VALIDATION]`을 확인한다. 현재 Gemma 3 IT 모델의 실제 평가에서는 이 현상이 모든 tool-required case에서 발생했다.

### 명함 asset을 찾을 수 없음

기본 경로는 `app/src/main/assets/cards/business_cards.json`이다. `HJP_CARD_DATA` 또는 `--cards`로 덮어쓸 수 있다.

## 18. Android 보존 경계

- Android 실기기 routing-first 정책 유지
- Android emulator deterministic fallback 유지
- Android 실제 Calendar/Email/SMS Intent 유지
- Android Room 명함 저장 유지
- Desktop mock은 Desktop module에만 존재
- `llm-litert-common`은 native API 변환만 공유
- Desktop JVM runtime dependency는 Android APK graph에 들어가지 않음
- debug APK에는 standalone 모델이 포함되지 않음
- standalone에는 모델과 명함 JSON만 포함
