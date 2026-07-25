package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QueryAnalysis {
    public final String rawQuery;
    public final String normalizedQuery;
    public final String keywordQuery;
    public final String semanticQuery;
    public final List<String> tokens;
    public final List<String> ngrams;
    public final boolean strictIdentityQuery;

    public QueryAnalysis(
            String rawQuery, String normalizedQuery, String keywordQuery, String semanticQuery,
            List<String> tokens, List<String> ngrams, boolean strictIdentityQuery) {
        this.rawQuery = value(rawQuery);
        this.normalizedQuery = value(normalizedQuery);
        this.keywordQuery = value(keywordQuery);
        this.semanticQuery = value(semanticQuery);
        this.tokens = immutable(tokens);
        this.ngrams = immutable(ngrams);
        this.strictIdentityQuery = strictIdentityQuery;
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(
                values == null ? Collections.emptyList() : values));
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }
}
