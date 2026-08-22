# Ryeong 최신 검색 업데이트 분석

## 비교 기준

- 이전 통합 기준: `0bfd236efa40987c8f0a620d256be9c099080274`
- 최신 `llm-integration-work`: `b543a189249df7d87523564b844cb472b15fcbf3`
- clone: `/tmp/hjp-ryeong-latest`
- 최신 커밋: `FTS4 티어드 키워드 검색 적용 + 멀티턴 버그 수정 + 동의어 확장 + 테스트 데이터셋`

두 커밋 사이 변경은 Ryeong의 `MainActivity`, FTS entity/DB, `CardSearchService`,
`KeywordSearchRanker`, 50명 test dataset 및 Python 평가/로컬 bridge에 한정된다.

## 대응 관계와 적용 판단

| 최신 Ryeong 변경 | 현재 프로젝트 대응 경로 | 판단 |
|---|---|---|
| FTS4 `unicode61`, prefix `{2,3,4}` | `app` Room `BusinessCardDao`/`HjpDatabase` | production DAO와 DB v3 migration으로 적용 |
| phrase → all terms → prefix → LIKE | `RoomBusinessCardRepository` → `RyeongContactSearchBackend` | Room 기반 단일 production keyword 경로로 적용 |
| LIKE 전용 동의어 확장 | 위 Room keyword 경로 | 최신 목록과 순서 유지 |
| honorific/조사 및 전화번호 정규화 | `search-core/QueryAnalyzer` | 공용 query analysis로 적용 |
| keyword + semantic RRF | `search-core/SearchLookupService` | 기존 RRF를 유지하고 Room FTS 순위를 입력으로 받도록 연결 |
| focus/new-name/digit 후속 질의 수정 | `search-core/AgentSessionState`, structured session | demo UI 없이 공용 session resolver와 회귀 테스트만 통합 |
| 50명 JSON과 evaluator | test resources/evaluator | production seed와 분리된 test fixture로만 사용 |
| UI import/chat 화면 | 현재 Compose UI 및 AgentKernel | 불필요, 가져오지 않음 |
| DB v2 + destructive fallback | 현재 DB v2 | 충돌. 데이터 보존 v2→v3 명시 migration으로 대체 |

## 반드시 가져올 production 검색 기능

1. 안전한 FTS term builder: 문자·숫자 외 문자를 제거하고 `AND`, `OR`, `NOT`,
   `NEAR`를 사용자 term으로 허용하지 않는다.
2. exact phrase, 전체 term AND, 2자 이상 prefix의 티어 순서를 유지한다.
3. 각 티어 결과를 `LinkedHashSet`으로 누적하여 먼저 회수된 순위를 보존한다.
4. 동의어는 가장 느슨한 LIKE fallback에서만 확장한다.
5. query/document semantic retrieval 결과와 keyword 순위를 score 직접 합산 없이 RRF로 결합한다.
6. 이름·영문명·회사·직책·부서·산업·지역·메모·태그 및 정규화 전화번호를 검색한다.
7. 결과는 현재 Room 문자열 ID를 그대로 사용하고 상세 조회 때 현재 DB 존재 여부를 다시 확인한다.

## 멀티턴·검색 품질 개선

- `씨`, `님`과 한국어 조사를 검색 term에서 제거한다.
- 질문에 명시된 이름과 직전 결과가 일치할 때 그 ID를 focus로 삼는다.
- 대명사/생략형일 때만 직전 focus를 재사용한다.
- 숫자·전화번호가 새로 등장하면 이전 focus에 강제로 결합하지 않는다.
- 새로 명시된 다른 이름은 이전 focus보다 우선한다.
- 동명이인 목록의 `첫 번째/두 번째` 선택은 직전 검색 순서와 ID provenance로 해석한다.
- 검색 후보가 요청과 무관하면 RAG context에 포함하지 않는다. 전화번호·이메일·주소는 RAG에
  넣지 않고 `get_contact(card_id)`에서만 제공한다.

## 모델·tokenizer·vector

- 생성 모델 Gemma 4 E2B와 검색 모델 EmbeddingGemma 300M은 별도 역할로 유지한다.
- 제공된 generic seq256 mixed-precision TFLite를 canonical asset으로 설치하고 최초 실행 때
  내부 `filesDir/models`로 복사할 수 있게 한다.
