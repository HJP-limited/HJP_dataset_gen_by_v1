# HJP Gemma 4 E2B 온디바이스 에이전트

이 브랜치는 Android 운영 APK를 Gemma 4 E2B IT 하나로 구성하고, 모델이 없는
Android Emulator APK를 별도 variant로 제공한다. 모델·APK·평가 산출물은 Git에
포함하지 않는다.

명함 검색·조회는 Ryeong `llm-integration-work@0bfd236efa40987c8f0a620d256be9c099080274`
구현을 canonical source로 사용한다. 0711의 lexical/cosine 직접 가산 경로는 제거했고,
production 경로는 `QueryAnalyzer → keyword → local semantic → RRF(k=60) →
safety gate → RagContextBuilder` 하나뿐이다.

## Android 실행 구조

```text
사용자 요청
→ routing-first
→ 명확한 기능 요청: deterministic router
→ 비정형 요청: Gemma 4 native tool calling
→ schema / argument / policy validation
→ AgentKernel → DefaultToolRegistry → 실제 tool
→ grounding / safe fallback
```

메일과 SMS는 native `open_compose`를 신뢰하지 않는다.

```text
Compose intent
→ 직접 주소 또는 search_contacts → get_contact
→ 동일 Gemma 4 Engine의 별도 draft conversation
→ draft validator / safe template fallback
→ application orchestrator가 open_compose 생성
→ 기존 validation / policy / registry
→ Android ACTION_SENDTO
```

Gemma 4 Engine은 프로세스당 하나의 lazy resource다. 판단과 초안 생성은 같은
Engine을 공유하고 conversation만 분리한다. 모든 native inference는 mutex queue로
직렬화된다. deterministic router 요청은 Engine을 초기화하지 않는다. 초기화나 추론
실패 시 routing-first gateway가 모델 없는 router 기능을 계속 제공한다.

## Android variants

| Variant | Gradle task | 모델 | LiteRT-LM runtime | ABI |
|---|---|---:|---:|---|
| `deviceStandalone` | `:app:assembleDeviceStandalone` | Gemma 4 E2B IT 1개 | 포함 | arm64-v8a |
| `emulatorDebug` | `:app:assembleEmulatorDebug` | 0개 | app graph에서 제외 | Android SDK 기본 ABI |

실기기 모델의 고정 검증값:

```text
file: gemma-4-E2B-it.litertlm
size: 2588147712 bytes
sha256: 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
model id: gemma4-e2b-it
```

### 실기기 APK

```bash
export HJP_GEMMA4_MODEL="/absolute/path/to/gemma-4-E2B-it.litertlm"
./scripts/build-device-gemma4.sh
./scripts/install-device-apk.sh
```

파일명·크기·SHA-256이 하나라도 다르면 빌드가 실패한다. 모델은
`deviceStandalone` generated asset으로 한 번만 stage되며, XNNPACK cache는
패키징하지 않는다.

### 에뮬레이터 APK

```bash
./scripts/build-emulator-apk.sh
```

에뮬레이터 UI에는 `에뮬레이터 모드: 온디바이스 LLM 미사용`이 표시된다.
`AgentKernel`, `DefaultToolRegistry`, Room seed/CRUD, 명함 검색·조회·수정,
argument validation, grounding, Calendar/Email/SMS Intent는 그대로 사용한다.

## 테스트

JDK 21과 Android SDK 36.1이 필요하다.

```bash
./gradlew test
./gradlew :search-core:searchEvaluation
./gradlew :app:testEmulatorDebugUnitTest
./gradlew :app:assembleEmulatorDebug
./gradlew :app:lintEmulatorDebug
./scripts/verify-repository-hygiene.sh
```

실제 모델이 있는 로컬 환경:

```bash
HJP_GEMMA4_MODEL="/absolute/path/to/gemma-4-E2B-it.litertlm" \
  ./gradlew :app:assembleDeviceStandalone

LITERT_LM_MODEL="/absolute/path/to/gemma-4-E2B-it.litertlm" \
LITERT_LM_MODEL_ID="gemma4-e2b-it" \
RUN_LITERT_LM_SMOKE_TEST=1 \
  ./gradlew :desktop-agent-runner:modelSmokeTest
```

Desktop 평가 실행기는 Android APK dependency graph에 포함되지 않는다.
상세 사용법은 [DESKTOP_AGENT_TEST.md](DESKTOP_AGENT_TEST.md), 모델 비교 결론은
[GEMMA4_E2B_EVALUATION.md](GEMMA4_E2B_EVALUATION.md)를 참고한다.

## 문서

- [Gemma 4 실기기 빌드·검증](docs/REAL_DEVICE_GEMMA4_TEST.md)
- [모델 없는 에뮬레이터 테스트](docs/EMULATOR_TEST.md)
- [모델 준비와 SHA-256 검증](docs/MODEL_PREPARATION.md)
- [운영 아키텍처](docs/ARCHITECTURE.md)
- [Ryeong 검색 통합 내역](docs/RYEONG_SEARCH_INTEGRATION.md)
- [검색 아키텍처](docs/SEARCH_ARCHITECTURE.md)
- [합성 검색 평가](docs/SEARCH_EVALUATION.md)

## 모듈

```text
app                       UI, Room, Android composition root와 product flavors
agent-contract            모델/세션 계약
agent-core                Kernel, Registry, validation/policy, executor
agent-routing             공통 deterministic router와 grounding
tool-contract             tool schema와 plugin 계약
tool-contact              실제 명함 search/get/update
tool-external-actions     Calendar/Compose 공통 계약과 draft validator
tool-android-intents      실제 Android Calendar/Email/SMS Intent
tool-datetime             현재 날짜와 시각
llm-litert-common         단일 Engine, native tool-call 공통 adapter
llm-litert                device flavor 전용 Android LiteRT-LM facade
desktop-agent-runner      APK와 분리된 Mac JVM 평가 도구
```
