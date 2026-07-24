# Gemma 4 E2B IT Desktop Agent 평가 보고서

평가일: 2026-07-24  
평가 환경: Apple Silicon Mac, LiteRT-LM 0.14.0, CPU backend

> 이 문서의 “기본 모델 유지” 결론은 당시 Desktop 비교 평가에 대한 기록이다.
> `Agent_Gemma_4_E2B_0711` Android 배포 variant는 사용 목적에 따라 Gemma 4
> E2B IT 하나만 포함한다. 평가상 native tool 정확도가 기준 미달이므로
> routing-first, Compose orchestrator, validator와 fallback은 제거하지 않았다.

## 1. 결론

Gemma 4 E2B IT 모델은 현재 저장소 밖의 다음 실제 파일로 검증했다.

```text
파일명: gemma-4-E2B-it.litertlm
검증 경로: $HJP_GEMMA4_MODEL
크기: 2,588,147,712 bytes
SHA-256: 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
```

파일을 현재 저장소나 Git에 복사하지 않았다.

`litertlm-jvm:0.14.0`의 공식 Kotlin/JVM API로 CPU 로딩, system instruction,
`OpenApiTool`, `automaticToolCalling=false`, `Message.toolCalls`,
`Content.ToolResponse`, 같은 conversation의 후속 tool call이 모두 실제 동작했다.

Gemma 4는 Gemma 3보다 native tool-calling 성능이 크게 높았지만 권장 기준에는
미달했다. 따라서 기본 모델은 `gemma3-1b-it-int4`로 유지하며 Gemma 4를 강제로
primary로 전환하지 않았다. Gemma 4를 선택해도 다음 안전 구조를 그대로 유지한다.

```text
routing-first
+ 선택 모델의 draft generation
+ Compose application orchestrator
+ tool argument validation
+ grounding validator / safe fallback
```

## 2. 모델 선택 방법

Desktop 환경변수:

```bash
export LITERT_LM_MODEL="/absolute/path/to/model.litertlm"
export LITERT_LM_MODEL_ID="gemma4-e2b-it"
```

