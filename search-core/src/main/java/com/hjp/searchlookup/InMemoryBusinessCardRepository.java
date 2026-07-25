package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable-snapshot production index and deterministic JVM fixture repository. */
public final class InMemoryBusinessCardRepository implements BusinessCardRepository {
    private final Map<String, BusinessCard> cards = new LinkedHashMap<>();
    private final Map<String, CardEmbedding> embeddings = new LinkedHashMap<>();

    public InMemoryBusinessCardRepository(List<BusinessCard> initial) {
        if (initial != null) for (BusinessCard card : initial) upsertCard(card);
    }

    @Override public synchronized List<BusinessCard> getAllCards() {
        return new ArrayList<>(cards.values());
    }

    @Override public synchronized BusinessCard getCard(String id) {
        return cards.get(id);
    }

    @Override public synchronized void upsertCard(BusinessCard card) {
        if (card != null) cards.put(card.id, card);
    }

    @Override public synchronized CardEmbedding getEmbedding(String cardId, String modelName) {
        return embeddings.get(modelName + ":" + cardId);
    }

    @Override public synchronized List<CardEmbedding> getEmbeddings(String modelName) {
        List<CardEmbedding> result = new ArrayList<>();
        for (CardEmbedding embedding : embeddings.values()) {
            if (embedding.modelName.equals(modelName)) result.add(embedding);
        }
        return result;
    }

    @Override public synchronized void upsertEmbedding(CardEmbedding embedding) {
        if (embedding != null) embeddings.put(
                embedding.modelName + ":" + embedding.cardId, embedding);
    }
}
