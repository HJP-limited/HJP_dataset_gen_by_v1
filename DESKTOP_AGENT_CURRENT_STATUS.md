# Desktop Agent Runner 현재 구현·검증 상태 보고서

> Android `Agent_Gemma_4_E2B_0711` 배포 상태는 루트 `README.md`와 `docs/`가
> 기준이다. 이 문서는 Gemma 3/Gemma 4 Desktop 비교 환경과 과거 검증 이력을
> 보존한다.
>
> 2026-07-24 Gemma 4 E2B 비교 업데이트: 선택적 모델 ID/경로, 공식 JVM native
> tool-calling, 모델별 평가·성능 보고서, Android 선택형 standalone을 구현하고
> 실제 Gemma 3/Gemma 4 CPU 평가를 완료했다. 최신 상세 결과는
> `GEMMA4_E2B_EVALUATION.md`를 우선한다. Gemma 4는 권장 정확도 기준에 미달해
> 기본 모델로 강제 전환하지 않았다.
>
> 2026-07-24 compose pipeline 업데이트: 아래 `0-B` 절이 메일·SMS 작성의 최신 상태다. `0-A`의 native tool-calling 평가는 그대로 유효하지만, 메일·SMS는 더 이상 Gemma 3의 `Message.toolCalls` 성공에 의존하지 않는다.
>
> 문서 뒤쪽에 남아 있는 “subprocess stdin 미종료”, “full/model-only blocker”, “51개 평가” 같은 문구는 당시 분석 기록이다. 최신 판정은 `0-A`와 `0-B`를 우선한다.

## 0-B. 자연어 의도 기반 메일·SMS hybrid compose pipeline

### 문제 원인과 해결 결론

기존 compose router는 직접 주소와 사용자가 그대로 말한 본문은 `open_compose`로 만들 수 있었지만, “지난 미팅 감사”, “지난 상담 감사 후 다음 주 연락”처럼 목적만 주어진 입력은 본문을 생성하지 못했다. 반대로 `model-agent`에서 Gemma 3에게 native tool call과 자연스러운 본문 생성을 동시에 맡기면 실제 평가에서 `Message.toolCalls`가 한 건도 생성되지 않았다.

현재는 역할을 다음처럼 분리한다.

```text
ComposePromptParser
→ 직접 이메일/전화번호 또는 contact query 추출
→ 이름이면 search_contacts
→ 결과 1개면 get_contact
→ email/phone/mobile을 tool result에서만 추출
→ 명시 본문이면 원문 보존
→ 목적만 있으면 Gemma 3 MessageDraftGenerator 호출
→ SafeMessageDraftGenerator 검증 또는 grounded template fallback
→ application_orchestrator가 ModelToolCall(open_compose) 생성
→ 기존 AgentKernel / validation / policy / DefaultToolRegistry
→ OpenComposePlugin
→ Android Intent 또는 Desktop mock
```

Gemma 3는 초안 JSON 생성만 담당한다. `open_compose` tool call은 모델 native tool call처럼 가장하지 않고 애플리케이션이 생성한다. 현재 저장소에는 FunctionGemma 모델이 로드되어 있지 않으며, compose 동작은 FunctionGemma 없이 deterministic parser와 Gemma 3 draft generator만으로 완결된다.

### 공통 계약과 구현 위치

```text
tool-external-actions/src/main/kotlin/com/hjp/tool/android/ExternalActionBackends.kt
tool-external-actions/src/main/kotlin/com/hjp/tool/android/SafeMessageDraftGenerator.kt
agent-routing/src/main/kotlin/com/example/hjp/LocalToolRoutingModelGateway.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/SubprocessMessageDraftGenerator.kt
llm-litert-common/src/main/kotlin/com/hjp/agent/litert/common/LiteRtNativeAgentModelGateway.kt
llm-litert/src/main/kotlin/com/hjp/agent/litert/LiteRtAgentModelGateway.kt
```

공통 계약은 `MessageDraftGenerator.generate(MessageDraftRequest): MessageDraftGeneration`이다. Desktop과 Android는 같은 `MessageDraftPrompt`와 `SafeMessageDraftGenerator`를 사용한다. Android 실기기에서는 기존 `LiteRtAgentModelGateway` 인스턴스를 routing primary이자 draft generator로 공유하며, emulator fallback은 안전한 template generator를 사용한다.

### 수신자와 본문 정책

- 직접 이메일 또는 전화번호는 그대로 사용한다.
- 이름/회사/직책은 `search_contacts → get_contact` 결과 1건일 때만 진행한다.
- 검색 0건은 `찾지 못했습니다`로 종료한다.
- 여러 건은 후보를 보여 주고 대상 확인을 요구한다.
- 명함에 채널 주소가 없으면 이메일/전화번호가 없다고 종료한다.
- SMS는 `phone`, 없으면 `mobile` 순으로 확인한다.
- 사용자가 `내용은 ...`, 인용문, `...라고/다고 문자`처럼 본문을 직접 제공하면 모델을 호출하지 않고 그대로 쓴다.
- 목적만 주어지면 Gemma 3에 tool schema 없이 JSON 초안만 요청한다.
- 현재 요청이 `지난`, `앞서`, `그때`처럼 이전 대화를 참조할 때만 최근 3개 사용자 입력 중 관련 미팅/상담/회의 맥락 1개를 최대 500자로 제한해 전달한다.
- 사용자가 지정한 메일 제목은 모델이 생성한 제목보다 우선한다.
- 본문이 비어 있거나 JSON parsing 실패, 문장 수/길이 위반, 반복, 과도한 공백, 전송 완료 주장, 입력에 없는 구체 날짜·연락처·회의 세부사항이 있으면 grounded template으로 교체한다.
- 메일은 2~5문장, SMS는 1~3문장이다.

### 실제 필수 명령 검증

직접 이메일:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode full --debug --prompt "test@example.com한테 지난번 미팅에 대한 감사 내용을 담아서 메일 작성해줘."'
```

실제 주요 로그:

```text
[COMPOSE_INTENT] channel=email body_mode=generate
[RECIPIENT_RESOLUTION] status=resolved source=direct recipient=test@example.com
[DRAFT_MODEL] draft_source=gemma3-1b-it-int4 fallback=true
[DRAFT_VALIDATION] valid=true json_parsed=false fallback=true reason=malformed_spacing
[GENERATED_TOOL_CALL] {"tool_call_source":"application_orchestrator","name":"open_compose","arguments":{"channel":"email","to":"test@example.com","subject":"미팅 감사드립니다","body":"안녕하세요. 지난 미팅에서 귀한 시간을 내주셔서 감사합니다. 다시 한번 감사드립니다."}}
[TOOL_RESULT] ... "status":"mock_success" ...
```

모델 raw output에는 사용자가 말하지 않은 팀 협업 내용과 비정상 공백이 포함됐고, validator가 최종 tool call 전에 차단했다.

이름 기반 SMS:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode full --debug --prompt "김지원에게 지난 상담에 감사하고 다음 주에 다시 연락드리겠다는 문자를 작성해줘."'
```

실제 주요 로그:

```text
[COMPOSE_INTENT] channel=sms body_mode=generate
[RECIPIENT_RESOLUTION] status=lookup_required query=김지원
[PARSED_TOOL_CALL] ... "name":"search_contacts" ...
[PARSED_TOOL_CALL] ... "name":"get_contact","arguments":{"card_id":"C001","purpose":"sms"} ...
[RECIPIENT_RESOLUTION] status=resolved source=contact_tool recipient=010-0000-0001 name=김지원
[DRAFT_MODEL] draft_source=gemma3-1b-it-int4 fallback=false
[DRAFT_RAW_OUTPUT] {"body":"김지원님께 지난 상담에 진심으로 감사드립니다. 다음 주에 다시 연락드리겠습니다. 감사합니다."}
[DRAFT_VALIDATION] valid=true json_parsed=true fallback=false reason=valid
[GENERATED_TOOL_CALL] ... "channel":"sms","to":"010-0000-0001" ...
[TOOL_RESULT] ... "status":"mock_success" ...
```

Desktop은 작성 내용을 기록했을 뿐 Activity를 열거나 실제 전송하지 않았다.

### 자동 테스트와 실제 모델 평가

```bash
./gradlew :agent-routing:test :tool-external-actions:test :desktop-agent-runner:test
./gradlew :desktop-agent-runner:modelAgentEval
```

검증 범위는 두 필수 prompt, 직접/이름 수신자, 명시 본문 무변경, 제목 우선, 검색 실패/동명이인/주소 누락, JSON parsing 실패, 빈 본문, 반복 출력, 입력에 없는 세부사항, 전송 완료 주장, `비전글로벌` 내부의 `로` parsing 회귀, Desktop 무전송을 포함한다.

실제 모델 dataset은 53건이며 email/SMS 11건은 `full` hybrid compose 경로, 나머지는 `model-agent` native tool-calling 경로로 분리 집계한다.

2026-07-24 CPU 최종 평가:

```text
tool_call_parse_rate=0.0
tool_selection_accuracy=0.09523809523809523
argument_accuracy=0.0
tool_sequence_accuracy=0.09523809523809523
tool_execution_success_rate=0.0
no_tool_false_positive_rate=0.0
grounded_answer_rate=1.0
hallucinated_contact_count=0.0
compose_intent_accuracy=1.0
recipient_resolution_accuracy=1.0
draft_generation_success_rate=1.0
draft_json_parse_rate=0.25
open_compose_execution_rate=1.0
recipient_hallucination_count=0.0
```

