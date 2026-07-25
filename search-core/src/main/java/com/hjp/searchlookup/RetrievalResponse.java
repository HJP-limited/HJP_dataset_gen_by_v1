package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RetrievalResponse {
    public final String query;
    public final String ragContext;
    public final String retrievalMode;
    public final String embeddingModelName;
    public final String engineName;
    public final String rerankerName;
    public final List<SearchResult> results;
    public final List<String> cardIds;
    public final int keywordResultCount;
    public final int semanticResultCount;
    public final boolean fallbackUsed;
    public final boolean safetyGateApplied;
    public final QueryAnalysis queryAnalysis;
    public final RetrievalMode mode;

    public RetrievalResponse(
            String query, List<SearchResult> results, String ragContext, RetrievalMode mode,
            String embeddingModelName, int keywordResultCount, int semanticResultCount,
            boolean fallbackUsed, boolean safetyGateApplied, QueryAnalysis queryAnalysis) {
        this.query = query == null ? "" : query;
        this.results = Collections.unmodifiableList(new ArrayList<>(
                results == null ? Collections.emptyList() : results));
        List<String> ids = new ArrayList<>();
        for (SearchResult result : this.results) ids.add(result.cardId);
        this.cardIds = Collections.unmodifiableList(ids);
        this.ragContext = ragContext == null ? "" : ragContext;
        this.mode = mode == null ? RetrievalMode.HYBRID : mode;
        this.retrievalMode = this.mode.name();
        this.embeddingModelName = embeddingModelName;
        this.engineName = embeddingModelName;
        this.rerankerName = "reciprocal-rank-fusion-k60";
        this.keywordResultCount = keywordResultCount;
        this.semanticResultCount = semanticResultCount;
        this.fallbackUsed = fallbackUsed;
        this.safetyGateApplied = safetyGateApplied;
        this.queryAnalysis = queryAnalysis;
    }
}
