package com.hjp.searchlookup.eval;

import com.hjp.searchlookup.BusinessCard;
import com.hjp.searchlookup.EmbeddingEngine;
import com.hjp.searchlookup.LocalEmbeddingEngine;
import com.hjp.searchlookup.SearchLookupService;
import com.hjp.searchlookup.SearchResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic, synthetic, model-free comparison; no real contact data is read. */
public final class SearchEvaluationMain {
    private static final int CARD_COUNT = 5_000;
    private static final int CASES_PER_CATEGORY = 20;
    private static final List<String> CATEGORIES = Arrays.asList(
            "exact_name", "partial_name", "company", "title", "department", "industry",
            "location", "memo", "tags", "email", "phone", "semantic_natural_language",
            "nonexistent", "ambiguous", "duplicate_name", "special_characters",
            "mixed_korean_english");

    private SearchEvaluationMain() {}

    public static void main(String[] args) throws Exception {
        File output = new File(args.length == 0 ? "build/reports/search-evaluation" : args[0]);
        if (!output.isDirectory() && !output.mkdirs()) {
            throw new IllegalStateException("Cannot create report directory: " + output);
        }
        List<BusinessCard> cards = cards();
        List<EvalCase> cases = cases(cards);

        forceGc();
        long baselineMemory = usedMemory();
        long newStart = System.nanoTime();
        SearchLookupService ryeong = new SearchLookupService(cards, new LocalEmbeddingEngine());
        double newIndexMs = elapsedMillis(newStart);
        long newMemory = Math.max(0L, usedMemory() - baselineMemory);

        forceGc();
        long legacyBaseline = usedMemory();
        long legacyStart = System.nanoTime();
        Legacy0711Search legacy = new Legacy0711Search(cards, new LocalEmbeddingEngine());
        double legacyIndexMs = elapsedMillis(legacyStart);
        long legacyMemory = Math.max(0L, usedMemory() - legacyBaseline);

        Metrics oldMetrics = evaluate("0711_legacy", cases, legacy::search, legacyIndexMs, legacyMemory);
        Metrics newMetrics = evaluate(
                "0725_ryeong", cases,
                (query, limit) -> ryeong.retrieve(query, limit).results,
                newIndexMs, newMemory);

        String json = "{\n"
                + "  \"dataset\": {\"synthetic_cards\": " + cards.size()
                + ", \"cases\": " + cases.size() + "},\n"
                + "  \"legacy_0711\": " + oldMetrics.toJson("  ") + ",\n"
                + "  \"ryeong_0725\": " + newMetrics.toJson("  ") + ",\n"
                + "  \"thresholds\": {\"exact_name_top1\": 1.0, \"recall_at_5\": 0.95,"
                + " \"mrr\": 0.90, \"nonexistent_false_positive_rate\": 0.0,"
                + " \"ambiguous_auto_selection_count\": 0, \"p95_latency_ms\": 150.0}\n"
                + "}\n";
        Files.write(new File(output, "search-evaluation.json").toPath(),
                json.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(output, "search-evaluation.csv").toPath(),
                csv(oldMetrics, newMetrics).getBytes(StandardCharsets.UTF_8));
        System.out.println(json);
    }