Desktop CLI:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode model-agent --debug --model "/absolute/path/gemma-4-E2B-it.litertlm" --model-id gemma4-e2b-it --prompt "김지원 명함을 찾아줘."'
```

지원 model ID:

```text
gemma3-1b-it-int4
gemma4-e2b-it
```

`--model-id gemma4-e2b-it`만 지정하면 저장소의
`models/gemma-4-E2B-it.litertlm`만 찾는다. 파일이 없을 때 Gemma 3를 대신
사용하지 않으며 다음 형식으로 실패한다.

```text
모델 파일을 찾을 수 없습니다: .../models/gemma-4-E2B-it.litertlm
```

알 수 없는 model ID도 명확히 거부한다. `--model`만 지정하면 알려진 파일명에서
ID를 추론하며, 추론할 수 없는 파일명에는 `--model-id`를 요구한다.

debug 로그에는 실제 선택값이 남는다.

```text
[MODEL_CONFIG] model_id=gemma4-e2b-it model_path=... model_size_bytes=2588147712
[MODEL_DECISION_SOURCE] decision_source=gemma4-e2b-it
```

## 3. 공식 JVM API 호환성

공통 구현은 `llm-litert-common`의 `LiteRtNativeAgentModelGateway`다. Android
facade인 `llm-litert`와 Desktop이 같은 adapter를 사용하므로 native tool-call
변환 코드를 복제하지 않았다.

Gemma 4 파일 metadata에서 다음 항목을 실제 확인했다.

```text
container version: 1.5.0
llm_model_type: gemma4
code_fence_start: <|tool_call>
code_fence_end: <tool_call|>
function_response_start: <|tool_response>
use_template_for_fc_format: true
```

모델 안에 Gemma 4용 Jinja function/tool-response template이 포함되어 있으며,
LiteRT-LM API가 이를 자동 적용했다. Gemma 3용 prompt 문자열이나 tool-call
template을 Gemma 4에 수동 재사용하지 않았다.

검증된 API 흐름:

```text
Engine
→ ConversationConfig(systemInstruction, OpenApiTool 목록)
→ automaticToolCalling=false
→ sendMessage(user)
→ Message.toolCalls
→ 공통 ModelToolCall
→ AgentKernel / DefaultToolRegistry
→ Content.ToolResponse
→ 같은 Conversation.sendMessage(tool response)
→ 후속 tool call 또는 final message
→ grounded formatter
```

## 4. 실제 native tool-call 로그

### 4.1 명함 검색

입력:

```text
김지원 명함을 찾아줘.
```

주요 로그:

```text
[ROUTING_POLICY] deterministic_router_bypassed=true
[MODEL_DECISION_SOURCE] decision_source=gemma4-e2b-it
[PARSED_TOOL_CALL] {"source":"gemma4-e2b-it","name":"search_contacts","arguments":{"query":"김지원"}}
[TOOL_RESULT] {"ok":true,...,"card_id":"C001","name":"김지원","company":"비전글로벌","title":"대표이사",...}
[TOOL_RESPONSE_SENT_TO_MODEL] {"name":"search_contacts","response":{...}}
[GROUNDING_VALIDATION] valid=true strategy=strict_grounded_formatter tool=search_contacts model_text_discarded=true
[FINAL_RESPONSE] 명함 검색 결과입니다.
- 김지원 · 비전글로벌 · 대표이사
```

이 호출은 deterministic router가 아니라 Gemma 4의 `Message.toolCalls`에서
변환됐다.

### 4.2 현재 시각

입력:

```text
현재 시간 알려줘.
```

주요 로그:

```text
[PARSED_TOOL_CALL] {"source":"gemma4-e2b-it","name":"get_current_datetime","arguments":{}}
[TOOL_RESULT] {"ok":true,...,"date":"2026-07-24","time":"20:58:43","timezone":"Asia/Seoul",...}
[TOOL_RESPONSE_SENT_TO_MODEL] {"name":"get_current_datetime","response":{...}}
```

### 4.3 같은 conversation의 연속 호출

입력:

```text
2026년 7월 10일 오후 2시 회의 일정 만들어줘.
```

실제 sequence:

```text
get_current_datetime()
→ Content.ToolResponse
→ create_calendar_event(start_time="2026-07-10T14:00:00", title="회의 일정")
→ Desktop calendar mock
```

실제 tool result:

```json
{
  "status": "mock_success",
  "tool": "create_calendar_event",
  "title": "회의 일정",
  "start_time": "2026-07-10T14:00",
  "end_time": "2026-07-10T15:00",
  "message": "Desktop 테스트에서는 Android 캘린더 Intent를 실행하지 않았습니다."
}
```

모델이 외부 동작을 완료했다고 과장한 문장을 생성할 수 있어, Desktop
`model-agent`의 strict grounding은 모든 tool final에서 모델 자유 텍스트를
버리고 공통 `GroundedToolResultFormatter`를 사용한다.

## 5. native Compose에서 확인한 한계와 차단

Gemma 4에 다음 요청을 native `model-agent`로 직접 주면:

```text
test@example.com에게 내용은 안녕하세요라고 메일 작성해줘.
```

다음 call을 만들었지만 `body`를 누락했다.

```text
open_compose(channel="email", subject="안녕하세요", to="test@example.com")
```

또 다른 실제 실행에서는 이름 검색 후 SMS의 `to`에 전화번호 대신 명함 ID
`C001`을 넣고 본문을 `subject`에 넣었다.

```text
search_contacts(query="김지원")
→ open_compose(channel="sms", subject="안녕하세요", to="C001")
```

이를 안전하게 막기 위해 `OpenComposePlugin` input codec에 채널별 교차 검증을
추가했다.

- email: 실제 이메일 주소 형식만 허용
- sms: 8~15자리 실제 전화번호 형식만 허용
- 이름이나 명함 ID는 `to`로 허용하지 않음

따라서 native model이 잘못된 수신자를 만들더라도 Android Intent 또는 Desktop
mock을 열기 전에 `tool.invalid_arguments`로 차단된다. 안전하고 완전한
메일·SMS 흐름은 계속 hybrid Compose orchestrator를 사용한다.

## 6. 의도 기반 Compose 실제 결과

### 6.1 직접 이메일

입력:

```text
test@example.com한테 지난번 미팅에 대한 감사 내용을 담아서 메일 작성해줘.
```

Gemma 4 raw JSON:

```json
{
  "subject": "지난번 미팅에 감사드립니다.",
  "body": "안녕하세요. 지난번 미팅에 귀한 시간 내어주셔서 진심으로 감사드립니다. 덕분에 많은 것을 배우고 유익한 시간을 보낼 수 있었습니다. 앞으로도 좋은 기회로 다시 뵐 수 있기를 기대합니다. 항상 건강하시고 평안하시기를 바랍니다. 감사합니다."
}
```

JSON parsing은 성공했지만 메일 2~5문장 제한을 넘었으므로 validator가
`invalid_sentence_count`로 거부했다.

```text
draft_source=gemma4-e2b-it
json_parsed=true
draft_json_valid=false
fallback_used=true
tool_call_source=application_orchestrator
```

최종 application-generated call:

```text
open_compose(
  channel="email",
  to="test@example.com",
  subject="미팅 감사드립니다",
  body="안녕하세요. 지난 미팅에서 귀한 시간을 내주셔서 감사합니다. 다시 한번 감사드립니다."
)
```

Desktop mock이 성공했고 실제 전송은 하지 않았다.

### 6.2 이름 기반 SMS

입력:

```text
김지원에게 지난 상담에 감사하고 다음 주에 다시 연락드리겠다는 문자를 작성해줘.
```

실제 흐름:

```text
search_contacts(query="김지원")
→ get_contact(card_id="C001", purpose="sms")
→ 실제 명함 전화번호 010-0000-0001
→ Gemma 4 draft
→ application-generated open_compose
```

Gemma 4 raw JSON:

```json
{
  "body": "김지원님, 지난번 상담에 감사드립니다. 다음 주에 다시 연락드리겠습니다. 좋은 하루 보내세요."
}
```

검증 결과:

```text
draft_source=gemma4-e2b-it
json_parsed=true
draft_json_valid=true
fallback_used=false
tool_call_source=application_orchestrator
```

최종 call:

```text
open_compose(
  channel="sms",
  to="010-0000-0001",
  body="김지원님, 지난번 상담에 감사드립니다. 다음 주에 다시 연락드리겠습니다. 좋은 하루 보내세요."
)
```

## 7. 동일 53개 평가 비교

같은 `gemma3_tool_eval.jsonl`, CPU backend, tool registry, 명함 asset과 정책으로
두 모델을 평가했다. email/SMS 11건은 안전한 `full` Compose 경로, 나머지
42건은 router를 우회한 `model-agent` native 경로다.

| 지표 | Gemma 3 1B IT | Gemma 4 E2B IT |
|---|---:|---:|
| tool_call_parse_rate | 0.0000 | 0.8158 |
| tool_selection_accuracy | 0.0952 | 0.7857 |
| argument_accuracy | 0.0000 | 0.7632 |
| tool_sequence_accuracy | 0.0952 | 0.7143 |
| tool_execution_success_rate | 0.0000 | 0.6579 |
| no_tool_false_positive_rate | 0.0000 | 0.0000 |
| grounded_answer_rate | 1.0000 | 0.9286 |
| hallucinated_contact_count | 0 | 0 |
| compose_intent_accuracy | 1.0000 | 1.0000 |
| recipient_resolution_accuracy | 1.0000 | 1.0000 |
| draft_generation_success_rate | 0.2500 | 0.6250 |
| draft_json_parse_rate | 1.0000 | 1.0000 |
| open_compose_execution_rate | 1.0000 | 1.0000 |
| recipient_hallucination_count | 0 | 0 |
| fallback_rate | 0.7500 | 0.3750 |

`draft_generation_success_rate`는 validator를 통과해 fallback 없이 사용한 모델
초안 비율이다. `draft_json_parse_rate`는 JSON 문법 parse 성공 비율이므로,
JSON을 parse했어도 문장 수·세부사항 정책 위반으로 fallback이 적용될 수 있다.

Gemma 4 실패 17건의 대표 원인:

- 검색 결과만 보고 `get_contact`를 생략
- `get_contact(purpose="phone")`처럼 schema enum에 없는 값 생성
- 상대 날짜 일정에서 평가셋 기대 sequence와 다른 추가 datetime 호출 또는 날짜 계산
- 모호 요청에서 tool 대신 확인 문장 반환
- update 요청에서 tool 미호출, get 단계 생략, 상충하는 `clear_fields`와 `updates`
- 일부 draft의 문장 수 또는 안전 정책 위반으로 fallback
- `전략팀` 검색은 tool 선택은 맞았지만 현재 검색 backend가 0건 반환

상세 실패에는 prompt, expected/actual tools, arguments, raw output, tool result,
final answer와 failure reason이 저장된다.

```text
desktop-agent-runner/build/reports/model-eval/gemma3-1b-it-int4/result.json
desktop-agent-runner/build/reports/model-eval/gemma3-1b-it-int4/result.csv
desktop-agent-runner/build/reports/model-eval/gemma4-e2b-it/result.json
desktop-agent-runner/build/reports/model-eval/gemma4-e2b-it/result.csv
desktop-agent-runner/build/reports/model-eval/model-comparison.json
desktop-agent-runner/build/reports/model-eval/model-comparison.csv
```

## 8. CPU 성능과 메모리

동일 prompt `김지원 명함을 찾아줘.`, 동일 CPU backend, 동일 JVM adapter로 한
번씩 측정했다.

| 측정값 | Gemma 3 1B IT | Gemma 4 E2B IT |
|---|---:|---:|
| 모델 파일 크기 | 584,417,280 B | 2,588,147,712 B |
| engine initialization | 1,580 ms | 1,192 ms |
| first request latency | 760 ms | 6,263 ms |
| total response latency | 2,404 ms | 10,888 ms |
| peak current JVM RSS | 1,359,600 KB | 3,091,408 KB |
| native tool call 성공 | false | true |
| draft generation | 2,898 ms | 5,309 ms |

초기화는 기존 disk cache가 준비된 warm 상태의 단일 측정이므로 Gemma 4가
항상 더 빠르다는 뜻이 아니다. latency와 RSS는 반복 benchmark의 평균이 아닌
이번 실제 smoke measurement다.

RSS는 `ps -o rss= -p <current-jvm-pid>`를 100ms 간격으로 샘플링한 현재 JVM
프로세스의 peak resident set이다. CLI subprocess RSS와 GPU memory는 포함하지
않으므로 전체 시스템 메모리라고 표현하지 않는다. 이번 비교는 CPU만 실행했다.

```text
desktop-agent-runner/build/reports/model-performance/gemma3-1b-it-int4.json
desktop-agent-runner/build/reports/model-performance/gemma4-e2b-it.json
desktop-agent-runner/build/reports/model-performance/model-comparison.json
desktop-agent-runner/build/reports/model-performance/model-comparison.csv
```

## 9. Android 선택과 standalone 패키징

Gemma 3 설정은 기본값으로 보존했다. Gemma 4 선택:

```bash
./gradlew :app:assembleDebug :app:mergeStandaloneAssets \
  -PhjpModelId=gemma4-e2b-it \
  -PhjpModelPath="/absolute/path/gemma-4-E2B-it.litertlm"