`draft_json_parse_rate=0.25`는 Gemma 3의 JSON 형식 준수율이 낮다는 뜻이다. validation과 fallback을 포함한 draft/open-compose 성공률은 1.0이고 수신자 환각은 0건이다. native tool-calling 0%라는 기존 모델 한계는 그대로다.

보고서:

```text
desktop-agent-runner/build/reports/model-eval/result.json
desktop-agent-runner/build/reports/model-eval/result.csv
```

### Android 및 패키징 검증

```bash
./gradlew test :app:assembleDebug :app:mergeStandaloneAssets
```

2026-07-24 결과는 `BUILD SUCCESSFUL`이다. Android `OpenComposePlugin`과 실제 Intent backend는 교체하지 않았고 Desktop만 mock backend를 사용한다.

standalone merged asset:

```text
gemma3-1b-it-int4.litertlm
cards/business_cards.json
```

`*.xnnpack_cache_*`는 포함되지 않았다.

## 0-A. 실제 모델 tool-calling 검증 환경 최종 상태

### 결론

검증 환경 자체는 완성됐다.

- `LiteRtLmProcessRunner`는 child stdin을 즉시 닫고 stdout/stderr/timeout 자원을 정리한다.
- `--mode model-agent`가 추가됐다.
- model-agent는 deterministic router와 `RoutingFirstAgentModelGateway`를 완전히 우회한다.
- Desktop은 공식 `litertlm-jvm:0.14.0` API를 직접 사용한다.
- Android와 Desktop의 OpenAPI tool 등록, `Message.toolCalls` 변환, `Content.ToolResponse` 연속 대화 코드는 `llm-litert-common`에 공통화됐다.
- 모델-originated `ModelToolCall`은 기존 `AgentKernel`, validation/policy, `DefaultToolRegistry`, 실제 contact plugin 또는 Desktop mock을 통과한다.
- 연락처 최종 응답에는 grounded formatter와 안전 gate가 적용된다.
- 51개 실제 모델 평가와 JSON/CSV reporter가 추가됐다.
- Desktop XNNPACK cache는 module build 디렉터리로 분리됐다.
- standalone merged asset은 모델과 명함 JSON 두 파일만 허용한다.
- Android debug APK 빌드가 통과했다.

다만 실제 `gemma3-1b-it-int4.litertlm` 모델 자체는 등록된 tool을 호출하지 못했다. 2026-07-23 CPU 실제 평가에서 47개의 tool-required case 모두 `Message.toolCalls`가 비어 있었으며 일반 텍스트만 생성했다. no-tool 4건에서는 tool을 호출하지 않았다. 따라서 “검증 장치”는 정상 동작하지만 “현재 모델의 tool-calling 성능”은 실패로 판정한다.

### stdin blocker 수정

파일:

```text
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/LiteRtLmProcessRunner.kt
```

`ProcessBuilder(command).start()` 직후 `process.outputStream.close()`를 호출한다. Python CLI가 비-TTY에서 수행하는 `sys.stdin.read()`에 EOF가 전달되므로 대기하지 않는다.

동시에 다음을 구현했다.

- `ProcessBuilder(List<String>)` 유지
- stdout/stderr 전용 daemon reader executor
- 정상 종료 후 reader join
- timeout 시 `destroy` 후 필요하면 `destroyForcibly`
- input/error stream close
- reader future cancel
- executor `shutdownNow`와 termination 대기
- subprocess cache를 `disk`에서 `memory`로 변경

회귀 테스트는 stdin EOF 전까지 기다리는 executable, stdout/stderr 동시 출력, timeout 후 reader thread 잔존 여부, 한글·공백 경로를 검증한다.

### model-agent 구조

```text
DesktopAgentRunner(MODEL_AGENT)
→ LiteRtNativeAgentModelGateway
→ Engine / ConversationConfig
→ OpenApiTool(ToolContract)
→ automaticToolCalling=false
→ Conversation.sendMessage(user)
→ Message.toolCalls
→ ModelToolCall
→ AgentKernel
→ DefaultToolRegistry
→ DefaultToolExecutor
→ 실제 Search/Get/Update plugin 또는 Desktop mock
→ ModelToolResponse
→ Content.ToolResponse
→ 동일 conversation
→ 후속 tool call 또는 final
→ grounding validation
```

이 경로에는 `LocalToolRoutingModelGateway`와 `RoutingFirstAgentModelGateway`가 없다. debug trace와 fixture 통합 테스트가 `deterministic_router_bypassed=true`를 검증한다.

### 공식 Kotlin/JVM API 사용 근거

Google Maven에서 확인한 `com.google.ai.edge.litertlm:litertlm-jvm:0.14.0` jar는 macOS arm64 네이티브 라이브러리와 다음 API를 포함한다.

```text
Engine
EngineConfig
ConversationConfig
Conversation
Message.toolCalls
Content.ToolResponse
OpenApiTool
```

따라서 model-agent에는 subprocess JSON parsing을 사용하지 않는다. Android도 0.14.0으로 맞추고 공통 adapter를 경유한다. CLI subprocess는 기존 `full`의 primary fallback과 `model-only` 호환을 위해서만 남겼다.

### tool definition 전달

`ToolCatalogSnapshot.contractsByModelName`에서 전체 contract를 읽고 다음을 공식 `OpenApiTool` JSON으로 등록한다.

- `name`
- `description`
- 전체 `parameters` JSON schema
- schema의 `required`
- `additionalProperties`
- type/enum/min/max/default

실제 등록 tool:

```text
create_calendar_event
get_contact
get_current_datetime
open_compose
search_contacts
update_business_card
```

`automaticToolCalling=false`이므로 LiteRT-LM 내부가 tool 구현을 직접 실행하지 않는다. `OpenApiTool.execute`는 `manual_tool_execution_required`를 반환하고, 실제 실행 소유권은 계속 `AgentKernel`에 있다.

### grounding

연락처 tool result는 먼저 같은 모델 conversation에 전송한다. 모델이 후속 tool call을 반환하면 계속 AgentKernel이 실행한다. 최종 연락처 문장은 모델 raw text를 그대로 쓰지 않고 `GroundedToolResultFormatter`가 tool payload만으로 생성한다.

모델이 contact/calendar/email/SMS/datetime/update처럼 tool evidence가 필요한 요청에 tool을 전혀 호출하지 않으면 output safety gate가 작업 미실행 메시지로 대체한다. 이 gate는 tool을 선택하거나 생성하는 router가 아니라, 확인되지 않은 모델 문장을 사용자에게 내보내지 않는 최종 검증기다.

### 실제 단일 prompt 결과

명령:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode model-agent --debug --prompt "김지원 명함을 찾아줘."'
```

핵심 실제 로그:

```text
[ROUTING_POLICY] deterministic_router_bypassed=true
[MODEL_REQUEST] 김지원 명함을 찾아줘.
[MODEL_RAW_OUTPUT] 김지원 명함을 찾을 수 없습니다.
[MODEL_DECISION_SOURCE] decision_source=gemma3-1b-it-int4
[PARSED_TOOL_CALL] none
[TOOL_EXECUTION] not_executed
[TOOL_RESULT] not_executed
[TOOL_RESPONSE_SENT_TO_MODEL] not_sent
[GROUNDING_VALIDATION] valid=false reason=contact_request_without_contact_tool safe_response=true
[FINAL_RESPONSE] 연락처 도구가 호출되지 않아 확인된 명함 정보를 제공할 수 없습니다.
```

즉 이 실행에서는 모델 tool call과 tool result가 없다. 이를 router call로 대체하거나 성공으로 가장하지 않고 평가 실패로 기록한다.

### 평가 dataset과 지표

dataset:

```text
desktop-agent-runner/src/modelEval/resources/gemma3_tool_eval.jsonl
```

총 51건이며 `contact_search`, `contact_lookup`, `contact_update`, `calendar`, `email`, `sms`, `current_datetime`, `no_tool`, `ambiguous`, `nonexistent_contact`, `multi_step`를 모두 포함한다.

task와 산출물:

```bash
./gradlew :desktop-agent-runner:modelAgentEval
```

```text
desktop-agent-runner/build/reports/model-eval/result.json
desktop-agent-runner/build/reports/model-eval/result.csv
```

최종 실제 CPU 지표:

| 지표 | 결과 |
|---|---:|
| `tool_call_parse_rate` | `0.0` |
| `tool_selection_accuracy` | `0.0784313725490196` (`4/51`, no-tool case만 일치) |
| `argument_accuracy` | `0.0` |
| `tool_sequence_accuracy` | `0.0784313725490196` |
| `tool_execution_success_rate` | `0.0` |
| `no_tool_false_positive_rate` | `0.0` |
| `grounded_answer_rate` | `1.0` (tool 미호출 요청은 안전 차단 포함) |
| `hallucinated_contact_count` | `0` (최종 사용자 응답 기준) |

대표 실패:

```text
prompt: 김지원 명함을 찾아줘.
expected tools: [search_contacts]
actual tools: []
actual arguments: []
raw model output: 김지원 명함을 찾을 수 없습니다.
tool result: 없음
final answer: 연락처 도구가 호출되지 않아 확인된 명함 정보를 제공할 수 없습니다.
failure reason: tool_sequence_mismatch; argument_mismatch; tool_execution_failed
```

모델 raw output 중에는 존재하지 않는 연락처를 생성한 사례도 있었지만 최종 output safety gate가 모두 차단했다. raw output은 성능 분석을 위해 실패 보고서에만 보존된다.

### cache와 standalone asset

Desktop 공식 Engine cache:

```text
desktop-agent-runner/build/cache/litertlm
```

기존 모델 옆 485MB cache는 현재 작업에서 삭제하지 않았다. 사용자 파일일 수 있기 때문이다. 대신 `.gitignore`와 asset staging이 이를 무시하며 새 Desktop 실행은 build cache 경로를 사용한다.

`mergeStandaloneAssets` 실제 최종 목록:

```text
cards/business_cards.json
gemma3-1b-it-int4.litertlm
```

`verifyStandaloneMergedAssets`가 exact set과 `*.xnnpack_cache_*` 부재를 자동 검증한다.

### 검증 결과

```text
./gradlew :desktop-agent-runner:test :desktop-agent-runner:modelEvalClasses
→ BUILD SUCCESSFUL