    private static Metrics evaluate(
            String name, List<EvalCase> cases, Searcher searcher,
            double indexMs, long memoryDelta) {
        int top1 = 0;
        int foundAt5 = 0;
        int nonexistentFalsePositive = 0;
        int ambiguousAutoSelection = 0;
        double reciprocalRank = 0.0;
        double ndcg = 0.0;
        List<Double> latencies = new ArrayList<>();
        Map<String, CategoryMetrics> byCategory = new LinkedHashMap<>();
        for (String category : CATEGORIES) byCategory.put(category, new CategoryMetrics());

        for (EvalCase item : cases) {
            long start = System.nanoTime();
            List<SearchResult> results = searcher.search(item.query, 5);
            latencies.add(elapsedMillis(start));
            CategoryMetrics category = byCategory.get(item.category);
            category.total++;
            int rank = firstRelevantRank(results, item.relevantIds);
            if (item.relevantIds.isEmpty()) {
                if (!results.isEmpty()) {
                    nonexistentFalsePositive++;
                    category.falsePositive++;
                }
                continue;
            }
            if (item.ambiguous && results.size() == 1) ambiguousAutoSelection++;
            if (rank == 1) {
                top1++;
                category.top1++;
            }
            if (rank > 0) {
                foundAt5++;
                reciprocalRank += 1.0 / rank;
                ndcg += 1.0 / log2(rank + 1);
                category.found++;
                category.rr += 1.0 / rank;
            }
        }
        Collections.sort(latencies);
        int relevantCases = (int) cases.stream().filter(item -> !item.relevantIds.isEmpty()).count();
        return new Metrics(
                name, ratio(top1, relevantCases), ratio(foundAt5, relevantCases),
                reciprocalRank / Math.max(1, relevantCases), ndcg / Math.max(1, relevantCases),
                ratio(nonexistentFalsePositive,
                        (int) cases.stream().filter(item -> item.relevantIds.isEmpty()).count()),
                ambiguousAutoSelection, average(latencies), percentile(latencies, .95),
                indexMs, memoryDelta, byCategory);
    }

    private static List<BusinessCard> cards() {
        String[] titles = {"대표", "AI 엔지니어", "품질 관리자", "영업 매니저", "디자이너"};
        String[] departments = {"경영", "플랫폼", "품질", "영업", "브랜드"};
        String[] industries = {"finance", "it", "manufacturing", "sales", "design"};
        String[] locations = {"서울", "판교", "부산", "대전", "제주"};
        String[] concepts = {"투자 파트너십", "인공지능 개발", "생산 검사", "고객 영업", "브랜드 시각"};
        List<BusinessCard> cards = new ArrayList<>();
        for (int index = 0; index < CARD_COUNT; index++) {
            int group = index % titles.length;
            String suffix = String.format("%05d", index);
            cards.add(new BusinessCard(
                    "S" + suffix, "합성인물" + suffix, "Synthetic Person " + suffix,
                    "합성기업" + String.format("%04d", index / 5), titles[group],
                    departments[group], industries[group], locations[group],
                    "010-" + String.format("%04d", index / 10) + "-" + String.format("%04d", index),
                    "fixture" + suffix + "@example.test", "합성주소 " + locations[group],
                    concepts[group] + " 프로젝트 담당", Arrays.asList(concepts[group], industries[group])));
        }
        cards.set(0, new BusinessCard(
                "S00000", "중복이름", "", "첫중복회사", "대표", "경영", "finance", "서울",
                "010-0000-0000", "duplicate0@example.test", "", "투자", Arrays.asList("투자")));
        cards.set(1, new BusinessCard(
                "S00001", "중복이름", "", "둘중복회사", "AI 엔지니어", "플랫폼", "it", "판교",
                "010-0000-0001", "duplicate1@example.test", "", "인공지능 개발",
                Arrays.asList("AI")));
        return cards;
    }

    private static List<EvalCase> cases(List<BusinessCard> cards) {
        List<EvalCase> cases = new ArrayList<>();
        for (int offset = 10; offset < 10 + CASES_PER_CATEGORY; offset++) {
            BusinessCard card = cards.get(offset);
            cases.add(one("exact_name", card.name + " 명함을 찾아줘", card.id));
            cases.add(one("partial_name", card.name.substring(2), card.id));
            cases.add(many("company", card.company + " 사람", ids(cards, c -> c.company.equals(card.company))));
            cases.add(many("title", card.title + " 명함", ids(cards, c -> c.title.equals(card.title))));
            cases.add(many("department", card.department + " 부서 담당자", ids(cards, c -> c.department.equals(card.department))));
            cases.add(many("industry", card.industry + " 업종", ids(cards, c -> c.industry.equals(card.industry))));
            cases.add(many("location", card.location + " 지역 사람", ids(cards, c -> c.location.equals(card.location))));
            cases.add(many("memo", card.memo, ids(cards, c -> c.memo.equals(card.memo))));
            cases.add(many("tags", card.tags.get(0), ids(cards, c -> c.tags.contains(card.tags.get(0)))));
            cases.add(one("email", card.email, card.id));
            cases.add(one("phone", card.phone.replaceAll("[^0-9]", ""), card.id));
            cases.add(many("semantic_natural_language", "인공지능 시스템을 만드는 개발 담당자",
                    ids(cards, c -> c.industry.equals("it"))));
            cases.add(new EvalCase("nonexistent", "없는사람" + offset + " 명함 찾아줘",
                    Collections.emptySet(), false));
            cases.add(new EvalCase("ambiguous", "중복이름에게 연락", set("S00000", "S00001"), true));
            cases.add(new EvalCase("duplicate_name", "중복이름 명함", set("S00000", "S00001"), true));
            cases.add(one("special_characters", "!!! " + card.name + " ###", card.id));
            cases.add(one("mixed_korean_english", card.name + " " + card.industry, card.id));
        }
        return cases;
    }