```

Gradle이 선택 model ID, 파일명, 크기와 SHA-256을 `BuildConfig`에 넣는다.
Android `AppContainer`는 선택된 파일명과 model ID를 사용하고, 모델별 cache를
`cacheDir/litertlm/<model-id>`로 분리한다.

debug APK는 모델을 포함하지 않는다. standalone은 선택 모델 하나를 generated
asset으로 stage하고 size·SHA-256·merged asset allowlist를 검증한다.

Gemma 4 실제 merge 결과:

```text
gemma-4-E2B-it.litertlm      2,588,147,712 bytes
cards/business_cards.json              911 bytes
```

`*.xnnpack_cache_*`, Desktop cache, Gemma 3 모델은 Gemma 4 standalone 결과에
포함되지 않았다. Android의 routing-first, emulator deterministic fallback,
실제 Calendar/Email/SMS Intent, Room 명함 저장은 변경하지 않았다.

## 10. 재현 명령

Gemma 4 native 단일 prompt:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode model-agent --debug --model "/absolute/path/gemma-4-E2B-it.litertlm" --model-id gemma4-e2b-it --prompt "김지원 명함을 찾아줘."'
```

Gemma 4 hybrid Compose:

```bash
./gradlew :desktop-agent-runner:run \
  --args='--mode full --debug --model "/absolute/path/gemma-4-E2B-it.litertlm" --model-id gemma4-e2b-it --prompt "test@example.com한테 지난번 미팅에 대한 감사 내용을 담아서 메일 작성해줘."'
```