./gradlew :app:mergeStandaloneAssets :app:assembleDebug
→ BUILD SUCCESSFUL

./gradlew :desktop-agent-runner:run --args='--mode model-agent --debug --prompt "김지원 명함을 찾아줘."'
→ BUILD SUCCESSFUL, 실제 모델 실행, router 우회, native tool call 없음 확인

./gradlew :desktop-agent-runner:modelAgentEval
→ BUILD SUCCESSFUL, 51 cases, result.json/result.csv 생성
```

실제 model smoke는 일반 test에서 분리돼 있다.

```bash
RUN_LITERT_LM_SMOKE_TEST=1 ./gradlew :desktop-agent-runner:modelSmokeTest
```

### 추가·수정된 핵심 파일

```text
llm-litert-common/build.gradle.kts
llm-litert-common/src/main/kotlin/com/hjp/agent/litert/common/LiteRtNativeAgentModelGateway.kt
llm-litert/src/main/kotlin/com/hjp/agent/litert/LiteRtAgentModelGateway.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopAgentRunner.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopConfig.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopTracing.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/LiteRtLmProcessRunner.kt
desktop-agent-runner/src/modelEval/kotlin/com/hjp/desktop/ModelAgentEvalMain.kt
desktop-agent-runner/src/modelEval/resources/gemma3_tool_eval.jsonl
agent-routing/src/main/kotlin/com/hjp/agent/routing/ContactGroundingGuard.kt
app/build.gradle.kts
gradle/libs.versions.toml
settings.gradle.kts
.gitignore
DESKTOP_AGENT_TEST.md
DESKTOP_AGENT_CURRENT_STATUS.md
```

### 남은 모델 자체 한계

현재 모델은 `gemma3-1b-it-int4` 일반 instruction-tuned 모델이다. 공식 tool API가 tool schema를 정상 전달해도 이 모델이 native tool-call token/structure를 생성한다는 보장은 없다. 실제 51건 평가에서는 한 번도 생성하지 않았다.

검증 환경을 성공시키기 위해 deterministic router 결과를 model-originated call로 위장하지 않았다. 실제 tool-calling 성능을 얻으려면 FunctionGemma 등 tool use에 맞게 학습·패키징된 LiteRT-LM 모델을 같은 `model-agent` 평가에 넣어 비교해야 한다.

## 0. 문서 정보

- 점검일: 2026-07-23
- 점검 환경: Apple Silicon Mac, macOS, `zsh`, Asia/Seoul
- 점검 브랜치: `android-app`
- 점검 기준 HEAD: `993a5f5` (`Improve tool tester button layout`)
- 저장소 루트:

  ```text
  /absolute/path/to/project
  ```

- 실제 LiteRT-LM 실행 파일:

  ```text
  $LITERT_LM_BIN
  ```

- 실제 모델 파일:

  ```text
  /absolute/path/to/gemma3-1b-it-int4.litertlm
  ```

- 모델 파일 확인 크기: `584,417,280` bytes
- LiteRT-LM CLI 확인 버전: `0.14.0`

이 문서는 현재 저장소의 실제 소스, Git 상태, Gradle 설정, 테스트 결과, LiteRT-LM CLI 도움말 및 실제 프로세스 동작을 직접 확인한 결과를 기록한다. 파일명이나 클래스명을 추측해서 작성하지 않았다.

기능 상태를 점검하는 동안에는 기존 소스 코드를 수정하지 않았다. 점검 결과를 보존하기 위해 이 보고서 `DESKTOP_AGENT_CURRENT_STATUS.md`만 새로 추가했다. 테스트와 Gradle 검증을 실행했기 때문에 각 모듈의 `build/` 산출물과 테스트 결과 파일은 갱신되었다.

---

## 1. 최종 결론

현재 Desktop Agent Runner 작업은 핵심 구조와 대부분의 기능이 구현되어 있으나 **완료 상태가 아니다**.

구현되어 있고 검증된 부분은 다음과 같다.

- Android와 Desktop이 공유하는 `AgentKernel`
- 공통 `AgentModelGateway`, `ModelToolCall`, `ModelToolResponse`
- Android/Desktop 공용 deterministic router
- 공통 `ToolRegistry`와 tool executor
- 검색, 조회, 수정 tool plugin
- Android 앱이 사용하는 실제 `business_cards.json` 연결
- 캘린더 및 메일/SMS의 Desktop-safe mock
- 현재 날짜·시각 tool
- `full`, `router-only`, `model-only` CLI 모드의 코드 구조
- 단일 prompt 및 대화형 CLI 코드
- 상세 debug tracing
- Desktop/JVM 단위·통합 테스트
- Android debug 앱 빌드 보존
- Desktop 실행 문서

그러나 다음 문제 때문에 완료 조건을 충족하지 못한다.

1. **실제 LiteRT-LM subprocess가 child stdin EOF를 받지 못해 대기 상태에 빠진다.**
   - 현재 `full` 모드에서 primary 모델이 필요한 요청과 `model-only` 모드는 정상 완료되지 않는다.
   - 기본 timeout인 900초까지 멈춰 있을 수 있다.

2. **standalone APK asset에 485MB XNNPACK cache가 추가로 포함된다.**
   - `models/` 디렉터리 전체가 standalone asset source set으로 연결돼 있다.
   - Desktop CLI의 `--cache disk`가 모델 옆에 만든 cache 파일도 asset으로 병합된다.

3. **작업 전체가 아직 Git에 커밋되지 않았다.**
   - 신규 공통 모듈과 Desktop 모듈이 모두 미추적 상태다.
   - 기존 Android app 내부 tool 파일을 삭제한 변경도 커밋되지 않았다.

4. **`.gitignore`가 하위 모듈의 `build/`를 제외하지 않는다.**
   - 점검 시점에 약 2,019개의 모듈 build 산출물이 미추적 파일로 노출됐다.
   - 현재 상태에서 `git add -A`를 실행하면 안 된다.

5. **실제 model-originated tool call 전체 통합 테스트가 없다.**
   - parser 단위 테스트는 존재한다.
   - 실제 모델 smoke는 subprocess stdin 문제 때문에 통과하지 못했다.

6. **Desktop 명함 수정은 프로세스 메모리 안에서만 유지된다.**
   - 공통 `UpdateBusinessCardPlugin`은 실제로 실행한다.
   - Android asset JSON은 덮어쓰지 않으며 Desktop 프로세스를 종료하면 수정 내용이 사라진다.

따라서 현재 상태는 다음과 같이 요약할 수 있다.

```text
router-only 전체 파이프라인: 동작 및 검증 완료
Android debug 앱: 빌드 및 단위 테스트 통과
실제 LiteRT-LM 모델 자체: 정상 동작 확인
Desktop full/model-only의 subprocess 연결: blocker 존재
standalone 모델 패키징: cache 혼입 문제 존재
Git 커밋 가능 상태: 아님
```

---

## 2. 현재 Gradle 모듈 구조

현재 `settings.gradle.kts`에서 확인된 모듈은 다음과 같다.

```text
app
agent-contract
agent-core
agent-routing
search-core
tool-contract
tool-contact
tool-datetime
tool-external-actions
tool-android-intents
llm-litert
desktop-agent-runner
```

역할은 다음과 같다.

| 모듈 | 역할 | Android SDK 의존 |
|---|---|---:|
| `agent-contract` | 모델 gateway, model decision, model tool call, agent event 계약 | 없음 |
| `tool-contract` | tool contract, plugin API, registry snapshot, 실행 결과 계약 | 없음 |
| `agent-core` | AgentKernel, Registry, Policy, Executor, session, observation | 없음 |
| `agent-routing` | deterministic router, routing-first 정책, model output parser, grounded formatter | 없음 |
| `search-core` | Java 기반 로컬 검색 및 hash/concept embedding | 없음 |
| `tool-contact` | 명함 repository port와 검색/조회/수정 plugin | 없음 |
| `tool-datetime` | 현재 날짜·시각 plugin | 없음 |
| `tool-external-actions` | 캘린더/메시지 공통 contract 및 backend port | 없음 |
| `desktop-agent-runner` | Mac JVM CLI, file data source, mock backend, subprocess gateway | 없음 |
| `tool-android-intents` | 실제 Android Calendar/Email/SMS Intent backend | 있음 |
| `llm-litert` | Android LiteRT-LM native gateway | 있음 |
| `app` | Compose UI, ViewModel, Room, Android composition root | 있음 |

`settings.gradle.kts`에는 JVM 전용 task만 요청했을 때 Android 모듈을 include하지 않는 조건부 구성이 들어 있다.

JVM 전용 프로젝트 집합:

```text
agent-contract
tool-contract
agent-core
agent-routing
search-core
tool-contact
tool-external-actions
tool-datetime
desktop-agent-runner
```

다음처럼 프로젝트 경로가 명시된 JVM task만 요청하면 `app`, `tool-android-intents`, `llm-litert`가 제외된다.

```bash
./gradlew :desktop-agent-runner:test
./gradlew :desktop-agent-runner:run
./gradlew :agent-routing:test :agent-core:test
```

반면 다음처럼 root task를 요청하거나 Android task를 요청하면 Android 모듈이 포함된다.

```bash
./gradlew test
./gradlew :app:assembleDebug
```

따라서 Android SDK 없이 실행하려는 경우 문서에 적힌 JVM 모듈 경로를 명시해야 한다.

---

## 3. Android 사용자 입력부터 최종 응답까지의 실제 흐름

### 3.1 UI 입력

파일:

```text
app/src/main/java/com/example/hjp/MainActivity.kt
```

`MainActivity`는 `AgentScreen`에 다음 callback을 전달한다.

```kotlin
onSend = agentViewModel::send
```

사용자가 Compose `OutlinedTextField`에 입력한 뒤 `보내기` 버튼을 누르면 다음 코드가 호출된다.

```kotlin
onClick = { onSend(input); input = "" }
```

### 3.2 ViewModel

파일:

```text
app/src/main/java/com/example/hjp/AgentViewModel.kt
```

`AgentViewModel.send(rawText)`는 입력을 trim하고, 모델 준비 여부와 busy 상태를 확인한 뒤 다음을 호출한다.

```kotlin
container.kernel.runTurn(text)
```

`AgentKernel`에서 발생하는 `AgentEvent`는 UI 상태로 변환된다.

- `TurnStarted`
- `ToolStarted`
- `ToolFinished`
- `ConfirmationRequested`
- `PermissionRequested`
- `Token`
- `FinalMessage`
- `UserError`

### 3.3 AppContainer

파일:

```text
app/src/main/java/com/example/hjp/AppContainer.kt
```

`AppContainer`는 Android production composition root다.

조립되는 주요 구성은 다음과 같다.

```text
HjpDatabase
→ RoomBusinessCardRepository
→ RyeongContactSearchBackend
→ Search/Get/Update plugin
→ Android Calendar/Message backend plugin
→ CurrentDateTime plugin
→ DefaultToolRegistry
→ AgentSessionManager
→ AgentKernel
```

등록되는 plugin은 실제 소스 기준 다음 6개다.

| 모델 tool 이름 | plugin |
|---|---|
| `search_contacts` | `SearchContactsPlugin` |
| `get_contact` | `GetContactPlugin` |
| `update_business_card` | `UpdateBusinessCardPlugin` |
| `create_calendar_event` | `CreateCalendarEventPlugin` |
| `open_compose` | `OpenComposePlugin` |
| `get_current_datetime` | `GetCurrentDateTimePlugin` |

### 3.4 Android 모델 gateway 선택

Android emulator로 판단되면:

```text
LocalToolRoutingModelGateway
```

실기기로 판단되면:

```text
Gemma3AgentModelGateway
  ├─ primary: LiteRtAgentModelGateway
  └─ toolRouter: LocalToolRoutingModelGateway
