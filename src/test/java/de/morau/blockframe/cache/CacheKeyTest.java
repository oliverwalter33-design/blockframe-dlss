package de.morau.blockframe.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CacheKeyTest {
    @Test
    void canonicalFormIsSortedAndRoundTripsExactly() {
        Map<String, String> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("zeta", "last");
        reverseOrder.put("alpha", "first");
        CacheKey key = new CacheKey("native-runtime", 1, reverseOrder);

        String canonical = new String(key.canonicalBytes(), StandardCharsets.UTF_8);
        assertEquals(
            """
            BLOCKFRAME_CACHE_KEY_V1
            schema=1
            kind=native-runtime
            count=2
            dimension=alpha\tfirst
            dimension=zeta\tlast
            """,
            canonical
        );
        CacheKey reparsed = CacheKey.parseCanonical(key.canonicalBytes());
        assertEquals(key, reparsed);
        assertArrayEquals(key.canonicalBytes(), reparsed.canonicalBytes());
        assertEquals(key.digestHex(), reparsed.digestHex());
    }

    @Test
    void rejectsNonCanonicalOrderTrailingMaterialAndMalformedUtf8() {
        byte[] reversed = (
            "BLOCKFRAME_CACHE_KEY_V1\n"
                + "schema=1\nkind=native-runtime\ncount=2\n"
                + "dimension=zeta\tlast\n"
                + "dimension=alpha\tfirst\n"
        ).getBytes(StandardCharsets.UTF_8);
        assertThrows(
            IllegalArgumentException.class,
            () -> CacheKey.parseCanonical(reversed)
        );

        CacheKey key = new CacheKey("native-runtime", 1, Map.of("alpha", "first"));
        byte[] trailing = (
            new String(key.canonicalBytes(), StandardCharsets.UTF_8) + "\n"
        ).getBytes(StandardCharsets.UTF_8);
        assertThrows(
            IllegalArgumentException.class,
            () -> CacheKey.parseCanonical(trailing)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> CacheKey.parseCanonical(new byte[] {(byte)0xc3, (byte)0x28})
        );
    }

    @Test
    void everyRelevantVersionDimensionChangesTheDigest() {
        CacheKey first = new CacheKey(
            "native-runtime",
            1,
            Map.of("blockframe", "1", "bundle", "a")
        );
        CacheKey changedVersion = new CacheKey(
            "native-runtime",
            1,
            Map.of("blockframe", "2", "bundle", "a")
        );
        CacheKey changedSchema = new CacheKey(
            "native-runtime",
            2,
            Map.of("blockframe", "1", "bundle", "a")
        );

        assertNotEquals(first.digestHex(), changedVersion.digestHex());
        assertNotEquals(first.digestHex(), changedSchema.digestHex());
    }

    @Test
    void rejectsSeparatorsInDimensionValues() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new CacheKey(
                "native-runtime",
                1,
                Map.of("blockframe", "bad\nvalue")
            )
        );
    }
}
