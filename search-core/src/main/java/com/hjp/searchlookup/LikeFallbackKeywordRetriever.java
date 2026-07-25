package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ryeong's working LIKE fallback. Field-aware ordering adds general exact-name safety without
 * changing the hybrid fusion formula.
 */
public final class LikeFallbackKeywordRetriever implements KeywordRetriever {
    private final BusinessCardRepository repository;

    public LikeFallbackKeywordRetriever(BusinessCardRepository repository) {
        this.repository = repository;
    }

    @Override public List<SearchResult> retrieve(QueryAnalysis analysis, int topK) {
        List<String> tokens = analysis == null ? Collections.emptyList() : analysis.tokens;
        if (tokens.isEmpty()) return Collections.emptyList();
        List<SearchResult> results = new ArrayList<>();
        for (BusinessCard card : repository.getAllCards()) {
            Set<String> fields = new LinkedHashSet<>();
            double score = 0.0;
            for (String token : tokens) score += scoreToken(card, token, fields);
            if (score > 0.0) {
                // Ryeong app uses ngrams to broaden candidates, never as sufficient evidence.
                // Applying the same rule prevents an unknown proper name from matching merely
                // because it shares a common Korean bigram with a real contact.
                for (String ngram : analysis.ngrams) {
                    if (card.privateSearchableText().contains(ngram)) score += 1.0;
                }
                if (card.verified) score += 0.001;
                results.add(new SearchResult(
                        card, score, ScoreBreakdown.keywordOnly(score),
                        Arrays.asList("keyword-like"), new ArrayList<>(fields), 0, 0.0, score));
            }
        }
        results.sort(Comparator.comparingDouble((SearchResult result) -> result.score).reversed()
                .thenComparing(result -> result.card.name)
                .thenComparing(result -> result.card.id));
        return limit(rank(results), topK);
    }

    private double scoreToken(BusinessCard card, String token, Set<String> matchedFields) {
        String needle = token.toLowerCase(Locale.ROOT);
        String normalizedName = normalize(card.name);
        String normalizedNameEn = normalize(card.nameEn);
        if (normalizedName.equals(needle) || normalizedNameEn.equals(needle)) {
            matchedFields.add("name");
            return 1000.0;
        }
        if (normalizedName.startsWith(needle) || normalizedNameEn.startsWith(needle)) {
            matchedFields.add("name");
            return 400.0;
        }
        if (normalizedName.contains(needle) || normalizedNameEn.contains(needle)) {
            matchedFields.add("name");
            return 250.0;
        }
        double score = 0.0;
        score += fieldScore(card.company, needle, "company", matchedFields, 160.0);
        score += fieldScore(card.title, needle, "title", matchedFields, 130.0);
        score += fieldScore(card.department, needle, "department", matchedFields, 120.0);
        score += fieldScore(card.industry, needle, "industry", matchedFields, 100.0);
        score += fieldScore(card.location, needle, "location", matchedFields, 90.0);
        for (String tag : card.tags) {
            if (normalize(tag).contains(needle)) {
                matchedFields.add("tags");
                score += 80.0;
                break;
            }
        }
        score += fieldScore(card.memo, needle, "memo", matchedFields, 60.0);
        score += fieldScore(card.phone, needle, "phone", matchedFields, 180.0);
        score += fieldScore(card.mobile, needle, "phone", matchedFields, 180.0);
        String digits = (card.phone + card.mobile).replaceAll("[^0-9]", "");
        if (needle.matches("[0-9]{3,}") && digits.contains(needle)) {
            matchedFields.add("phone");
            score += 180.0;
        }
        score += fieldScore(card.email, needle, "email", matchedFields, 180.0);
        score += fieldScore(card.address, needle, "address", matchedFields, 40.0);
        score += fieldScore(card.website, needle, "website", matchedFields, 40.0);
        return score;
    }

    private double fieldScore(
            String value, String token, String field, Set<String> matchedFields, double weight) {
        String normalized = normalize(value);
        if (normalized.isEmpty() || !normalized.contains(token)) return 0.0;
        matchedFields.add(field);
        if (normalized.equals(token)) return weight;
        if (normalized.startsWith(token)) return weight * 0.8;
        return weight * 0.6;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private List<SearchResult> rank(List<SearchResult> input) {
        List<SearchResult> result = new ArrayList<>();
        for (int index = 0; index < input.size(); index++) {
            result.add(input.get(index).withRank(index + 1));
        }
        return result;
    }

    private List<SearchResult> limit(List<SearchResult> results, int limit) {
        if (limit <= 0 || limit == Integer.MAX_VALUE) return Collections.unmodifiableList(results);
        return Collections.unmodifiableList(
                new ArrayList<>(results.subList(0, Math.min(limit, results.size()))));
    }
}