```

실기기의 Android LiteRT-LM backend 설정은 현재 `CPU_ONLY`다.

### 3.5 Gemma3AgentModelGateway

파일:

```text
app/src/main/java/com/example/hjp/Gemma3AgentModelGateway.kt
```

이 클래스는 Android 이름을 유지하지만 실제 routing-first 정책은 공통 모듈의 다음 클래스에 위임한다.

```text
agent-routing/src/main/kotlin/com/hjp/agent/routing/RoutingFirstAgentModelGateway.kt
```

정책은 다음과 같다.

```text
LocalToolRoutingModelGateway.canRoute(userText) == true
→ deterministic router 사용

그 외
→ primary model 사용

primary open/decision 실패
→ deterministic router fallback
```

### 3.6 AgentKernel

파일:

```text
agent-core/src/main/kotlin/com/hjp/agent/core/AgentKernel.kt
```

`AgentKernel`은 Android와 Desktop에서 동일한 구현을 사용한다.

실행 순서:

```text
사용자 입력 trim/검증
→ 세션 생성 또는 재사용
→ 현재 registry snapshot 생성
→ model session 준비
→ model.decide()
→ ModelDecision 처리
→ ModelToolCall contract 조회
→ 반복 호출/최대 호출 수 검사
→ tool policy 평가
→ tool argument decode/검증
→ tool 실행
→ ToolExecutionResult
→ ModelToolResponse 변환
→ model.continueWithToolResult()
→ 최종 응답 stream
```

현재 turn 정책:

- 한 decision에서 tool call 1개만 허용
- 한 turn 최대 tool call 5개
- 동일 tool과 동일 canonical arguments 반복 차단
- tool별 timeout 적용
- local mutation은 confirmation 필요

---

## 4. deterministic router의 실제 지원 범위

파일:

```text
agent-routing/src/main/kotlin/com/example/hjp/LocalToolRoutingModelGateway.kt
```

`LocalToolRoutingModelGateway.canRoute(text)`는 내부 `LocalPromptRouter.parse(text)` 결과로 판단한다.

실제 `PendingAction` 유형은 다음과 같다.

```text
ContactSearch
ContactLookup
CurrentDateTime
Calendar
Compose
ContactUpdate
```

### 4.1 명함 검색

예:

```text
김민수 명함 찾아줘.
```

생성되는 call:

```text
search_contacts(query="김민수", limit=5)
```

검색 결과가 없으면:

```text
‘김민수’에 해당하는 명함을 찾지 못했습니다.
```

### 4.2 명함 상세 조회

예:

```text
김지원 연락처 보여줘.
```

흐름:

```text
search_contacts
→ 결과 0개: 찾지 못했다는 응답
→ 결과 2개 이상: 대상을 하나로 특정해 달라는 응답
→ 결과 1개: get_contact
→ tool result에 있는 필드만 최종 출력
```

### 4.3 명함 수정

예:

```text
김지원 명함 메모를 VIP로 수정해줘.
```

흐름:

```text
search_contacts
→ get_contact
→ update_business_card
→ confirmation policy
→ 수정
```

Desktop runtime은 confirmation에 자동으로 `true`를 반환한다. Android UI는 사용자에게 확인 UI를 표시한다.

### 4.4 캘린더

절대 시각 예:

```text
2026년 7월 10일 오후 2시 회의 일정 만들어줘.
```

생성되는 call:

```text
create_calendar_event(
  title="회의",
  start_time="2026-07-10T14:00"
)
```

상대 시각 예:

```text
내일 오후 2시 회의 일정 만들어줘.
```

흐름:

```text
get_current_datetime
→ 현재 날짜 기준으로 내일 계산
→ create_calendar_event
```

### 4.5 이메일 및 SMS

직접 주소/번호가 있으면 바로 `open_compose`를 생성한다.

예:

```text
jiwon@example.com에게 제목은 회의 요청, 내용은 내일 가능하신가요 라고 메일 작성해줘.
010-0000-0001로 회의에 늦는다고 문자 보내줘.
```

명함 이름으로 요청하면:

```text
search_contacts
→ get_contact
→ email 또는 phone 확인
→ open_compose
```

### 4.6 현재 날짜 및 시각

예:

```text
현재 시간 알려줘.
지금 몇 시야?
```

생성되는 call:

```text
get_current_datetime
```

Mac Desktop에서는 JVM 실제 clock과 Mac timezone을 사용한다.

---

## 5. ToolCall parser와 최종 응답 grounding

### 5.1 Desktop model output parser

파일:

```text
agent-routing/src/main/kotlin/com/hjp/agent/routing/ModelToolCallParser.kt
```

지원 형식:

- 직접 JSON object
- fenced JSON
- `type=tool_call`
- `name`, `tool`, `model_tool_name`
- `arguments` 또는 `args`
- OpenAI 스타일 `tool_calls`
- 문자열로 들어온 OpenAI `arguments`
- `final`, `final_answer`
- plain text final

잘못된 tool call 형식은 `ModelDecision.Invalid`로 변환된다.

### 5.2 Android native tool call

Android `llm-litert` 모듈은 LiteRT-LM native `Message.toolCalls`를 읽어 동일한 공통 `ModelToolCall`로 변환한다.

따라서 Android는 subprocess stdout parser를 사용하지 않지만 동일 역할의 native 변환 코드를 사용한다.

### 5.3 GroundedToolResultFormatter

파일:

```text
agent-routing/src/main/kotlin/com/hjp/agent/routing/GroundedToolResultFormatter.kt
```

Desktop primary 모델이 tool call을 만든 뒤에는 두 번째 자유 생성으로 연락처 정보를 합성하지 않는다.

```text
tool result
→ GroundedToolResultFormatter
→ tool result에 들어 있는 필드만 최종 응답
```

지원 tool:

- `search_contacts`
- `get_contact`
- `get_current_datetime`
- `create_calendar_event`
- `open_compose`
- `update_business_card`

이 경로는 tool 결과에 없는 이름, 회사, 직책, 전화번호, 이메일을 새로 생성하지 않는다.

단, primary 모델이 tool call 없이 plain text final을 반환한 경우에는 그 텍스트 자체를 출력할 수 있다. 따라서 모든 가능한 자연어 표현에 대해 연락처 환각을 형식적으로 완전 차단한다고 단정할 수는 없다. 알려진 명함 요청은 deterministic router로 우선 처리되고, 모델 system instruction에도 데이터에 없는 연락처를 만들지 말라는 지시가 들어 있다.

---

## 6. ToolRegistry와 실제 tool 구현

### 6.1 ToolRegistry

파일:

```text
agent-core/src/main/kotlin/com/hjp/agent/core/DefaultToolRegistry.kt
```

`DefaultToolRegistry`는 `ToolImplementationCandidate` 목록을 받아 다음 기준으로 현재 catalog를 만든다.

- candidate enable 여부
- device capability
- tool availability
- capability별 priority
- 동일 priority 충돌 검사
- model tool name 중복 검사

snapshot에는 다음 정보가 포함된다.

```text
revision
bindingRevision
bindings
contractsByModelName
```

실행 시 model tool name으로 binding을 찾고 implementation ID로 실제 plugin을 resolve한다.

### 6.2 명함 tool

파일:

```text
tool-contact/src/main/kotlin/com/hjp/tool/contact/ContactPlugins.kt
tool-contact/src/main/kotlin/com/hjp/tool/contact/ContactToolContracts.kt
tool-contact/src/main/kotlin/com/hjp/tool/contact/RyeongContactSearchBackend.kt
```

실제 plugin:

- `SearchContactsPlugin`
- `GetContactPlugin`
- `UpdateBusinessCardPlugin`

입력 codec은 누락, 빈 문자열, 잘못된 타입, 잘못된 enum, 범위를 검증한다.

검색 응답에는 다음만 들어간다.

```text
card_id
name
company
title
location
score
```

전화번호와 이메일은 `get_contact` 이후에만 반환된다.

### 6.3 Android 실제 외부 동작

파일:

```text
tool-android-intents/src/main/kotlin/com/hjp/tool/android/AndroidIntentBackends.kt
```

Android 구현은 다음 API를 직접 사용한다.

- `Context`
- `Intent`
- `Uri`
- `CalendarContract`
- `ActivityNotFoundException`

캘린더:

```text
Intent.ACTION_INSERT
CalendarContract.Events.CONTENT_URI
```

메일:

```text
Intent.ACTION_SENDTO
mailto:
```

SMS:

```text
Intent.ACTION_SENDTO
smsto:
```

### 6.4 Desktop mock

파일:

```text
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopMockBackends.kt
```

Desktop에서는 Android Activity나 Intent를 실행하지 않는다.

캘린더 mock 구조:

```json
{
  "status": "mock_success",
  "tool": "create_calendar_event",
  "title": "...",
  "start_time": "...",
  "end_time": "...",
  "message": "Desktop 테스트에서는 Android 캘린더 Intent를 실행하지 않았습니다."
}
```

메시지 mock 구조:

```json
{
  "status": "mock_success",
  "tool": "open_compose",
  "channel": "email 또는 sms",
  "to": "...",
  "subject": "...",
  "body": "...",
  "message": "Desktop 테스트에서는 Android 작성 Activity를 열거나 메시지를 전송하지 않았습니다."
}
```

실제 저장 또는 전송은 하지 않는다.

---

## 7. 명함 데이터와 검색 방식

### 7.1 실제로 발견된 asset

파일:

```text
app/src/main/assets/cards/business_cards.json
```

확인 크기:

```text
911 bytes
```

현재 포함 레코드:

```text
C001 / 김지원 / 비전글로벌 / 대표이사
C002 / 오성령 / 코어AI / AI 엔지니어
```

저장소 전체를 검색한 결과 다음 파일은 존재하지 않는다.

```text
card_vectors_f32.bin
vector ID 파일
vector metadata 파일
별도 검색 index 파일
```

### 7.2 Android 로딩

관련 파일:

```text
app/src/main/java/com/example/hjp/data/AssetBusinessCardRepository.kt
app/src/main/java/com/example/hjp/data/RoomBusinessCardRepository.kt
app/src/main/java/com/example/hjp/data/HjpDatabase.kt
app/src/main/java/com/example/hjp/data/BusinessCardDao.kt
app/src/main/java/com/example/hjp/data/BusinessCardEntity.kt
```

Android는 다음 순서로 동작한다.

```text
business_cards.json
→ AssetBusinessCardRepository
→ Room이 비어 있을 때 seed
→ RoomBusinessCardRepository
→ search/get/update plugin
```

Android의 명함 수정은 Room에 저장된다.

### 7.3 Desktop 로딩

파일:

```text
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopFileDataSource.kt
```

Desktop은 같은 JSON 파일을 직접 읽는다.

기본 경로:

```text
<repository>/app/src/main/assets/cards/business_cards.json
```

경로 override:

```text
HJP_CARD_DATA
--cards
```

JSON decode는 Android와 Desktop이 공통 `BusinessCardJsonCodec`를 사용한다.

검증 항목:

- root가 JSON array인지
- 각 card가 object인지
- `id`가 존재하고 비어 있지 않은지
- `name`이 존재하고 비어 있지 않은지
- ID가 중복되지 않는지

### 7.4 검색 엔진

관련 파일:

```text
search-core/src/main/java/com/hjp/searchlookup/SearchLookupService.java
search-core/src/main/java/com/hjp/searchlookup/LocalEmbeddingEngine.java
```

현재 검색은 파일 기반 precomputed vector가 아니다.

`LocalEmbeddingEngine`은 다음을 메모리에서 생성한다.

- 192차원 hash vector
- word feature
- 2~3글자 n-gram
- concept feature

검색 score는 이름, 회사, 직책, 업종, 전체 검색 텍스트, synonym, cosine similarity를 조합한다.

알 수 없는 짧은 한국어 이름이 hash embedding 우연 유사도로 실제 인물처럼 반환되지 않도록 이름 lexical match 제한이 들어 있다.

### 7.5 Desktop 수정의 제약

Desktop `update()`는 실제 `UpdateBusinessCardPlugin`과 공통 validation을 통과한다.

그러나 data source는 copy-on-write 메모리 구현이다.

```text
원본 business_cards.json: 변경하지 않음
현재 Desktop 프로세스 메모리: 변경됨
프로세스 종료 후: 변경 내용 사라짐
```

원본 Android asset을 테스트 과정에서 손상시키지 않는다는 장점은 있으나, Desktop에서 수정의 영속성까지 검증하지는 못한다.

---

## 8. Desktop CLI 구현

### 8.1 파일

```text
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/Main.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopConfig.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopAgentRunner.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopTracing.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/LiteRtLmProcessRunner.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/SubprocessLiteRtAgentModelGateway.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopFileDataSource.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopMockBackends.kt
```

### 8.2 실행 모드

#### `full`

구성:

```text
RoutingFirstAgentModelGateway
  ├─ primary: SubprocessLiteRtAgentModelGateway
  └─ router: LocalToolRoutingModelGateway
