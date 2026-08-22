# Ryeong 검색 통합 분석

## 기준

- 분석 브랜치: `HJP-limited/ryeong`의 `llm-integration-work`
- clone 경로: `/tmp/hjp-ryeong`
- commit: `0bfd236efa40987c8f0a620d256be9c099080274`
- commit 시각: `2026-07-24T14:06:58+09:00`
- 현재 프로젝트 분석 기준: 2026-07-25 작업 트리

통합 전 `search-core/src/main/java/com/hjp/searchlookup`는 위 commit의
`src/main/java/com/hjp/searchlookup`와 파일 내용이 동일했다. 그러나 Android
production adapter는 이전 식별자 `93ca58f`, `LocalEmbeddingEngine`, 메모리
snapshot만 사용했고 Ryeong Android 앱에서 실기기 검증한
EmbeddingGemma/RAG SDK 경로와 Room embedding 저장소는 연결하지 않았다.
따라서 알고리즘을 다시 복사하지 않고 adapter와 실패 경계를 교체하는 것이
올바른 통합 범위다.

## 대응 관계

| 역할 | 통합 전 현재 프로젝트 | Ryeong 기준 | 최종 결정 |
|---|---|---|---|
| tool contract | `ContactToolContracts`의 `search_contacts`, `get_contact` | 별도 demo UI/API | 현재 이름·입력 의미 유지 |
| DB 모델/ID | `BusinessCardEntity.id: String` Room PK | `BusinessCard.id: String` | Room ID를 그대로 검색 `card_id`로 사용 |
| repository | suspend `BusinessCardRepository`와 Room 구현 | 동기 `com.hjp.searchlookup.BusinessCardRepository` | 요청 시 Room snapshot adapter, 상세는 Room 재조회 |
| query 분석 | Ryeong `QueryAnalyzer` | 동일 | 단일 구현 유지 |
| keyword | `LikeFallbackKeywordRetriever` | 동일, FTS demo 구현도 별도 존재 | agent는 Ryeong LIKE keyword, card-tab API는 keyword-only로 분리 |
| semantic | `SemanticRetriever` + Local fake | EmbeddingGemma Android provider | query/document task를 분리한 실기기 provider 연결 |
| fusion | `ReciprocalRankFusion` | 동일 | raw score 합산 없이 RRF 유지 |
| context | `RagContextBuilder` | 동일 | 내부 provenance용, 전화·이메일·주소 제외 |
| tool output | 이름/회사/직함/지역/score/engine | retrieval metadata | 지역 제거, `match_summary`, mode, fallback 추가 |
| embedding store | service 수명 동안 메모리 | Room/사전 계산 vector 참고 구현 | `card_embeddings` Room table로 영속화 |
| workflow | `StructuredAgentKernel`, policy, validator | demo chat 흐름 | 현재 구조 전부 유지 |
| registry/executor | 현재 `DefaultToolRegistry`, `DefaultToolExecutor` | demo 직접 호출 | 현재 구조 유지 |
| staged schemas | Stage 1/Stage 2와 content generation | 없음 | 현재 구조 유지 |

## 가져온 구성요소

- `QueryAnalyzer`, `QueryAnalysis`
- `KeywordRetriever`, `LikeFallbackKeywordRetriever`
- `SemanticRetriever`
- `OnDeviceEmbeddingEngine`의 production/fallback 경계
- `CardEmbedding`, `EmbeddingUpdater`, vector codec와 cosine 검증
- `ReciprocalRankFusion`
- `RagContextBuilder`
- `RetrievalService`, `RetrievalResponse`, `SearchLookupService`
- Ryeong Android 앱의 `GemmaEmbeddingModel` 기반 query/document embedding 방식
- 모델과 tokenizer를 앱 내부 또는 external-files `models`에서 찾는 asset 정책
- model-backed retrieval이 불가능할 때 keyword-only로 계속 동작하는 정책

## 현재 프로젝트에서 유지한 구성요소

- `BusinessCardEntity`, `BusinessCardDao`, `RoomBusinessCardRepository`
- Room의 기존 문자열 PK와 seed/import 경로
- `search_contacts`, `get_contact`, `update_business_card`의 외부 tool 이름
- `ToolRegistry`, `ToolExecutor`, session provenance
- `StructuredAgentKernel`, staged intent, workflow orchestrator
- allowlist, argument validator, contact provenance, confirmation 정책
- `get_contact`에서만 전화번호·이메일 등 상세정보를 반환하는 경계
- 이메일·문자·일정·수정의 기존 Android 실행/확인 흐름
- Gemma 4 E2B 생성 모델과 LiteRT-LM gateway

EmbeddingGemma는 검색용 768차원 모델이고 Gemma 4 E2B는 intent/본문 생성
모델이다. 모델, session, runtime 역할을 합치지 않았다.

## 제거한 중복과 dead path

다음 클래스는 production에서 참조되지 않는 TODO 또는 과거 demo 호환
계층이라 제거했다.

- `RoomFtsKeywordRetriever`
- `SqliteFts5KeywordRetriever`
- `KeywordCandidateSource`, `InMemoryKeywordCandidateSource`
- `TextTokenizer`, `HuggingFaceTokenizer`, `LightweightTokenizer`
- `RerankerEngine`, `NoOpRerankerEngine`
- `SearchMode`, `SearchExample`

최종 Android 검색 경로는 `RyeongContactSearchBackend` 하나다. custom ONNX
tokenizer stub과 AI Edge RAG SDK tokenizer를 병렬로 두지 않았다.
`LocalEmbeddingEngine`은 오류 진단용 deterministic fallback vector
구현으로 남지만 `isModelBacked=false`이며 semantic ranking에는 사용되지
않는다. 모델 미준비 시 실제 검색은 `LikeFallbackKeywordRetriever`만 수행한다.

