package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ryeong RRF. Candidate scores are never added directly; only 1 / (k + rank) is fused. */
public final class ReciprocalRankFusion {
    public static final int DEFAULT_RRF_K = 60;
    private final int k;

    public ReciprocalRankFusion() {
        this(DEFAULT_RRF_K);
    }

    public ReciprocalRankFusion(int k) {
        this.k = k <= 0 ? DEFAULT_RRF_K : k;
    }

    public List<SearchResult> fuse(
            List<SearchResult> keywordResults, List<SearchResult> semanticResults, int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, BusinessCard> cards = new HashMap<>();
        Map<String, Double> similarities = new HashMap<>();
        Map<String, Double> keywordScores = new HashMap<>();
        Map<String, Integer> keywordRanks = new HashMap<>();
        Map<String, Set<String>> sources = new HashMap<>();
        Map<String, Set<String>> matchedFields = new HashMap<>();
        add(scores, cards, similarities, keywordScores, keywordRanks, sources, matchedFields,
                keywordResults, "keyword");
        add(scores, cards, similarities, keywordScores, keywordRanks, sources, matchedFields,
                semanticResults, "semantic");

        List<SearchResult> results = new ArrayList<>();
        for (String id : scores.keySet()) {
            double score = scores.get(id);
            double similarity = similarities.getOrDefault(id, 0.0);
            results.add(new SearchResult(
                    cards.get(id), score,
                    new ScoreBreakdown(
                            keywordScores.getOrDefault(id, 0.0), similarity, 0, 0, score),
                    new ArrayList<>(sources.get(id)),
                    new ArrayList<>(matchedFields.getOrDefault(id, Collections.emptySet())),
                    0, similarity, score));
        }
        results.sort(Comparator
                .comparingDouble((SearchResult result) -> result.rankFusionScore).reversed()
                .thenComparingInt(result -> keywordRanks.getOrDefault(
                        result.cardId, Integer.MAX_VALUE))
                .thenComparing(result -> result.cardId));
        List<SearchResult> ranked = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            ranked.add(results.get(index).withRank(index + 1));
        }
        if (topK > 0 && ranked.size() > topK) {
            ranked = new ArrayList<>(ranked.subList(0, topK));
        }
        return Collections.unmodifiableList(ranked);
    }

    private void add(
            Map<String, Double> scores,
            Map<String, BusinessCard> cards,
            Map<String, Double> similarities,
            Map<String, Double> keywordScores,
            Map<String, Integer> keywordRanks,
            Map<String, Set<String>> sources,
            Map<String, Set<String>> matchedFields,
            List<SearchResult> list,
            String source) {
        if (list == null) return;
        for (int index = 0; index < list.size(); index++) {
            SearchResult result = list.get(index);
            int rank = result.rank > 0 ? result.rank : index + 1;
            scores.put(result.cardId,
                    scores.getOrDefault(result.cardId, 0.0) + 1.0 / (k + rank));
            cards.put(result.cardId, result.card);
            similarities.put(result.cardId, Math.max(
                    similarities.getOrDefault(result.cardId, 0.0), result.similarity));
            sources.computeIfAbsent(result.cardId, ignored -> new LinkedHashSet<>()).add(source);
            matchedFields.computeIfAbsent(result.cardId, ignored -> new LinkedHashSet<>())
                    .addAll(result.matchedFields);
            if ("keyword".equals(source)) {
                keywordScores.put(result.cardId, result.score);
                keywordRanks.put(result.cardId, rank);
            }
        }
    }
}