→ AgentKernel
→ ToolRegistry
→ 실제 명함 tool 또는 Desktop mock
```

known deterministic request는 모델을 실행하지 않는다.

primary 모델의 open/decision이 예외를 던지면 router fallback을 사용한다.

현재 문제:

- primary 모델을 실제 호출하면 stdin EOF 문제로 멈춘다.

#### `router-only`

구성:

```text
LocalToolRoutingModelGateway
→ AgentKernel
→ ToolRegistry
→ 실제 명함 tool 또는 Desktop mock
```

LiteRT-LM을 실행하지 않는다.

현재 상태:

- 실제 실행 성공
- 전체 tool pipeline 확인 가능

#### `model-only`

구성:

```text
사용자 prompt
→ LiteRtLmProcessRunner
→ raw model output
```

실행 시 다음 경고를 출력한다.

```text
주의: model-only 모드는 ToolRegistry와 명함 데이터베이스를 사용하지 않습니다.
```

현재 문제:

- subprocess stdin EOF 문제로 정상 완료되지 않는다.

### 8.3 대화형 모드

`--prompt`가 없으면 반복 입력을 받는다.

종료 명령:

```text
exit
quit
```

### 8.4 단일 prompt

예:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --prompt "김지원 명함 찾아줘."'
```

### 8.5 설정 기본값

```text
LITERT_LM_BIN=$HOME/litert-lm-env/bin/litert-lm
LITERT_LM_MODEL=<repository>/models/gemma3-1b-it-int4.litertlm
LITERT_LM_BACKEND=cpu
HJP_CARD_DATA=<repository>/app/src/main/assets/cards/business_cards.json
LITERT_LM_TIMEOUT_SECONDS=900
```

지원 backend:

```text
cpu
gpu
npu
```

지원 CLI override:

```text
--mode
--prompt
--debug
--backend
--timeout-seconds
--litert-bin
--model
--cards
```

### 8.6 subprocess command

현재 `LiteRtLmProcessRunner`가 만드는 인자 배열:

```text
<litert-lm executable>
run
<absolute model path>
--prompt
<prompt>
--backend
<cpu|gpu|npu>
--cache
disk
--temperature
0.1
--top-k
20
--max-num-tokens
4096
```

shell command 문자열을 조합하지 않고 `ProcessBuilder(List<String>)`를 사용한다.

따라서 한글과 공백이 포함된 모델 경로는 하나의 인자로 보존된다.

stdout과 stderr는 별도 비동기 reader로 읽는다.

처리되는 실패:

- 실행 파일 없음
- 모델 파일 없음
- timeout
- 종료 코드가 0이 아님
- stderr 또는 stdout 상세 메시지

명시적 오류:

```text
LiteRT-LM 실행 파일을 찾을 수 없습니다: ...
모델 파일을 찾을 수 없습니다: ...
```

---

## 9. 확정된 LiteRT-LM subprocess blocker

### 9.1 현상

실제 opt-in smoke test를 실행했다.

```bash
RUN_LITERT_LM_SMOKE_TEST=1 \
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew :desktop-agent-runner:modelSmokeTest --rerun-tasks
```

관찰 결과:

- LiteRT-LM Python process가 계속 살아 있음
- CPU 사용률: `0.0%`
- 누적 CPU time: 약 `0.18초`
- 프로세스 상태: sleeping
- 실제 모델 파일 또는 XNNPACK cache를 열지 않음
- 네트워크 연결 없음
- stdout/stderr pipe는 열려 있음
- 7분 이상 진행되지 않음

531.8초 후 원인 확인을 마치고 점검자가 프로세스를 종료했다.

테스트 결과 파일에는 다음처럼 기록됐다.