## Adapter

```text
Room BusinessCardEntity
  └─ RoomBusinessCardRepository: BusinessCardRecord
       └─ RyeongContactSearchBackend: Ryeong BusinessCard snapshot
            ├─ QueryAnalyzer
            ├─ LikeFallbackKeywordRetriever
            ├─ SemanticRetriever
            ├─ ReciprocalRankFusion
            └─ RagContextBuilder / RetrievalResponse
                 └─ SearchContactsOutput (PII 최소화)

Room card_embeddings
  ⇄ StoredCardEmbedding
  ⇄ Ryeong CardEmbedding
```

`search_contacts`가 반환한 ID는 변환하거나 새로 생성하지 않는다.
`get_contact(card_id)`는 먼저 현재 검색 snapshot에 존재하는 ID인지 확인한 뒤
같은 ID로 Room을 재조회한다. 따라서 삭제되었거나 모델이 만든 임의 ID로
상세정보를 읽을 수 없다.

검색 tool 출력:

```json
{
  "results": [
    {
      "card_id": "C001",
      "name": "김지원",
      "company": "비전글로벌",
      "title": "대표이사",
      "match_summary": "이름·회사 일치",
      "score": 1.0
    }
  ],
  "count": 1,
  "mode": "KEYWORD_ONLY",
  "fallback_used": true,
  "engine": "google/embeddinggemma-300m (keyword-fallback:LocalEmbeddingEngine)"
}
```

keyword/semantic 순위, RRF source, query analysis, fallback reason, 초기화/검색
시간은 내부 객체와 Logcat `HjpContactSearch`에 남기고 LLM payload에는 넣지
않는다.

## Embedding production/fallback

Ryeong branch의 공용 `OnDeviceEmbeddingEngine` ONNX 경로는 실제 inference
대신 `UnsupportedOperationException`을 던지는 stub이었다. 반면 Ryeong
Android 앱은 AI Edge RAG SDK 0.3.0의 `GemmaEmbeddingModel`과 다음 파일로
실기기 검증되어 있다.

```text
<app files 또는 external-files>/models/embeddinggemma-300m.tflite
<app files 또는 external-files>/models/sentencepiece.model
```

현재 앱은 이 검증된 방식을 `AndroidEmbeddingGemmaEngine`으로 연결한다.
두 파일 존재/읽기 가능/크기, 초기화, 768차원, finite/non-zero vector를
검증한다. query에는 `RETRIEVAL_QUERY`, card에는 `RETRIEVAL_DOCUMENT`를
사용한다. 생성된 card vector는 Room `card_embeddings`에 model name,
dimension, source hash와 함께 저장한다.

실패 시:

1. 예외를 앱 밖으로 전파하지 않는다.
2. 실패한 fallback vector를 semantic 결과로 사용하거나 기존 768차원
   vector와 섞지 않는다.
3. 해당 요청을 `KEYWORD_ONLY`로 다시 수행한다.
4. `fallback_used=true`와 reason을 diagnostics에 기록한다.
5. 검색 결과는 기존 workflow와 동일하게 0/1/다수 검증을 받는다.

모델과 tokenizer는 저장소에 추가하거나 다운로드하지 않았다. 현재 asset이
없으므로 실제 앱의 기본 실행은 안전한 keyword fallback이다.

## Gradle·asset·데이터 충돌

- AI Edge RAG SDK: `com.google.ai.edge.localagents:localagents-rag:0.3.0`
- 누락된 전이 dependency: `protobuf-javalite:4.35.1`
- 기존 생성 runtime: `litertlm-android:0.13.1` 유지
- `tflite`, `onnx`, `task`, `model`, `litertlm`은 `noCompress`
- debug packaging에서 LiteRT-LM과 RAG SDK의 duplicate class/native library
  충돌은 발생하지 않았다.
- app asset에는 911B seed JSON만 있고 embedding model/tokenizer는 없다.
- Ryeong의 15MB precomputed vector, 1.4MB 5,000-card seed, 대형 모델 파일은
  복사하지 않았다. 현재 Room fixture와 ID가 다르므로 그대로 복사하면
  provenance가 깨진다.
- DB version은 1→2이며 기존 `business_cards`를 보존하고
  `card_embeddings`만 생성하는 명시적 migration을 사용한다.

## 위험과 rollback 지점

| 위험 | 통제 | rollback 지점 |
|---|---|---|
| RAG SDK가 특정 ABI에서 로드 실패 | asset이 있을 때만 모델 생성, 모든 오류 keyword fallback | AppContainer embedding factory |
| 모델/tokenizer 불일치 | 두 파일 검사, 초기화 오류 기록 | `AndroidEmbeddingGemmaEngine` |
| vector dimension/model drift | model name+dimension+source hash 검증 | `card_embeddings` table/adapter |
| 첫 indexing 지연 | Room vector 재사용, background dispatcher | `RyeongContactSearchBackend.requireService` |
| 중복 이름 오선택 | 모든 결과 유지, orchestrator가 사용자 선택 요구 | 기존 workflow policy |
| PII 노출 | 검색 DTO와 RAG에서 phone/email/address 제외 | `SearchOutputCodec`, `RagContextBuilder` |
| runtime dependency 충돌 | 전체 APK packaging 검증 | app의 RAG SDK dependency 두 줄 |

rollback은 검색 tool/schema, Room DB, agent workflow를 되돌리는 방식이 아니라
AppContainer의 embedding factory만 keyword-only engine으로 교체할 수 있다.
그 경우에도 production 검색 backend와 ID/provenance 경로는 하나로 유지된다.
