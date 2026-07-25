package com.hjp.searchlookup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class EmbeddingUpdater {
    private final BusinessCardRepository repository;
    private final EmbeddingEngine engine;

    public EmbeddingUpdater(BusinessCardRepository repository, EmbeddingEngine engine) {
        this.repository = repository;
        this.engine = engine;
    }

    public void upsertCardAndRefreshEmbedding(BusinessCard card) {
        repository.upsertCard(card);
        refreshIfNeeded(card);
    }

    public void refreshIfNeeded(BusinessCard card) {
        String text = card.searchableText();
        String hash = sha256(text);
        CardEmbedding old = repository.getEmbedding(card.id, engine.name());
        if (old != null && hash.equals(old.sourceTextHash)) return;
        float[] vector = engine.embed(text);
        CosineSimilarity.normalizeInPlace(vector);
        long now = System.currentTimeMillis();
        repository.upsertEmbedding(new CardEmbedding(
                card.id, engine.name(), vector.length, FloatVectorCodec.toBlob(vector), hash,
                old == null ? now : old.createdAt, now));
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((input == null ? "" : input)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