```text
tests=1
failures=1
LiteRT-LM subprocess 종료 코드가 0이 아닙니다: 143
```

종료 코드 143은 모델 자체가 낸 오류가 아니라 점검자가 대기 프로세스에 SIGTERM을 보냈기 때문에 기록된 값이다.

### 9.2 원인

LiteRT-LM CLI 0.14.0의 실제 설치 소스는 stdin이 TTY가 아니면 다음을 실행한다.

```python
if not sys.stdin.isatty():
    piped_input = sys.stdin.read().strip()
```

Java `ProcessBuilder.start()`는 child stdin에 연결되는 pipe를 생성한다.

현재 runner는 다음을 하지 않는다.

```kotlin
process.outputStream.close()
```

따라서 child 입장에서는:

```text
stdin은 TTY가 아님
→ sys.stdin.read()
→ parent의 pipe writer가 열려 있음
→ EOF가 오지 않음
→ 모델 로딩 전 무기한 대기
```

구현상 timeout은 900초이므로 최종적으로는 timeout 처리가 실행될 수 있지만, 정상 모델 추론은 시작되지 않는다.

### 9.3 모델/경로 자체의 분리 검증

같은 CLI와 같은 모델에 stdin EOF만 명시해서 직접 실행했다.

```bash
"$HOME/litert-lm-env/bin/litert-lm" run \
  "$PWD/models/gemma3-1b-it-int4.litertlm" \
  --prompt '한 문장으로 인사해 주세요.' \
  --backend cpu \
  --cache disk \
  --temperature 0.1 \
  --top-k 20 \
  --max-num-tokens 4096 \
  </dev/null
```

실제 출력:

```text
안녕하세요! 😊
```

이 결과로 다음이 확인됐다.

- LiteRT-LM CLI 설치 정상
- 모델 파일 정상
- CPU backend 정상
- 모델 절대 경로 정상
- 한글과 공백이 포함된 경로 정상
- 모델 prompt 생성 정상
- 문제는 Desktop runner의 stdin 미종료

### 9.4 필요한 수정

subprocess 시작 직후 child stdin을 닫아야 한다.

개념적으로 필요한 처리:

```kotlin
val process = ProcessBuilder(command).start()
process.outputStream.close()
```

수정 후 반드시 다음 회귀 테스트가 필요하다.

1. stdin EOF가 오기 전까지 대기하는 stub executable
2. runner가 stdin을 닫아 stub이 즉시 종료하는지
3. 실제 `modelSmokeTest`
4. `model-only`
5. `full`에서 비-deterministic prompt
6. 실제 model-originated tool call

---

## 10. Debug log 구현

`--debug`에서 지원하는 stage:

```text
[INPUT]
[ROUTER]
[MODEL_REQUEST]
[MODEL_RAW_OUTPUT]
[PARSED_TOOL_CALL]
[TOOL_EXECUTION]
[TOOL_RESULT]
[FINAL_RESPONSE]
```

기본 실행에서는 상세 stage log를 출력하지 않는다.

항상 사용자에게 유용한 다음 결과는 출력한다.

```text
사용자 입력 >
라우팅 결과 >
도구 실행 결과 >
최종 응답 >
```

debug 메시지는 최대 12,000자로 제한된다.

전체 명함 DB나 전체 vector를 출력하는 코드는 확인되지 않았다.

---

## 11. 자동 테스트 현황

### 11.1 Desktop/JVM 강제 재실행

실행 명령:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew \
  :desktop-agent-runner:test \
  :agent-routing:test \
  :agent-core:test \
  :tool-contact:test \
  :tool-datetime:test \
  :search-core:test \
  --rerun-tasks
```

결과:

```text
BUILD SUCCESSFUL
30 actionable tasks: 30 executed
```

테스트 수:

| Test suite | 개수 | 실패 |
|---|---:|---:|
| `DesktopFileDataSourceTest` | 5 | 0 |
| `LiteRtLmProcessRunnerTest` | 2 | 0 |
| `ToolRegistryAndValidationTest` | 2 | 0 |
| `DesktopMockBackendsTest` | 2 | 0 |
| `DesktopAgentIntegrationTest` | 3 | 0 |
| `ModelToolCallParserTest` | 3 | 0 |
| `ContactSearchPromptParserTest` | 3 | 0 |
| `LocalToolRoutingModelGatewayTest` | 10 | 0 |
| `AgentKernelTest` | 2 | 0 |
| `AgentSessionManagerTest` | 1 | 0 |
| `SearchLookupServiceTest` | 2 | 0 |
| `ContactPluginsTest` | 2 | 0 |
| `DateTimePluginsTest` | 2 | 0 |
| **합계** | **39** | **0** |

### 11.2 검증되는 주요 항목

- search request → `search_contacts`
- 상세 조회 → search → get
- 명함 수정 → search → get → update
- 절대 시각 calendar route
- 상대 시각 → current datetime → calendar
- 현재 날짜·시각 route
- 직접 이메일 주소 compose
- 명함 이메일 조회 후 compose
- SMS compose
- tool call parser
- missing tool 처리
- 누락/빈 값/잘못된 타입 argument 처리
- 실제 Android asset JSON 로딩
- 없는 이름이 semantic false positive로 반환되지 않음
- 회사 검색
- 의미 기반 검색
- 특수문자 검색
- 동명이인 fixture
- Desktop update가 원본 파일을 수정하지 않음
- calendar mock 구조
- email mock 구조
- 모델 실패 시 deterministic fallback
- 한글과 공백 경로가 ProcessBuilder에서 단일 인자로 유지됨

### 11.3 테스트 공백

현재 일반 테스트가 검증하지 못하는 항목:

- subprocess child stdin이 닫히는지
- 실제 모델 smoke 성공
- 실제 model output → parser → ModelToolCall → registry → tool → final 전체 흐름
- full 모드의 실제 primary plain text 응답
- subprocess timeout 후 모든 reader/thread가 완전히 정리되는지
- Desktop interactive mode의 여러 turn 실제 stdin E2E
- standalone APK에 cache가 포함되지 않는지

### 11.4 Android 단위 테스트와 debug 빌드

실행 명령:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
```

결과:

```text
BUILD SUCCESSFUL
98 actionable tasks: 98 executed
```

Android unit test:

| Test suite | 개수 | 실패 |
|---|---:|---:|
| `ExampleUnitTest` | 1 | 0 |
| `Gemma3AgentModelGatewayTest` | 3 | 0 |
| **합계** | **4** | **0** |

`Gemma3AgentModelGatewayTest`가 검증하는 항목:

- known tool request가 primary보다 deterministic router에서 먼저 처리됨
- non-tool request는 primary가 처리함
- primary open 실패 시 deterministic router fallback

debug APK 빌드는 성공했다.

이 점검에서는 emulator, Android 실기기, instrumentation test를 실행하지 않았다.

---

## 12. 실제 router-only CLI 검증

실행 명령:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "김지원 명함 찾아줘."'
```

실제 핵심 출력:

```text
사용자 입력 > 김지원 명함 찾아줘.
[INPUT] 김지원 명함 찾아줘.
[ROUTER] deterministic_match=true
라우팅 결과 > search_contacts(query="김지원", limit=5)
[PARSED_TOOL_CALL] search_contacts(query="김지원", limit=5)
[TOOL_EXECUTION] search_contacts(query="김지원", limit=5)
[TOOL_RESULT] {
  "ok": true,
  "tool_capability": "contact.search",
  "data": {
    "results": [
      {
        "card_id": "C001",
        "name": "김지원",
        "company": "비전글로벌",
        "title": "대표이사",
        "location": "서울",
        "score": 55.913235545158386
      }
    ],
    "count": 1,
    "engine": "LocalEmbeddingEngine"
  }
}
최종 응답 > 명함 검색 결과입니다.
- 김지원 · 비전글로벌 · 대표이사 · 서울
```

이 실행으로 다음 전체 경로가 실제로 동작함을 확인했다.

```text
사용자 입력
→ deterministic router
→ ModelToolCall
→ AgentKernel
→ DefaultToolRegistry
→ DefaultToolExecutor
→ SearchContactsPlugin
→ DesktopFileDataSource의 실제 Android asset
→ 실제 검색 결과
→ tool result 기반 최종 응답
```

---

## 13. Android/Desktop dependency 분리 판정

### 13.1 공통 JVM 코드에서 Android import

다음 모듈의 main source에서는 Android import가 확인되지 않았다.

```text
agent-contract
agent-core
agent-routing
search-core
tool-contract
tool-contact
tool-datetime
tool-external-actions
desktop-agent-runner
```

### 13.2 Android import가 남은 위치

Android 의존은 다음에 제한된다.

```text
app
tool-android-intents
llm-litert
```

주요 의존:

- `android.content.Context`
- `android.content.Intent`
- `android.net.Uri`
- `android.provider.CalendarContract`
- `android.util.Log`
- AndroidX Compose
- AndroidX Lifecycle
- AndroidX Room

### 13.3 APK dependency

`app/build.gradle.kts`는 `desktop-agent-runner`에 의존하지 않는다.

Desktop subprocess, Desktop file source, Desktop mock 구현은 APK dependency graph에 들어가지 않는다.

debug APK는 standalone 모델 source set을 사용하지 않으므로 557MB 모델이 debug APK에 포함되지 않는다.

---

## 14. standalone APK asset cache 혼입 문제

### 14.1 현재 standalone 설정

`app/build.gradle.kts`는 standalone source set에 다음 디렉터리 전체를 추가한다.

```text
<repository>/models
```

`hjp-agent.litertlm`은 ignore pattern으로 제외한다.

예상 standalone 모델:

```text
models/gemma3-1b-it-int4.litertlm
```

### 14.2 Desktop disk cache

Desktop subprocess command는 항상 다음 옵션을 사용한다.

```text
--cache disk
```

LiteRT-LM은 모델과 같은 디렉터리에 다음 파일을 생성한다.

```text
models/gemma3-1b-it-int4.litertlm.xnnpack_cache_1783681953_584417280
```

확인 크기:

```text
약 485MB
```

### 14.3 실제 asset merge 검증

실행 명령:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew :app:mergeStandaloneAssets --rerun-tasks
```