- AI Edge RAG SDK가 요구하는 `sentencepiece.model`은 동일
  `litert-community/embeddinggemma-300m` artifact만 허용한다. 다른 tokenizer나 임의 생성 파일은
  사용하지 않는다.
- embedding cache 유효 조건은 card ID, embedding model identity/source hash, dimension 768,
  document input hash가 모두 일치하는 경우뿐이다. 불일치 cache는 검색에 사용하지 않고 다시 계산한다.
- 최신 Ryeong의 precomputed vector는 현재 Room ID 및 입력 hash를 증명할 수 없으므로 production에
  가져오지 않는다.

## DB/FTS 변경과 충돌

- 현재 DB는 v2이며 `business_cards`, `card_embeddings`를 보존해야 한다.
- v3 migration은 `business_cards_fts`를 만들고 기존 카드를 재색인한다. destructive migration은
  추가하지 않는다.
- insert/update/delete trigger 또는 repository transaction으로 FTS를 동기화한다.
- 명함 수정은 해당 FTS row를 즉시 교체하고 오래된 embedding은 source-text hash 검증으로 무효화한다.
- Ryeong Java demo의 정수 FTS row ID와 현재 문자열 card ID 사이에는 DAO adapter가 필요하다.

## 현재 프로젝트에서 유지할 구성요소

- Room `BusinessCardEntity`, 문자열 ID, `card_embeddings`, repository/update 확인 흐름
- `search_contacts`, `get_contact` tool contract와 최소 개인정보 출력 codec
- `StructuredAgentKernel`, staged intent, workflow orchestrator, validator, provenance 검증
- `ToolRegistry`, `ToolExecutor`, Android intent 실행 구조
- `search-core`의 QueryAnalyzer/SemanticRetriever/ReciprocalRankFusion/RagContextBuilder
- AI Edge RAG SDK 기반 `AndroidEmbeddingGemmaEngine`과 안전한 keyword fallback

## 제거하거나 만들지 않을 중복

- Ryeong demo `CardSearchService`, 독립 Room repository/DTO/chat UI는 복사하지 않는다.
- 제거했던 `RoomFtsKeywordRetriever` stub을 되살리지 않는다. 실제 Room DAO adapter만 production
  keyword provider가 된다.
- cosine/vector codec/tokenizer/embedding engine/RAG builder를 두 벌로 만들지 않는다.
- 50명 데이터는 앱 seed로 넣지 않고 test fixture에서만 로드한다.
- 대형 모델, tokenizer, precomputed vectors는 Git tracked source로 만들지 않는다.

## 테스트·평가 도구

- 최신 Ryeong 50명 fixture를 현재 repository/test DTO로 변환하는 evaluator를 둔다.
- exact name, phone, field, semantic, RRF, fallback, stale ID, 개인정보 최소화와 DB migration을 검사한다.
- 멀티턴은 최소 20개의 2~3턴 시나리오로 focus/새 이름/숫자/동명이인 provenance를 검증한다.
- 이전 staged 64 기대값은 변경하지 않고 기존 기준과 동일하게 평가한다.

## 위험과 rollback 지점

1. Room이 생성한 FTS4 schema와 수기 migration SQL이 다르면 앱 시작이 실패한다. KSP schema 검증과
   migration 테스트를 통과하기 전 배포하지 않는다.
2. RAG SDK는 arm64 중심이다. x86_64 AVD의 keyword fallback을 semantic 성공으로 기록하지 않는다.
3. tokenizer가 없거나 TFLite가 호환되지 않으면 초기화 실패를 진단 로그에 남기고 `KEYWORD_ONLY`로
   유지한다.
4. 171 MiB asset은 APK 크기를 크게 늘린다. 모델 파일 제거만으로 코드 경로가 keyword fallback으로
   복구되며 DB/명함 데이터는 영향을 받지 않는다.
5. search adapter 교체 rollback은 DB v3/FTS table을 유지한 채 Room keyword provider 주입만 이전
   in-memory retriever로 되돌릴 수 있다. 데이터 삭제는 rollback 수단으로 사용하지 않는다.