Gemma 3 평가:

```bash
./gradlew :desktop-agent-runner:modelAgentEval
```

Gemma 4 평가:

```bash
./gradlew :desktop-agent-runner:modelAgentEval \
  --args='--model "/absolute/path/gemma-4-E2B-it.litertlm" --model-id gemma4-e2b-it'
```

성능 측정:

```bash
./gradlew :desktop-agent-runner:modelPerformance
./gradlew :desktop-agent-runner:modelPerformance \
  --args='--model "/absolute/path/gemma-4-E2B-it.litertlm" --model-id gemma4-e2b-it'
```

일반 회귀와 실제 smoke:

```bash
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:mergeStandaloneAssets
RUN_LITERT_LM_SMOKE_TEST=1 ./gradlew :desktop-agent-runner:modelSmokeTest
```

## 11. 기본 모델 채택 판단

권장 기준과 Gemma 4 결과:

| 기준 | 요구 | 결과 | 판정 |
|---|---:|---:|---|
| tool_selection_accuracy | >= 0.90 | 0.7857 | 실패 |
| argument_accuracy | >= 0.90 | 0.7632 | 실패 |
| tool_execution_success_rate | >= 0.90 | 0.6579 | 실패 |
| no_tool_false_positive_rate | <= 0.05 | 0.0000 | 통과 |
| hallucinated_contact_count | 0 | 0 | 통과 |
| recipient_hallucination_count | 0 | 0 | 통과 |
| open_compose_execution_rate | 1.0 | 1.0 | 통과 |