결과:

```text
BUILD SUCCESSFUL
```

병합된 파일:

```text
gemma3-1b-it-int4.litertlm                                  약 557MB
gemma3-1b-it-int4.litertlm.xnnpack_cache_1783681953_...     약 485MB
cards/business_cards.json                                   911B
```

즉 다음 standalone APK를 만들면 불필요한 XNNPACK cache까지 asset으로 패키징될 가능성이 아니라, **현재 asset merge 단계에서 실제 포함되는 것이 확인됐다.**

### 14.4 영향

- standalone APK 크기 급증
- 모델 관련 asset이 불필요하게 중복
- 배포 파일에 Mac Desktop용 compiled cache 혼입
- 기기/버전별 cache 호환성 보장 불가
- 완료 조건의 “모델 파일이 APK에 중복 패키징되지 않음” 위반

### 14.5 필요한 수정 방향

다음 중 하나가 필요하다.

1. standalone asset source를 `models/` 전체가 아니라 정확한 모델 단일 파일로 제한
2. XNNPACK cache를 별도 cache directory에 생성
3. `ignoreAssetsPattern`에 cache 패턴 추가
4. standalone merge 단계에서 예상 asset allowlist 검증 task 추가

가장 안전한 방향은 모델 원본 디렉터리와 Desktop compiled cache 디렉터리를 분리하고, standalone에는 정확한 `.litertlm` 파일 하나만 포함하는 것이다.

---

## 15. Git 작업 트리 상태

### 15.1 현재 상태

현재 브랜치:

```text
android-app
```

현재 HEAD:

```text
993a5f5 Improve tool tester button layout
```

Desktop Agent 작업은 커밋되지 않았다.

tracked diff 통계:

```text
25 files changed
346 insertions
992 deletions
```

이 통계에는 미추적 신규 모듈과 파일이 포함되지 않는다.

### 15.2 주요 tracked 수정

```text
.gitignore
CLAUDE.md
README.md
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/hjp/MainActivity.kt
app/src/main/java/com/example/hjp/ui/theme/Color.kt
app/src/main/java/com/example/hjp/ui/theme/Theme.kt
app/src/main/java/com/example/hjp/ui/theme/Type.kt
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
build.gradle.kts
gradle.properties
gradle/libs.versions.toml
gradlew
settings.gradle.kts
```

### 15.3 기존 app 내부 구현 삭제

다음 legacy 파일은 삭제 상태다.

```text
app/src/main/java/com/example/hjp/agent/tools/AgentTool.kt
app/src/main/java/com/example/hjp/agent/tools/CreateCalendarEventTool.kt
app/src/main/java/com/example/hjp/agent/tools/GetCurrentDateTimeTool.kt
app/src/main/java/com/example/hjp/agent/tools/OpenComposeTool.kt
app/src/main/java/com/example/hjp/agent/tools/ToolRegistry.kt
app/src/main/java/com/example/hjp/agent/tools/UpdateBusinessCardTool.kt
app/src/main/java/com/example/hjp/data/BusinessCard.kt
app/src/main/java/com/example/hjp/data/SharedPreferencesBusinessCardStore.kt
app/src/androidTest/java/com/example/hjp/ExampleInstrumentedTest.kt
```

공통 모듈 및 Room 기반 구현으로 이동한 흔적이다.

### 15.4 신규 미추적 핵심 파일

Android:

```text
app/src/main/java/com/example/hjp/AgentViewModel.kt
app/src/main/java/com/example/hjp/AppContainer.kt
app/src/main/java/com/example/hjp/Gemma3AgentModelGateway.kt
app/src/main/java/com/example/hjp/HjpApplication.kt
app/src/main/java/com/example/hjp/data/AssetBusinessCardRepository.kt
app/src/main/java/com/example/hjp/data/BusinessCardDao.kt
app/src/main/java/com/example/hjp/data/BusinessCardEntity.kt
app/src/main/java/com/example/hjp/data/HjpDatabase.kt
app/src/main/java/com/example/hjp/data/RoomBusinessCardRepository.kt
app/src/main/assets/cards/business_cards.json
app/src/test/java/com/example/hjp/Gemma3AgentModelGatewayTest.kt
app/src/androidTest/java/com/example/hjp/AgentScreenInstrumentedTest.kt
```

공통/서버 독립 모듈:

```text
agent-contract/
agent-core/
agent-routing/
search-core/
tool-contract/
tool-contact/
tool-datetime/
tool-external-actions/
```

Android 전용 모듈:

```text
tool-android-intents/
llm-litert/
```

Desktop:

```text
desktop-agent-runner/
```

문서:

```text
DESKTOP_AGENT_TEST.md
PROJECT_ANALYSIS.md
ANDROID_APP_BRANCH_TOOL_ANALYSIS.md
GEMMA3_REAL_DEVICE_TEST_GUIDE.md
```

기타 미추적 파일:

```text
crash_full_log.txt
dist/
models/
scripts/
.kotlin/
```

이 기타 파일들이 모두 Desktop Agent 작업에 속한다고 단정할 수는 없으므로 커밋 전 소유와 필요 여부를 별도로 판단해야 한다.

### 15.5 `.gitignore` 문제

현재 항목:

```gitignore
/build
/models/*.litertlm
```

문제:

- `/build`는 저장소 루트의 `build`만 제외
- 각 신규 모듈의 `build/`는 제외되지 않음
- `.kotlin/` compiler session이 제외되지 않음
- `.litertlm.xnnpack_cache_*`는 `*.litertlm` 패턴에 해당하지 않음
- `dist/HJP-Gemma3-Standalone-arm64-v1.0.apk` 약 591MB가 제외되지 않음

점검 당시:

```text
신규 모듈 source/build script/test 파일: 약 68개
신규 모듈 build 산출물: 약 2,019개
```

현재 상태에서 다음을 실행하면 안 된다.

```bash
git add -A
```

먼저 ignore 규칙과 커밋 대상 allowlist를 정리해야 한다.

---

## 16. 문서 상태

Desktop 사용 문서:

```text
DESKTOP_AGENT_TEST.md
```

포함된 내용:

1. Desktop 실행기 목적
2. model-only와 전체 agent 차이
3. 필요한 환경
4. LiteRT-LM 가상환경 확인
5. 모델 및 명함 경로 설정
6. Gradle 실행 명령
7. `full`, `router-only`, `model-only`
8. 대화형 실행
9. 단일 prompt 실행
10. 테스트 명령
11. 예상 출력
12. Android 전용 tool이 mock이라는 설명
13. 문제 해결
14. Android/Desktop dependency 경계

문서 내용 대부분은 현재 코드와 일치한다.

그러나 다음 설명은 현재 실제 동작과 일치하지 않는다.

- `model-only`가 정상적으로 raw model output을 반환한다는 전제
- 실제 모델 smoke가 실행 가능하다는 전제
- README 명령만으로 full/model-only를 재현할 수 있다는 전제
- standalone packaging 규칙이 Desktop cache를 포함하지 않는다는 전제

subprocess stdin과 standalone cache 문제를 수정한 뒤 문서를 갱신해야 한다.

---

## 17. 완료 조건별 정확한 판정

| 번호 | 완료 조건 | 판정 | 근거 |
|---:|---|---|---|
| 1 | Android Studio/SDK 없이 Mac 터미널 실행 | 부분 충족 | `router-only`와 JVM test는 가능. 실제 model subprocess는 stdin blocker |
| 2 | 실제 `.litertlm` 선택 실행 | 미충족 | 직접 CLI는 성공하지만 Desktop runner에서 멈춤 |
| 3 | 기존 deterministic router 재사용 | 충족 | Android/Desktop 공통 `LocalToolRoutingModelGateway` |
| 4 | 기존 ToolRegistry 또는 공통 구현 재사용 | 충족 | 공통 `DefaultToolRegistry`, `DefaultToolExecutor` |
| 5 | 실제 프로젝트 명함 데이터 검색 | 충족 | Android asset `business_cards.json` 직접 사용 |
| 6 | 존재하지 않는 명함을 모델이 만들지 않음 | 부분 충족 | deterministic/tool-backed 응답은 grounding. 모든 plain text 모델 응답의 형식적 차단은 아님 |
| 7 | Android 전용 tool을 안전한 mock으로 처리 | 충족 | Calendar/Message Desktop backend |
| 8 | 상세 debug log 제공 | 충족 | 요구된 8개 stage 모두 존재 |
| 9 | 단위 테스트와 통합 테스트 추가 | 충족 | JVM 39개 통과, Android 4개 통과 |
| 10 | 기존 Android 앱/빌드 구조 보존 | 부분 충족 | debug 빌드 성공. standalone에 Desktop XNNPACK cache 혼입 |
| 11 | README 명령만으로 재현 가능 | 미충족 | model-only/full primary가 stdin 문제로 멈춤 |

핵심 기능의 완전 충족은 3, 4, 5, 7, 8, 9번이다.

부분 충족은 1, 6, 10번이다.

미충족은 2, 11번이다.

---

## 18. 남아 있는 필수 수정

우선순위 순서:

### P0. subprocess stdin 종료