    private static EvalCase one(String category, String query, String id) {
        return new EvalCase(category, query, set(id), false);
    }

    private static EvalCase many(String category, String query, Set<String> ids) {
        return new EvalCase(category, query, ids, false);
    }

    private static Set<String> ids(List<BusinessCard> cards, CardPredicate predicate) {
        Set<String> ids = new HashSet<>();
        for (BusinessCard card : cards) if (predicate.matches(card)) ids.add(card.id);
        return ids;
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static int firstRelevantRank(List<SearchResult> results, Set<String> relevant) {
        for (int index = 0; index < Math.min(5, results.size()); index++) {
            if (relevant.contains(results.get(index).cardId)) return index + 1;
        }
        return -1;
    }

    private static String csv(Metrics oldMetrics, Metrics newMetrics) {
        StringBuilder result = new StringBuilder(
                "model,top1_accuracy,recall_at_5,mrr,ndcg_at_5,nonexistent_false_positive_rate,"
                + "ambiguous_auto_selection_count,average_latency_ms,p95_latency_ms,"
                + "index_build_time_ms,memory_delta_bytes\n");
        result.append(oldMetrics.csv()).append('\n').append(newMetrics.csv()).append('\n');
        return result.toString();
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double average(List<Double> values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return values.isEmpty() ? 0.0 : sum / values.size();
    }

    private static double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return 0.0;
        int index = Math.min(values.size() - 1,
                Math.max(0, (int) Math.ceil(values.size() * percentile) - 1));
        return values.get(index);
    }

    private static double elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void forceGc() {
        System.gc();
    }

    private interface Searcher {
        List<SearchResult> search(String query, int limit);
    }

    private interface CardPredicate {
        boolean matches(BusinessCard card);
    }

    private static final class EvalCase {
        final String category;
        final String query;
        final Set<String> relevantIds;
        final boolean ambiguous;

        EvalCase(String category, String query, Set<String> relevantIds, boolean ambiguous) {
            this.category = category;
            this.query = query;
            this.relevantIds = relevantIds;
            this.ambiguous = ambiguous;
        }
    }

    private static final class CategoryMetrics {
        int total;
        int top1;
        int found;
        int falsePositive;
        double rr;

        String toJson() {
            return "{\"cases\":" + total + ",\"top1\":" + ratio(top1, total)
                    + ",\"recall_at_5\":" + ratio(found, total)
                    + ",\"mrr\":" + (total == 0 ? 0.0 : rr / total)
                    + ",\"false_positives\":" + falsePositive + "}";
        }
    }

    private static final class Metrics {
        final String name;
        final double top1;
        final double recallAt5;
        final double mrr;
        final double ndcgAt5;
        final double nonexistentFalsePositiveRate;
        final int ambiguousAutoSelectionCount;
        final double averageLatencyMs;
        final double p95LatencyMs;
        final double indexBuildTimeMs;
        final long memoryDeltaBytes;
        final Map<String, CategoryMetrics> categories;

        Metrics(
                String name, double top1, double recallAt5, double mrr, double ndcgAt5,
                double nonexistentFalsePositiveRate, int ambiguousAutoSelectionCount,
                double averageLatencyMs, double p95LatencyMs, double indexBuildTimeMs,
                long memoryDeltaBytes, Map<String, CategoryMetrics> categories) {
            this.name = name;
            this.top1 = top1;
            this.recallAt5 = recallAt5;
            this.mrr = mrr;
            this.ndcgAt5 = ndcgAt5;
            this.nonexistentFalsePositiveRate = nonexistentFalsePositiveRate;
            this.ambiguousAutoSelectionCount = ambiguousAutoSelectionCount;
            this.averageLatencyMs = averageLatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.indexBuildTimeMs = indexBuildTimeMs;
            this.memoryDeltaBytes = memoryDeltaBytes;
            this.categories = categories;
        }

        String toJson(String indent) {
            StringBuilder categoryJson = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, CategoryMetrics> entry : categories.entrySet()) {
                if (!first) categoryJson.append(',');
                first = false;
                categoryJson.append('"').append(entry.getKey()).append("\":")
                        .append(entry.getValue().toJson());
            }
            categoryJson.append('}');
            return "{\"name\":\"" + name + "\",\"top1_accuracy\":" + top1
                    + ",\"recall_at_5\":" + recallAt5 + ",\"mrr\":" + mrr
                    + ",\"ndcg_at_5\":" + ndcgAt5
                    + ",\"nonexistent_false_positive_rate\":" + nonexistentFalsePositiveRate
                    + ",\"ambiguous_auto_selection_count\":" + ambiguousAutoSelectionCount
                    + ",\"average_latency_ms\":" + averageLatencyMs
                    + ",\"p95_latency_ms\":" + p95LatencyMs
                    + ",\"index_build_time_ms\":" + indexBuildTimeMs
                    + ",\"memory_delta_bytes\":" + memoryDeltaBytes
                    + ",\"categories\":" + categoryJson + "}";
        }

        String csv() {
            return name + "," + top1 + "," + recallAt5 + "," + mrr + "," + ndcgAt5 + ","
                    + nonexistentFalsePositiveRate + "," + ambiguousAutoSelectionCount + ","
                    + averageLatencyMs + "," + p95LatencyMs + "," + indexBuildTimeMs + ","
                    + memoryDeltaBytes;
        }
    }

