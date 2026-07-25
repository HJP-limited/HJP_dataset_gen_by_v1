package com.hjp.searchlookup;

public interface EmbeddingEngine {
    float[] embed(String input);

    default float[] embedCard(BusinessCard card) {
        return embed(card.searchableText());
    }

    default Float cosine(float[] a, float[] b) {
        return CosineSimilarity.cosine(a, b);
    }

    String name();
    boolean isModelBacked();
}