- `ProcessBuilder.start()` 직후 child stdin 닫기
- stdin EOF stub 회귀 테스트 추가
- 실제 smoke 재실행

### P0. standalone cache 혼입 차단

- 모델 디렉터리와 Desktop cache 디렉터리 분리
- 또는 standalone asset을 정확한 모델 단일 파일로 제한
- asset allowlist 검증 추가

### P0. Git ignore 및 커밋 정리

- 모든 module `build/` 제외
- `.kotlin/` 제외
- XNNPACK cache 제외
- `dist/*.apk` 처리 결정
- build 산출물 없이 source만 stage

### P1. 실제 full/model 통합 테스트

- actual model plain text
- actual model tool call
- parser
- registry
- 실제 명함 tool
- grounded final response

### P1. 문서 갱신

- stdin 문제 수정 반영
- cache directory 정책 반영
- smoke 결과 반영

### P2. Desktop 명함 수정 영속성 정책

다음 중 하나를 명시적으로 결정해야 한다.

- 현재처럼 테스트 세션 메모리에서만 수정
- 별도 Desktop test DB/JSON copy에 저장
- 매 실행마다 원본 asset에서 복사한 임시 fixture에 저장

Android asset 원본을 직접 수정하는 방식은 피하는 것이 안전하다.

---

## 19. 수정 후 반드시 실행할 자동 검증

### 19.1 JVM 단위·통합 테스트

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew \
  :desktop-agent-runner:test \
  :agent-routing:test \
  :agent-core:test \
  :tool-contact:test \
  :tool-datetime:test \
  :search-core:test \
  --rerun-tasks
```

### 19.2 실제 모델 smoke

```bash
RUN_LITERT_LM_SMOKE_TEST=1 \
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew :desktop-agent-runner:modelSmokeTest --rerun-tasks
```

필수 성공 조건:

```text
실제 모델 파일 open
exit code 0
generatedText non-empty
timeout 미발생
```

### 19.3 Android 단위 테스트와 debug 빌드

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
```

### 19.4 standalone asset 검증

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew :app:mergeStandaloneAssets --rerun-tasks
```

병합 asset에는 다음만 있어야 한다.

```text
gemma3-1b-it-int4.litertlm
cards/business_cards.json
```

다음은 없어야 한다.

```text
*.xnnpack_cache_*
hjp-agent.litertlm
Desktop 실행 파일
Desktop mock class
```

---

## 20. 수정 후 Mac 수동 검증 절차

### 20.1 환경 확인

```bash
test -x "$HOME/litert-lm-env/bin/litert-lm"
"$HOME/litert-lm-env/bin/litert-lm" --version
"$HOME/litert-lm-env/bin/litert-lm" --help
"$HOME/litert-lm-env/bin/litert-lm" run --help
test -r "$PWD/models/gemma3-1b-it-int4.litertlm"
```

예상:

```text
litert-lm, version 0.14.0
```

### 20.2 router-only 단일 prompt

실제 존재 이름:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "김지원 명함 찾아줘."'
```

없는 이름:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "테스트미존재인물 명함 찾아줘."'
```

회사:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "비전글로벌 명함 찾아줘."'
```

직책/의미:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "판교 AI 개발자 명함 찾아줘."'
```

상세 조회:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "김지원 연락처 보여줘."'
```

현재 시각:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "지금 몇 시야?"'
```

캘린더:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "내일 오후 3시에 팀 회의 일정 잡아줘."'
```

메일:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "김지원에게 내용은 안녕하세요 라고 메일 작성해줘."'
```

SMS:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "김지원에게 회의에 늦는다고 문자 보내줘."'
```

명함 수정:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --debug --prompt "김지원 명함 메모를 VIP로 수정해줘."'
```

확인할 사항:

- 실제 JSON에 있는 값만 출력
- 없는 이름은 `찾지 못했습니다`
- 캘린더 Intent 실행 안 함
- 메일/SMS 실제 전송 안 함
- mock 구조에 제목, 시각, 수신자, 본문 포함
- 상세 log stage 누락 없음

### 20.3 대화형 router-only

```bash
./gradlew :desktop-agent-runner:run --args='--mode router-only --debug'
```

한 프로세스 안에서 여러 요청을 실행한다.

```text
김지원 명함 찾아줘.
김지원 연락처 보여줘.
지금 몇 시야?
exit
```

### 20.4 model-only

stdin 수정 후:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode model-only --debug --prompt "한 문장으로 인사해 주세요."'
```

필수 확인:

- 경고 출력
- 모델 request 출력
- raw output 출력
- `ToolRegistry와 명함 데이터베이스를 사용하지 않습니다` 표시
- timeout 없이 종료

### 20.5 full 비-tool prompt

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode full --debug --prompt "한 문장으로 인사해 주세요."'
```

필수 확인:

- deterministic match가 false
- LiteRT-LM 실제 실행
- model raw output 출력
- 정상 final response

### 20.6 full deterministic prompt

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode full --debug --prompt "김지원 명함 찾아줘."'
```

필수 확인:

- deterministic match가 true
- 모델 subprocess를 시작하지 않음
- search tool 실행
- 실제 asset 결과
- grounded final response

### 20.7 full model-originated tool call

deterministic parser가 직접 매칭하지 않는 표현을 준비하되, 모델이 tool을 선택하도록 요청한다.

확인할 경로:

```text
MODEL_REQUEST
→ MODEL_RAW_OUTPUT
→ PARSED_TOOL_CALL
→ TOOL_EXECUTION
→ TOOL_RESULT
→ FINAL_RESPONSE
```

모델이 tool call 대신 plain text를 반환할 가능성이 있으므로 실제 raw output을 기록하고, 필요하면 system instruction 및 prompt 형식을 조정해야 한다.

### 20.8 오류 경로

실행 파일 없음:

```bash
LITERT_LM_BIN=/missing/litert-lm \
./gradlew :desktop-agent-runner:run \
  --args='--mode model-only --prompt "안녕"'
```

모델 없음:

```bash
LITERT_LM_MODEL=/missing/model.litertlm \
./gradlew :desktop-agent-runner:run \
  --args='--mode model-only --prompt "안녕"'
```

확인할 메시지:

```text
LiteRT-LM 실행 파일을 찾을 수 없습니다: ...
모델 파일을 찾을 수 없습니다: ...
```

### 20.9 Git 최종 점검

```bash
git status --short
git diff --check
git ls-files --others --exclude-standard
```

필수 확인:

- module `build/` 없음
- `.kotlin/` 없음
- XNNPACK cache 없음
- APK 없음
- 모델 바이너리 없음
- 의도한 source/test/document만 남음

---

## 21. 현재 실행 가능한 명령과 주의사항

### 안전하게 실행 가능한 명령

```bash
./gradlew :desktop-agent-runner:test
./gradlew :agent-routing:test :agent-core:test
./gradlew :desktop-agent-runner:run --args='--mode router-only'
./gradlew :desktop-agent-runner:run \
  --args='--mode router-only --prompt "김지원 명함 찾아줘."'
```

### 현재 blocker 때문에 권장하지 않는 명령

```bash
./gradlew :desktop-agent-runner:run --args='--mode model-only --prompt "안녕"'
./gradlew :desktop-agent-runner:run --args='--mode full --prompt "안녕"'
RUN_LITERT_LM_SMOKE_TEST=1 ./gradlew :desktop-agent-runner:modelSmokeTest
```

이 명령들은 subprocess stdin 수정 전에는 timeout까지 대기할 수 있다.

### 직접 CLI 모델 확인

Desktop runner를 우회한 모델 자체 확인은 stdin을 명시적으로 닫으면 가능하다.

```bash
"$HOME/litert-lm-env/bin/litert-lm" run \
  "$PWD/models/gemma3-1b-it-int4.litertlm" \
  --prompt "한 문장으로 인사해 주세요." \
  --backend cpu \
  </dev/null
```

이 직접 CLI 성공은 모델 자체만 확인한다. AgentKernel, ToolRegistry, 명함 데이터베이스가 연결된 전체 agent 성공을 의미하지 않는다.

---

## 22. 최종 상태 요약

```text
[구현 완료]
- 공통 AgentKernel
- 공통 ModelToolCall
- 공통 deterministic routing
- 공통 ToolRegistry/Executor
- 실제 명함 asset 검색
- 명함 조회 및 수정 plugin
- Android Calendar/Email/SMS 실제 backend
- Desktop Calendar/Email/SMS mock
- current datetime tool
- Desktop CLI와 3개 모드 코드
- debug tracing
- 단위/통합 테스트
- Desktop 사용 문서

[검증 완료]
- JVM test 39개 통과
- Android unit test 4개 통과
- Android debug APK 강제 빌드 성공
- router-only 실제 CLI 성공
- LiteRT-LM 0.14.0 help 확인
- 실제 모델 직접 CLI 추론 성공
- 한글/공백 모델 경로 성공

[미완료/문제]
- ProcessBuilder child stdin 미종료
- full/model-only 실제 모델 경로 멈춤
- real model smoke 미통과
- model-originated tool call 실제 통합 미검증
- standalone에 485MB XNNPACK cache 혼입
- 하위 module build 산출물 미ignore
- 전체 작업 미커밋
- Desktop 명함 수정 비영속
- instrumentation/실기기 검증 미실행
```

현재 가장 먼저 해결해야 할 두 가지는 다음이다.

```text
1. LiteRtLmProcessRunner에서 child stdin 닫기
2. standalone asset에서 XNNPACK cache 제외
```

그 다음 Git ignore와 staging 대상을 정리하고, 실제 model smoke 및 full mode 전체 흐름을 다시 검증해야 완료로 판단할 수 있다.
