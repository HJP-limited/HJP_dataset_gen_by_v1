package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class SemanticRetriever {
    public static final double MIN_SEMANTIC_SIMILARITY = 0.20;

    private final BusinessCardRepository repository;
    private final EmbeddingEngine embeddingEngine;

    public SemanticRetriever(
            BusinessCardRepository repository, EmbeddingEngine embeddingEngine) {
        this.repository = repository;
        this.embeddingEngine = embeddingEngine;
    }

    public List<SearchResult> retrieve(QueryAnalysis analysis, int topK) {
        String query = analysis == null ? "" : analysis.semanticQuery;
        if (query.isEmpty()) return Collections.emptyList();
        float[] queryVector = embeddingEngine.embed(query);
        CosineSimilarity.normalizeInPlace(queryVector);
        List<SearchResult> results = new ArrayList<>();
        for (CardEmbedding embedding : repository.getEmbeddings(embeddingEngine.name())) {
            BusinessCard card = repository.getCard(embedding.cardId);
            Float similarity = CosineSimilarity.cosine(queryVector, embedding.vector());
            if (card == null || similarity == null || similarity < MIN_SEMANTIC_SIMILARITY) continue;
            results.add(new SearchResult(
                    card, similarity,
                    new ScoreBreakdown(0, similarity, 0, 0, similarity),
                    Arrays.asList("semantic"), Collections.emptyList(), 0, similarity, 0));
        }
        results.sort(Comparator.comparingDouble((SearchResult result) -> result.similarity)
                .reversed().thenComparing(result -> result.card.id));
        List<SearchResult> ranked = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            ranked.add(results.get(index).withRank(index + 1));
        }
        if (topK > 0 && ranked.size() > topK) {
            ranked = new ArrayList<>(ranked.subList(0, topK));
        }
        return Collections.unmodifiableList(ranked);
    }
}