    /** 0711 production algorithm retained only in evaluation source, never production runtime. */
    private static final class Legacy0711Search {
        private static final float SEMANTIC_THRESHOLD = 0.04f;
        private static final double MIN_RESULT_SCORE = 5.0;
        private final List<BusinessCard> cards;
        private final EmbeddingEngine engine;
        private final Map<String, float[]> vectors = new HashMap<>();

        Legacy0711Search(List<BusinessCard> cards, EmbeddingEngine engine) {
            this.cards = cards;
            this.engine = engine;
            for (BusinessCard card : cards) vectors.put(card.id, engine.embedCard(card));
        }

        List<SearchResult> search(String rawQuery, int limit) {
            String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.KOREAN);
            List<String> tokens = Arrays.asList(query.split("\\s+"));
            float[] queryVector = engine.embed(query);
            boolean koreanNameOnly = query.matches("[가-힣]{2,4}");
            List<SearchResult> results = new ArrayList<>();
            for (BusinessCard card : cards) {
                if (koreanNameOnly && !card.name.toLowerCase(Locale.KOREAN).contains(query)) continue;
                double score = 0.0;
                String text = card.privateSearchableText();
                for (String token : tokens) {
                    if (token.length() < 2) continue;
                    if (card.name.toLowerCase(Locale.KOREAN).contains(token)) score += 50;
                    else if (card.company.toLowerCase(Locale.KOREAN).contains(token)) score += 35;
                    else if (card.title.toLowerCase(Locale.KOREAN).contains(token)) score += 25;
                    else if (card.industry.toLowerCase(Locale.KOREAN).contains(token)) score += 20;
                    else if (text.contains(token)) score += 12;
                }
                Float semantic = engine.cosine(queryVector, vectors.get(card.id));
                if (semantic != null && semantic > SEMANTIC_THRESHOLD) score += semantic * 45.0;
                if (score >= MIN_RESULT_SCORE) results.add(new SearchResult(card, score));
            }
            results.sort(Comparator.comparingDouble((SearchResult result) -> result.score)
                    .reversed().thenComparing(result -> result.cardId));
            return new ArrayList<>(results.subList(0, Math.min(limit, results.size())));
        }
    }
}
