# 운영 아키텍처

## 공통 실행 경로

`AppContainer`가 Room repository, contact plugins, datetime plugin, Android Intent
plugins를 `DefaultToolRegistry`에 한 번 등록한다. 모든 model-originated call과
deterministic call은 동일한 `AgentKernel`의 schema validation, policy,
execution, observation 경로를 통과한다.

## Canonical contact retrieval

```text
search_contacts
→ RyeongContactSearchBackend (ToolContract adapter)
→ SearchLookupService / RetrievalService
→ QueryAnalyzer
→ LikeFallbackKeywordRetriever
→ SemanticRetriever(LocalEmbeddingEngine)
→ ReciprocalRankFusion(k=60)
→ identity/lexical safety gate
→ RagContextBuilder
→ privacy-minimized RetrievalResponse
```

검색 결과는 card ID, 이름, 회사, 직책, 부서, 업종/태그, 지역과 score
breakdown만 포함한다. 전화번호·이메일·상세 주소는 `get_contact(card_id,
purpose)`가 Room 원본을 다시 조회할 때만 반환한다. Store revision이 바뀌면
검색 snapshot과 local embedding을 재생성하므로 생성·수정·삭제 뒤 stale result를
유지하지 않는다.

`RoomFtsKeywordRetriever`와 `SqliteFts5KeywordRetriever`는 Ryeong 원본에서도
placeholder였으므로 가져오지 않았다. 실제 동작하는 LIKE fallback을 production
keyword binding으로 사용한다. 별도 EmbeddingGemma/TFLite/ONNX 및 neural runtime도
추가하지 않았으며, semantic backend는 192차원 경량 `LocalEmbeddingEngine` 하나다.

```text
RoutingFirstAndroidAgentModelGateway
├── LocalToolRoutingModelGateway
└── device: LiteRtAgentModelGateway(Gemma 4)
```

정형 요청은 local router가 먼저 처리하므로 모델을 lazy load하지 않는다. primary
session 생성이나 추론이 실패해도 router session은 유지된다. Gemma 3 또는
FunctionGemma fallback Engine은 Android 운영 graph에 없다.

## Engine 소유권

device source set의 `VariantModelGatewayFactory`만 LiteRT facade를 생성한다.
그 인스턴스를 primary 판단과 compose draft generator 양쪽에 주입한다.
`SingleEngineResource`는 동시 첫 접근에서도 factory를 한 번만 호출하며 close 이후
재생성을 금지한다. `InferenceQueue`는 서로 다른 conversation의 native 호출도
하나씩 실행한다.

Android facade는 LiteRT-LM 0.14.0이 공식 지원하는 `EngineConfig.maxNumTokens`를
2,048로 제한한다. 이 버전의 `ConversationConfig`/`SamplerConfig`에는 별도
출력-token 상한 API가 없으므로 지원하지 않는 옵션을 추측해 추가하지 않았다.

```text
process
└── HjpApplication.container (synchronized lazy)
    └── LiteRtAgentModelGateway 1개
        └── LiteRtNativeAgentModelGateway
            ├── Engine 1개 (lazy)
            ├── agent Conversation
            └── draft Conversation
```

세션 reset/애플리케이션 종료 시 conversation과 Engine을 닫는다. Android 운영
trace sink는 backend 오류 종류만 기록하며 prompt, 전체 명함 DB, raw model output,
수신자 개인정보를 logcat에 기록하지 않는다.

## variant 격리

- `deviceImplementation(project(":llm-litert"))`: device variant만 native runtime
  의존성을 가진다.
- emulator source set은 `LocalToolRoutingModelGateway`만 생성하며 LiteRT type을
  import하지 않는다.
- generated model asset은 `deviceStandalone` source set에만 연결된다.
- Desktop runner와 model-eval source set은 Android app dependency가 아니다.
- 앱의 main asset은 작은 가상 명함 seed `cards/business_cards.json`뿐이다.

## 외부 동작

Calendar와 Email/SMS plugin은 Android `Intent` backend를 유지한다. Compose는
직접 주소를 쓰거나 `search_contacts → get_contact` 결과의 실제 email/phone만
사용한다. Gemma 4는 제목·본문 JSON 초안만 생성하며 validator/fallback 뒤
application orchestrator가 `open_compose`를 만든다.
