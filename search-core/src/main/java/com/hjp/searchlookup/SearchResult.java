package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SearchResult {
    public final BusinessCard card;
    public final double score;
    public final String cardId;
    public final String name;
    public final String company;
    public final String title;
    public final int rank;
    public final List<String> retrievalSources;
    public final double similarity;
    public final double rankFusionScore;
    public final ScoreBreakdown breakdown;
    public final List<String> matchedFields;

    public SearchResult(BusinessCard card, double score) {
        this(card, score, new ScoreBreakdown(score, 0, 0, 0, score),
                Collections.emptyList(), Collections.emptyList(), 0, 0.0, score);
    }

    public SearchResult(
            BusinessCard card, double score, ScoreBreakdown breakdown, List<String> sources,
            List<String> matchedFields, int rank, double similarity, double rankFusionScore) {
        this.card = card;
        this.score = score;
        this.cardId = card == null ? "" : card.id;
        this.name = card == null ? "" : card.name;
        this.company = card == null ? "" : card.company;
        this.title = card == null ? "" : card.title;
        this.rank = rank;
        this.retrievalSources = immutable(sources);
        this.matchedFields = immutable(matchedFields);
        this.similarity = similarity;
        this.rankFusionScore = rankFusionScore;
        this.breakdown = breakdown == null
                ? new ScoreBreakdown(0, similarity, 0, 0, score) : breakdown;
    }

    public SearchResult withRank(int newRank) {
        return new SearchResult(
                card, score, breakdown, retrievalSources, matchedFields, newRank, similarity,
                rankFusionScore);
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(
                values == null ? Collections.emptyList() : values));
    }
}
