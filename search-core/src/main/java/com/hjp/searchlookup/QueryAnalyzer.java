package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ryeong query analysis with deterministic Korean suffix/intent cleanup.
 * The semantic query remains the normalized natural-language request.
 */
public final class QueryAnalyzer {
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(Arrays.asList(
            "찾아줘", "찾아", "보여줘", "알려줘", "확인해줘", "확인", "있는", "사람",
            "명함", "연락처", "누구", "검색", "해줘", "주세요", "please", "find", "show",
            "me", "who", "is", "are", "the", "a", "an"));
    private static final List<String> PARTICLES = Arrays.asList(
            "에서는", "에서", "에게", "한테", "으로", "이랑", "부터", "까지", "처럼", "밖에",
            "은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "도", "만", "랑", "로");

    public QueryAnalysis analyze(String rawQuery) {
        String raw = rawQuery == null ? "" : rawQuery;
        String normalized = normalize(raw);
        List<String> tokens = keywordTokens(normalized);
        List<String> ngrams = ngrams(tokens);
        boolean strictIdentity = tokens.size() == 1 && isIdentityLike(tokens.get(0));
        return new QueryAnalysis(
                raw, normalized, String.join(" ", tokens), normalized, tokens, ngrams,
                strictIdentity);
    }

    public String normalize(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^\\p{IsHangul}\\p{L}\\p{N}@._+\\-#\\s]", " ");
        cleaned = cleaned.replaceAll("(?<![\\p{L}\\p{N}])#", " ");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private List<String> keywordTokens(String normalized) {
        if (normalized.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String source : normalized.split("\\s+")) {
            String token = stripParticle(source.trim());
            if (token.length() < 2 || STOP_WORDS.contains(token)) continue;
            result.add(token);
            String digits = token.replaceAll("[^0-9]", "");
            if (digits.length() >= 7 && !digits.equals(token)
                    && token.matches("[0-9+._\\-]+")) result.add(digits);
        }
        return new ArrayList<>(result);
    }

    private String stripParticle(String token) {
        if (!token.matches(".*[가-힣].*")) return token;
        for (String particle : PARTICLES) {
            if (token.endsWith(particle)) {
                String stem = token.substring(0, token.length() - particle.length());
                if (stem.length() >= 2 && stem.matches(".*[가-힣].*")) return stem;
            }
        }
        return token;
    }

    private List<String> ngrams(List<String> tokens) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.length() < 3 || !token.matches(".*[가-힣].*")) continue;
            for (int i = 0; i < token.length() - 1; i++) {
                result.add(token.substring(i, i + 2));
            }
        }
        return new ArrayList<>(result);
    }

    private boolean isIdentityLike(String token) {
        if (token.contains("@") || token.replaceAll("[^0-9]", "").length() >= 7) return true;
        if (!token.matches("[가-힣a-z0-9._+\\-]{2,24}")) return false;
        return !Arrays.asList(
                "개발자", "엔지니어", "대표", "팀장", "이사", "매니저", "담당자",
                "투자", "제조", "생산", "품질", "개발", "디자인", "영업", "마케팅",
                "보안", "금융", "인공지능", "서울", "판교", "부산", "대전", "제주")
                .contains(token);
    }
}
