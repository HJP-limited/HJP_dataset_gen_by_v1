package com.hjp.searchlookup;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class FloatVectorCodec {
    private FloatVectorCodec() {}

    public static byte[] toBlob(float[] vector) {
        if (vector == null) return new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) buffer.putFloat(value);
        return buffer.array();
    }

    public static float[] fromBlob(byte[] blob) {
        if (blob == null || blob.length == 0) return new float[0];
        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[blob.length / 4];
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
        return vector;
    }
}
