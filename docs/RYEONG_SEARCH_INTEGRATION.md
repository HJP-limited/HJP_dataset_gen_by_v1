# Ryeong 명함 검색 통합

## Provenance

- source repository: `https://github.com/HJP-limited/ryeong.git`
- source branch: `llm-integration-work`
- source commit: `0bfd236efa40987c8f0a620d256be9c099080274`
- integration base: `Agent_Gemma_4_E2B_0711@43512ced369961139e1416f71a7abeec1b5cbb4c`

Ryeong clone은 별도 증거 디렉터리에 clean 상태로 보존했고 직접 수정하지 않았다.
Ryeong 저장소와 이 저장소 모두 조사 시 root `LICENSE`/`NOTICE`가 없었다. 외부
재배포 전 저장소 소유자가 라이선스를 확인해야 한다.

## 가져온 canonical 구성요소

`BusinessCard`, `BusinessCardRepository`, `CardEmbedding`, `EmbeddingUpdater`,
`EmbeddingEngine`, `LocalEmbeddingEngine`, `CosineSimilarity`,
`FloatVectorCodec`, `QueryAnalysis`, `QueryAnalyzer`, `KeywordRetriever`,
`LikeFallbackKeywordRetriever`, `SemanticRetriever`, `ReciprocalRankFusion`,
`RagContextBuilder`, `RetrievalMode`, `RetrievalService`, `RetrievalResponse`,
`SearchResult`, `ScoreBreakdown`, `SortOption`, `SearchLookupService`를 Ryeong
구조와 알고리즘 기준으로 `search-core`에 통합했다.

Ryeong 원본의 `RoomFtsKeywordRetriever`, `SqliteFts5KeywordRetriever`,
`OnDeviceEmbeddingEngine`은 placeholder였다. 빈 결과를 반환하거나 실제 모델
추론이 구현되지 않았으므로 production에 복사하지 않았다. Ryeong 전체 Android
앱, `MainActivity`, 별도 DB, EmbeddingGemma 관리 UI도 가져오지 않았다.

## 수정·삭제한 legacy 구현

- 0711 `SearchLookupService`의 stateful filter, field score와 cosine 직접 가산,
  synonym score 경로를 완전히 교체했다.
- 0711의 card vector map과 내부 cosine ranking을 `CardEmbedding`,
  `EmbeddingUpdater`, `SemanticRetriever`로 교체했다.
- 중복 이름이던 tool-side `BusinessCardRepository` /
  `MutableBusinessCardRepository`는 원본 상세 저장 역할을 분명히 하도록
  `BusinessCardStore` / `MutableBusinessCardStore`로 바꿨다.
- 검색 결과를 상세 `BusinessCardRecord`로 역변환하던 mapper를 삭제했다.
  검색은 개인정보가 없는 projection만 만들고 상세 조회는 Room store를 직접
  다시 조회한다.

## Room adapter와 index 갱신

현재 앱의 `BusinessCardEntity`, `BusinessCardDao`, `HjpDatabase`, asset seed를
그대로 사용한다. `RoomBusinessCardRepository`는 유일한
Room→`BusinessCardRecord` mapper를 유지하며, `RyeongContactSearchBackend`의
유일한 `BusinessCardRecord`→Ryeong `BusinessCard` mapper가 검색 snapshot을
만든다.

Store revision은 seed/create/update/delete마다 증가한다. backend는 매 검색 전에
revision을 비교하며, 값이 바뀌면 mutex 아래 snapshot과 local embedding을 한 번만
재생성한다. 앱 재시작 시 backend가 새 snapshot을 구성한다. 현재 DB schema version은
1이며 destructive fallback을 사용하지 않는다.

## 안전 보강

- 정확 이름과 부분 이름 lexical evidence 우선
- 이메일/전화 query matching은 허용하지만 search output과 RAG에는 값 미노출
- 모르는 proper name은 semantic-only hit 금지
- Korean bigram은 candidate 보조에만 사용하고 단독 evidence로 인정하지 않음
- 동명이인 결과를 모두 유지하고 application/router가 확인 요청
- search 결과가 0개면 semantic hash collision을 결과로 승격하지 않음
- RRF는 Ryeong 상수 `k=60`, `1/(k+rank)`만 사용

## Embedding backend

별도 모델은 포함하지 않는다. `LocalEmbeddingEngine`은 192차원 hash word/2·3gram/
concept vector이며 모델 asset과 neural runtime이 없다. 합성 5,000건 평가에서
recall@5 1.0, MRR 1.0, p95 36.33ms를 기록해 APK/RAM 비용이 큰 별도 embedding
모델을 정당화할 근거가 없었다.
