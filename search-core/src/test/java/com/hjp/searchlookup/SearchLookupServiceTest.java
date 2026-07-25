package com.hjp.searchlookup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SearchLookupServiceTest {
    @Test
    public void hybridSearchFindsRelevantCardAndLookupUsesStableId() {
        BusinessCard ai = new BusinessCard("C002", "오성령", "Sungryung Oh", "코어AI",
                "AI 엔지니어", "플랫폼팀", "it", "판교", "010", "ai@example.com",
                "성남", "로컬 임베딩 검색", Arrays.asList("AI", "개발자"));
        BusinessCard finance = new BusinessCard("C001", "김지원", "Jiwon Kim", "비전글로벌",
                "대표이사", "전략팀", "finance", "서울", "011", "ceo@example.com",
                "서울", "투자 파트너십", Arrays.asList("투자"));
        SearchLookupService service = new SearchLookupService(Arrays.asList(ai, finance), new LocalEmbeddingEngine());

        RetrievalResponse response = service.retrieve("판교 AI 개발자", 5);
        List<SearchResult> results = response.results;

        assertFalse(results.isEmpty());
        assertEquals("C002", results.get(0).card.id);
        assertEquals("ai@example.com", service.getCard("C002").email);
        assertEquals("reciprocal-rank-fusion-k60", response.rerankerName);
        assertTrue(response.ragContext.contains("cardId=C002"));
        assertFalse(response.ragContext.contains("ai@example.com"));
        assertFalse(response.ragContext.contains("010"));
    }

    @Test
    public void unknownKoreanNameDoesNotReturnSemanticFalsePositive() {
        BusinessCard existing = new BusinessCard("C001", "김지원", "Jiwon Kim", "비전글로벌",
                "대표이사", "전략팀", "finance", "서울", "011", "ceo@example.com",
                "서울", "투자 파트너십", Arrays.asList("투자"));
        SearchLookupService service = new SearchLookupService(
                Arrays.asList(existing), new LocalEmbeddingEngine());

        assertTrue(service.retrieve("김민수 명함 찾아줘", 5).results.isEmpty());
    }

    @Test
    public void exactNameAndPartialNameRankBeforeSemanticCandidates() {
        BusinessCard exact = card("C1", "김지원", "비전글로벌", "대표", "전략", "finance", "서울");
        BusinessCard partial = card("C2", "김지우", "코어AI", "개발자", "AI", "it", "판교");
        SearchLookupService service = new SearchLookupService(
                Arrays.asList(partial, exact), new LocalEmbeddingEngine());

        assertEquals("C1", service.retrieve("김지원 연락처 보여줘", 5).results.get(0).cardId);
        List<SearchResult> partialResults = service.retrieve("김지", 5).results;
        assertEquals(2, partialResults.size());
        assertTrue(partialResults.stream().anyMatch(result -> result.cardId.equals("C1")));
        assertTrue(partialResults.stream().anyMatch(result -> result.cardId.equals("C2")));
    }

    @Test
    public void duplicateNameReturnsAllMatchesWithoutChoosingOne() {
        BusinessCard first = card("D1", "홍길동", "첫회사", "개발자", "플랫폼", "it", "서울");
        BusinessCard second = card("D2", "홍길동", "둘회사", "대표", "경영", "finance", "부산");
        SearchLookupService service = new SearchLookupService(
                Arrays.asList(first, second), new LocalEmbeddingEngine());

        List<SearchResult> results = service.retrieve("홍길동 명함", 5).results;

        assertEquals(2, results.size());
        assertEquals(new java.util.HashSet<>(Arrays.asList("D1", "D2")),
                new java.util.HashSet<>(Arrays.asList(results.get(0).cardId, results.get(1).cardId)));
    }

    @Test
    public void privateFieldsCanMatchButNeverAppearInRagContext() {
        BusinessCard card = new BusinessCard(
                "P1", "박하나", "", "안전회사", "보안담당", "", "security", "서울",
                "010-1234-5678", "private@example.com", "서울 상세주소", "민감한 전체 메모",
                Arrays.asList("보안"));
        SearchLookupService service = new SearchLookupService(
                Arrays.asList(card), new LocalEmbeddingEngine());

        RetrievalResponse email = service.retrieve("private@example.com", 5);
        RetrievalResponse phone = service.retrieve("01012345678", 5);

        assertEquals("P1", email.results.get(0).cardId);
        assertEquals("P1", phone.results.get(0).cardId);
        assertFalse(email.ragContext.contains("private@example.com"));
        assertFalse(email.ragContext.contains("010-1234-5678"));
        assertFalse(email.ragContext.contains("민감한 전체 메모"));
    }

    @Test
    public void rrfUsesRyeongK60RankFormula() {
        BusinessCard first = card("R1", "한별", "A", "개발자", "", "it", "서울");
        BusinessCard second = card("R2", "두별", "B", "대표", "", "finance", "부산");
        SearchResult keyword = new SearchResult(
                first, 100, ScoreBreakdown.keywordOnly(100), Arrays.asList("keyword"),
                Arrays.asList("name"), 1, 0, 0);
        SearchResult semantic = new SearchResult(
                first, .9, new ScoreBreakdown(0, .9, 0, 0, .9),
                Arrays.asList("semantic"), java.util.Collections.emptyList(), 2, .9, 0);
        SearchResult other = new SearchResult(
                second, .8, new ScoreBreakdown(0, .8, 0, 0, .8),
                Arrays.asList("semantic"), java.util.Collections.emptyList(), 1, .8, 0);

        List<SearchResult> results = new ReciprocalRankFusion().fuse(
                Arrays.asList(keyword), Arrays.asList(other, semantic), 5);

        assertEquals("R1", results.get(0).cardId);
        assertEquals(1.0 / 61.0 + 1.0 / 62.0, results.get(0).rankFusionScore, 0.0000001);
        assertEquals(60, ReciprocalRankFusion.DEFAULT_RRF_K);
    }

    @Test
    public void queryAnalyzerSupportsMixedKoreanEnglishAndSpecialCharacters() {
        QueryAnalysis analysis = new QueryAnalyzer().analyze(
                "코어AI에서 test@example.com / 010-1234-5678 찾아줘!!!");

        assertTrue(analysis.tokens.contains("코어ai"));
        assertTrue(analysis.tokens.contains("test@example.com"));
        assertTrue(analysis.tokens.contains("01012345678"));
        assertFalse(analysis.strictIdentityQuery);
    }

    private BusinessCard card(
            String id, String name, String company, String title, String department,
            String industry, String location) {
        return new BusinessCard(
                id, name, "", company, title, department, industry, location, "", "", "",
                title + " 업무 담당", Arrays.asList(industry));
    }
}
