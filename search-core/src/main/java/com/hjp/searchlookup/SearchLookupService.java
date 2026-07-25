package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Canonical Ryeong retrieval pipeline.
 *
 * <p>QueryAnalyzer → KeywordRetriever → SemanticRetriever → RRF(k=60) → safety gate
 * → RagContextBuilder → RetrievalResponse.
 */
public final class SearchLookupService implements RetrievalService {
    private static final int RAG_CARD_LIMIT = 5;

    private final BusinessCardRepository repository;
    private final EmbeddingEngine embeddingEngine;
    private final QueryAnalyzer queryAnalyzer = new QueryAnalyzer();
    private final KeywordRetriever keywordRetriever;
    private final SemanticRetriever semanticRetriever;
    private final ReciprocalRankFusion rankFusion = new ReciprocalRankFusion();
    private final RagContextBuilder ragContextBuilder = new RagContextBuilder();

    public SearchLookupService(List<BusinessCard> cards, EmbeddingEngine embeddingEngine) {
        this(new InMemoryBusinessCardRepository(cards), embeddingEngine);
    }

    public SearchLookupService(
            BusinessCardRepository repository, EmbeddingEngine embeddingEngine) {
        this.repository = repository;
        this.embeddingEngine = embeddingEngine == null
                ? new LocalEmbeddingEngine() : embeddingEngine;
        this.keywordRetriever = new LikeFallbackKeywordRetriever(repository);
        this.semanticRetriever = new SemanticRetriever(repository, this.embeddingEngine);
        EmbeddingUpdater updater = new EmbeddingUpdater(repository, this.embeddingEngine);
        for (BusinessCard card : repository.getAllCards()) updater.refreshIfNeeded(card);
    }

    /** Ryeong's separate lightweight card-tab path: analyzer + keyword only. */
    public List<SearchResult> search(String rawQuery, int limit) {
        return searchCardTab(rawQuery, SortOption.RELEVANCE, limit);
    }

    public List<SearchResult> searchCardTab(
            String rawQuery, SortOption sortOption, int limit) {
        return keywordRetriever.retrieve(queryAnalyzer.analyze(rawQuery), Math.max(1, limit));
    }

    @Override public RetrievalResponse retrieve(String rawQuery, int topK) {
        return retrieve(rawQuery, topK, RetrievalMode.HYBRID);
    }

    public RetrievalResponse retrieve(String rawQuery, int topK, RetrievalMode requestedMode) {
        int safeLimit = Math.max(1, topK);
        RetrievalMode mode = requestedMode == null ? RetrievalMode.HYBRID : requestedMode;
        QueryAnalysis analysis = queryAnalyzer.analyze(rawQuery);
        if (analysis.tokens.isEmpty()) return response(
                analysis, Collections.emptyList(), mode, 0, 0, false);

        List<SearchResult> keyword = mode == RetrievalMode.SEMANTIC_ONLY
                ? Collections.emptyList()
                : keywordRetriever.retrieve(analysis, Integer.MAX_VALUE);
        List<SearchResult> semantic = mode == RetrievalMode.KEYWORD_ONLY
                ? Collections.emptyList()
                : semanticRetriever.retrieve(analysis, Integer.MAX_VALUE);

        boolean gateApplied = false;
        List<SearchResult> results;
        if (mode == RetrievalMode.KEYWORD_ONLY) {
            results = limit(keyword, safeLimit);
        } else if (mode == RetrievalMode.SEMANTIC_ONLY) {
            if (analysis.strictIdentityQuery) {
                results = Collections.emptyList();
                gateApplied = true;
            } else {
                results = limit(semantic, safeLimit);
            }
        } else if (analysis.strictIdentityQuery) {
            gateApplied = true;
            if (keyword.isEmpty()) {
                results = Collections.emptyList();
            } else {
                Set<String> lexicalIds = new HashSet<>();
                for (SearchResult item : keyword) lexicalIds.add(item.cardId);
                results = limit(prioritizeLexical(onlyIds(
                        rankFusion.fuse(keyword, semantic, Integer.MAX_VALUE), lexicalIds)),
                        safeLimit);
            }
        } else {
            results = limit(prioritizeLexical(
                    rankFusion.fuse(keyword, semantic, Integer.MAX_VALUE)), safeLimit);
        }
        return response(analysis, results, mode, keyword.size(), semantic.size(), gateApplied);
    }

    @Override public BusinessCard getCard(String cardId) {
        return repository.getCard(cardId == null ? null : cardId.trim());
    }

    public String engineName() {
        return "ryeong-hybrid/" + embeddingEngine.name();
    }

    public QueryAnalysis analyzeQuery(String rawQuery) {
        return queryAnalyzer.analyze(rawQuery);
    }

    public List<SearchResult> retrieveKeywordCandidates(String query, int topK) {
        return keywordRetriever.retrieve(queryAnalyzer.analyze(query), topK);
    }

    public List<SearchResult> retrieveSemanticCandidates(String query, int topK) {
        return semanticRetriever.retrieve(queryAnalyzer.analyze(query), topK);
    }

    private RetrievalResponse response(
            QueryAnalysis analysis, List<SearchResult> results, RetrievalMode mode,
            int keywordCount, int semanticCount, boolean gateApplied) {
        String rag = mode == RetrievalMode.KEYWORD_ONLY
                ? ""
                : ragContextBuilder.build(
                        analysis.normalizedQuery, results, Math.min(RAG_CARD_LIMIT, results.size()));
        return new RetrievalResponse(
                analysis.normalizedQuery, results, rag, mode, embeddingEngine.name(),
                keywordCount, semanticCount, false, gateApplied, analysis);
    }

    private List<SearchResult> onlyIds(List<SearchResult> results, Set<String> ids) {
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult result : results) if (ids.contains(result.cardId)) filtered.add(result);
        return filtered;
    }

    /**
     * Safety tie-break after Ryeong RRF: any lexical evidence precedes semantic-only candidates.
     * Relative RRF order inside each partition is preserved.
     */
    private List<SearchResult> prioritizeLexical(List<SearchResult> results) {
        List<SearchResult> lexical = new ArrayList<>();
        List<SearchResult> semanticOnly = new ArrayList<>();
        for (SearchResult result : results) {
            (result.breakdown.keywordScore > 0.0 ? lexical : semanticOnly).add(result);
        }
        lexical.addAll(semanticOnly);
        return lexical;
    }

    private List<SearchResult> limit(List<SearchResult> results, int limit) {
        if (results == null || limit <= 0) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(
                results.subList(0, Math.min(limit, results.size()))));
    }
}
