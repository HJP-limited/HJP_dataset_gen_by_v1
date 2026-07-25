# Search architecture

## Two paths, one implementation set

명함 탭의 짧은 검색은 embedding과 RRF를 생략한다.

```text
query → QueryAnalyzer → LikeFallbackKeywordRetriever → SearchResult
```

에이전트 `search_contacts`는 Ryeong hybrid path를 사용한다.

```text
query
→ QueryAnalyzer
├→ KeywordRetriever
└→ SemanticRetriever(LocalEmbeddingEngine)
→ ReciprocalRankFusion(k=60)
→ lexical/identity safety gate
→ RagContextBuilder
→ RetrievalResponse
```

두 경로가 공유하는 `QueryAnalyzer`, `KeywordRetriever`, domain model은 각각
하나다. old scorer나 fallback search service는 production source에 없다.

## Room and tools

```text
Room BusinessCardEntity
→ BusinessCardStore (latest detail)
→ RyeongContactSearchBackend
→ canonical in-memory Ryeong index
→ SearchContactsPlugin
→ DefaultToolRegistry
→ AgentKernel
```

`search_contacts(query, limit)`은 개인정보가 없는 결과를 반환한다.
`get_contact(card_id, purpose)`는 동일 card ID로 Room 최신 record를 조회한다.
Compose name resolution은 항상 `search_contacts → 단일 결과 확인 → get_contact`
순서이며 card ID나 이름을 email/phone으로 실행하지 않는다.

## Debug evidence

Desktop `--debug`는 다음 stage를 출력한다.

```text
[SEARCH_BACKEND] ryeong
[QUERY_ANALYSIS]
[KEYWORD_RESULTS]
[SEMANTIC_RESULTS]
[RRF_RESULTS]
[RAG_CONTEXT]
```

각 로그는 전체 DB, vector, 전화번호, 이메일을 출력하지 않는다.
