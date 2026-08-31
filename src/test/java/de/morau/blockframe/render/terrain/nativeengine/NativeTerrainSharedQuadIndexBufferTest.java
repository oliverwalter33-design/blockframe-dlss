package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class NativeTerrainSharedQuadIndexBufferTest {
    @Test
    void writesExactUint16AndUint32QuadPatternsWithoutWrap() {
        NativeTerrainSharedQuadIndexBuffer indices =
            new NativeTerrainSharedQuadIndexBuffer(2, 3);
        var request = indices.resourceRequest();
        ByteBuffer bytes = ByteBuffer.allocate(
            Math.toIntExact(request.bytes())
        ).order(ByteOrder.LITTLE_ENDIAN);
        request.initialContents().write(bytes);
        assertEquals(0, bytes.remaining());
        bytes.flip();
        int[] expected = {0, 1, 2, 2, 3, 0, 4, 5, 6, 6, 7, 4};
        for (int value : expected) {
            assertEquals(value, Short.toUnsignedInt(bytes.getShort()));
        }
        bytes.position(
            Math.toIntExact(indices.select(12).byteOffset())
        );
        int[] expected32 = {0, 1, 2, 2, 3, 0};
        for (int value : expected32) {
            assertEquals(value, bytes.getInt());
        }
    }

    @Test
    void selectsUint16AtExactBoundaryAndRequiresSplitBeyondCapacity() {
        NativeTerrainSharedQuadIndexBuffer indices =
            new NativeTerrainSharedQuadIndexBuffer(
                NativeTerrainSharedQuadIndexBuffer
                    .MAXIMUM_UINT16_QUADS,
                0
            );
        var boundary = indices.select(
            NativeTerrainSharedQuadIndexBuffer
                .MAXIMUM_UINT16_VERTICES
        );
        assertEquals(IndexType.UINT16, boundary.indexType());
        assertEquals(65_536 / 4 * 6, boundary.indexCount());
        assertThrows(
            IllegalArgumentException.class,
            () -> indices.select(65_540)
        );
    }

    @Test
    void reportsOnlyActuallyAvoidedRepeatedUploadBytes() {
        NativeTerrainSharedQuadIndexBuffer indices =
            new NativeTerrainSharedQuadIndexBuffer(64, 0);
        assertEquals(
            0L,
            indices.avoidedUploadBytes(64L, IndexType.UINT16)
        );
        assertTrue(
            indices.avoidedUploadBytes(
                640L,
                IndexType.UINT16
            ) > 0L
        );
    }
}
