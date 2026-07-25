package com.hjp.searchlookup;

public final class CosineSimilarity {
    private CosineSimilarity() {}

    public static Float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return null;
        double dot = 0.0;
        double an = 0.0;
        double bn = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            an += a[i] * a[i];
            bn += b[i] * b[i];
        }
        if (an == 0.0 || bn == 0.0) return null;
        return (float) (dot / (Math.sqrt(an) * Math.sqrt(bn)));
    }

    public static void normalizeInPlace(float[] vector) {
        if (vector == null) return;
        double sum = 0.0;
        for (float value : vector) sum += value * value;
        if (sum == 0.0) return;
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
    }
}
