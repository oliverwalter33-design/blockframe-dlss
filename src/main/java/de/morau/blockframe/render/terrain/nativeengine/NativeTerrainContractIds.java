package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Domain-separated stable identifiers for Minecraft-facing terrain contracts.
 *
 * <p>Identity is derived from canonical registry/model/pipeline names. Runtime
 * object identity and iteration order never enter a persistent ABI key.</p>
 */
final class NativeTerrainContractIds {
    private NativeTerrainContractIds() {
    }

    static StableId stableId(String domain, String value) {
        byte[] hash = sha256(domain, value);
        StableId id = new StableId(readLong(hash, 0), readLong(hash, 8));
        if (!id.present()) {
            throw new IllegalStateException("SHA-256 produced reserved id");
        }
        return id;
    }

    static Digest digest(String domain, String value) {
        byte[] hash = sha256(domain, value);
        Digest digest = new Digest(
            readLong(hash, 0),
            readLong(hash, 8),
            readLong(hash, 16),
            readLong(hash, 24)
        );
        digest.requireKnown(domain);
        return digest;
    }

    private static byte[] sha256(String domain, String value) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(value, "value");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, domain);
            update(digest, value);
            return digest.digest();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte)(bytes.length >>> 24));
        digest.update((byte)(bytes.length >>> 16));
        digest.update((byte)(bytes.length >>> 8));
        digest.update((byte)bytes.length);
        digest.update(bytes);
    }

    private static long readLong(byte[] bytes, int offset) {
        return ((long)(bytes[offset] & 255) << 56)
            | ((long)(bytes[offset + 1] & 255) << 48)
            | ((long)(bytes[offset + 2] & 255) << 40)
            | ((long)(bytes[offset + 3] & 255) << 32)
            | ((long)(bytes[offset + 4] & 255) << 24)
            | ((long)(bytes[offset + 5] & 255) << 16)
            | ((long)(bytes[offset + 6] & 255) << 8)
            | (long)(bytes[offset + 7] & 255);
    }
}
