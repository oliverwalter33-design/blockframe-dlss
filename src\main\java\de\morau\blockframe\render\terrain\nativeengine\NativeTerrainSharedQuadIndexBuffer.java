package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.BufferKind;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.ResourceRequest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Generation-bound sequential quad index table shared by every compatible
 * Solid/Cutout draw.
 */
public final class NativeTerrainSharedQuadIndexBuffer {
    public static final int MAXIMUM_UINT16_VERTICES = 65_536;
    public static final int MAXIMUM_UINT16_QUADS =
        MAXIMUM_UINT16_VERTICES / 4;

    public record DrawRange(
        IndexType indexType,
        long byteOffset,
        int indexCount
    ) {
        public DrawRange {
            if (byteOffset < 0L || indexCount <= 0) {
                throw new IllegalArgumentException(
                    "invalid shared index draw range"
                );
            }
        }
    }

    public record Metrics(
        int uint16Quads,
        int uint32Quads,
        long uint16Bytes,
        long uint32Bytes,
        long totalBytes
    ) {
    }

    private final int uint16Quads;
    private final int uint32Quads;
    private final long uint16Bytes;
    private final long uint32Offset;
    private final long uint32Bytes;
    private final long totalBytes;

    public NativeTerrainSharedQuadIndexBuffer(
        int uint16Quads,
        int uint32Quads
    ) {
        if (
            uint16Quads <= 0
                || uint16Quads > MAXIMUM_UINT16_QUADS
                || uint32Quads < 0
        ) {
            throw new IllegalArgumentException(
                "invalid shared quad index capacities"
            );
        }
        this.uint16Quads = uint16Quads;
        this.uint32Quads = uint32Quads;
        this.uint16Bytes = multiplyExact(
            uint16Quads,
            6L * Short.BYTES
        );
        this.uint32Offset = alignUp(this.uint16Bytes, Integer.BYTES);
        this.uint32Bytes = multiplyExact(
            uint32Quads,
            6L * Integer.BYTES
        );
        this.totalBytes = Math.addExact(
            this.uint32Offset,
            this.uint32Bytes
        );
    }

    public ResourceRequest resourceRequest() {
        return new ResourceRequest(
            BufferKind.SHARED_INDEX,
            this.totalBytes,
            this::write
        );
    }

    public DrawRange select(int vertexCount) {
        if (vertexCount <= 0 || vertexCount % 4 != 0) {
            throw new IllegalArgumentException(
                "shared quad draw requires complete quads"
            );
        }
        int quads = vertexCount / 4;
        int indexCount = Math.multiplyExact(quads, 6);
        if (quads <= this.uint16Quads) {
            return new DrawRange(
                IndexType.UINT16,
                0L,
                indexCount
            );
        }
        if (quads <= this.uint32Quads) {
            return new DrawRange(
                IndexType.UINT32,
                this.uint32Offset,
                indexCount
            );
        }
        throw new IllegalArgumentException(
            "draw exceeds shared index capacity and must be split"
        );
    }

    public Metrics metrics() {
        return new Metrics(
            this.uint16Quads,
            this.uint32Quads,
            this.uint16Bytes,
            this.uint32Bytes,
            this.totalBytes
        );
    }

    public long avoidedUploadBytes(
        long compatibleQuadDraws,
        IndexType type
    ) {
        if (compatibleQuadDraws < 0L) {
            throw new IllegalArgumentException(
                "quad draw count must not be negative"
            );
        }
        long repeated = multiplyExact(
            compatibleQuadDraws,
            6L * type.bytes()
        );
        long resident = type == IndexType.UINT16
            ? this.uint16Bytes
            : this.uint32Bytes;
        return Math.max(0L, repeated - resident);
    }

    private void write(ByteBuffer destination) {
        if (destination.remaining() != this.totalBytes) {
            throw new IllegalArgumentException(
                "shared index destination has the wrong size"
            );
        }
        destination.order(ByteOrder.LITTLE_ENDIAN);
        for (int quad = 0; quad < this.uint16Quads; quad++) {
            int vertex = quad * 4;
            destination.putShort((short)vertex);
            destination.putShort((short)(vertex + 1));
            destination.putShort((short)(vertex + 2));
            destination.putShort((short)(vertex + 2));
            destination.putShort((short)(vertex + 3));
            destination.putShort((short)vertex);
        }
        while (destination.position() < this.uint32Offset) {
            destination.put((byte)0);
        }
        for (int quad = 0; quad < this.uint32Quads; quad++) {
            int vertex = quad * 4;
            destination.putInt(vertex);
            destination.putInt(vertex + 1);
            destination.putInt(vertex + 2);
            destination.putInt(vertex + 2);
            destination.putInt(vertex + 3);
            destination.putInt(vertex);
        }
    }

    private static long multiplyExact(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(
                "shared index size overflows",
                error
            );
        }
    }

    private static long alignUp(long value, long alignment) {
        return Math.addExact(value, alignment - 1L)
            & -alignment;
    }
}