Gemma 4를 기본 primary로 채택하지 않았다. 선택 실행과 Android standalone
빌드는 지원하지만 운영 기본 정책은 계속 routing-first와 안전한 Compose
orchestrator다.

## 12. 수정 파일

핵심 변경 파일:

```text
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopConfig.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopAgentRunner.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/DesktopTracing.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/Main.kt
desktop-agent-runner/src/main/kotlin/com/hjp/desktop/SubprocessMessageDraftGenerator.kt
desktop-agent-runner/src/modelEval/kotlin/com/hjp/desktop/ModelAgentEvalMain.kt
desktop-agent-runner/src/modelEval/kotlin/com/hjp/desktop/ModelPerformanceMain.kt
desktop-agent-runner/src/test/kotlin/com/hjp/desktop/DesktopConfigModelSelectionTest.kt
desktop-agent-runner/build.gradle.kts
llm-litert-common/src/main/kotlin/com/hjp/agent/litert/common/LiteRtNativeAgentModelGateway.kt
llm-litert/src/main/kotlin/com/hjp/agent/litert/LiteRtAgentModelGateway.kt
tool-external-actions/src/main/kotlin/com/hjp/tool/android/AndroidIntentPlugins.kt
tool-external-actions/src/main/kotlin/com/hjp/tool/android/AndroidIntentToolContracts.kt
tool-external-actions/src/main/kotlin/com/hjp/tool/android/SafeMessageDraftGenerator.kt
tool-external-actions/src/test/kotlin/com/hjp/tool/android/OpenComposePluginTest.kt
agent-routing/src/main/kotlin/com/example/hjp/LocalToolRoutingModelGateway.kt
app/build.gradle.kts
app/src/main/java/com/example/hjp/AppContainer.kt
DESKTOP_AGENT_CURRENT_STATUS.md
DESKTOP_AGENT_TEST.md
GEMMA4_E2B_EVALUATION.md
```

## 13. 남은 제약사항

- Gemma 4 native tool calling은 실제 동작하지만 선택·인자·sequence 정확도가
  운영 primary 기준에 미달한다.
- sampling 설정과 단일 실행의 영향으로 같은 prompt에서도 tool call 대신 확인
  문장을 반환할 수 있다.
- native Compose는 본문 누락, 명함 ID 수신자, SMS text를 subject에 넣는 오류가
  있어 application orchestrator를 제거할 수 없다.
- update 요청의 다단계 계획과 상충 인자 생성이 불안정하다.
- 상대 날짜 일정 계산과 평가셋 기대 sequence가 어긋나는 사례가 있다.
- Gemma 4는 Gemma 3보다 파일이 약 4.43배 크고 이번 측정에서 peak JVM RSS와
  응답 latency가 크게 증가했다.
- GPU/NPU 비교는 이번 작업에서 실행하지 않았다. CPU 성공 사실을 다른 backend
  지원으로 확대 해석하지 않는다.
