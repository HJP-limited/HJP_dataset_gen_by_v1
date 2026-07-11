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

        List<SearchResult> results = service.search("판교 AI 개발자", 5);

        assertFalse(results.isEmpty());
        assertEquals("C002", results.get(0).card.id);
        assertEquals("ai@example.com", service.getCard("C002").email);
    }

    @Test
    public void unknownKoreanNameDoesNotReturnSemanticFalsePositive() {
        BusinessCard existing = new BusinessCard("C001", "김지원", "Jiwon Kim", "비전글로벌",
                "대표이사", "전략팀", "finance", "서울", "011", "ceo@example.com",
                "서울", "투자 파트너십", Arrays.asList("투자"));
        SearchLookupService service = new SearchLookupService(
                Arrays.asList(existing), new LocalEmbeddingEngine());

        assertTrue(service.search("김민수", 5).isEmpty());
    }
}
