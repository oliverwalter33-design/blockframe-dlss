package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class NativeTerrainFrustumTest {
    private static final Bounds UNIT =
        new Bounds(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);

    @Test
    void usesVulkanZeroToOneNearPlaneAndAllSixBoundaries() {
        NativeTerrainFrustum frustum = identity();
        assertTrue(frustum.intersects(0, 0, 0, UNIT));
        assertTrue(
            frustum.intersects(
                -1,
                -1,
                0,
                new Bounds(0, 0, 0, 0, 0, 0)
            )
        );
        assertFalse(frustum.intersects(-3, 0, 0, UNIT));
        assertFalse(frustum.intersects(2, 0, 0, UNIT));
        assertFalse(frustum.intersects(0, -3, 0, UNIT));
        assertFalse(frustum.intersects(0, 2, 0, UNIT));
        assertFalse(frustum.intersects(0, 0, -2, UNIT));
        assertFalse(frustum.intersects(0, 0, 2, UNIT));
    }

    @Test
    void usesExactNegativeMojangOffsetAtNegativeCoordinates() {
        NativeTerrainFrustum frustum = new NativeTerrainFrustum();
        frustum.update(
            new Matrix4f(),
            -29_999_999,
            128,
            -29_999_999,
            -0.75F,
            0.0F,
            0.0F,
            1,
            16,
            NativeTerrainFrustum.DEFAULT_CONSERVATIVE_EPSILON
        );
        assertTrue(
            frustum.intersects(
                -29_999_998,
                128,
                -29_999_999,
                new Bounds(0, 0, 0, 0, 0, 0)
            )
        );
        assertFalse(
            frustum.intersects(
                -30_000_000,
                128,
                -29_999_999,
                new Bounds(0, 0, 0, 0, 0, 0)
            )
        );
        ByteBuffer bytes = ByteBuffer.allocateDirect(
            NativeTerrainFrustum.PUSH_CONSTANT_BYTES
        ).order(ByteOrder.LITTLE_ENDIAN);
        frustum.writePushConstants(bytes);
        bytes.flip();
        bytes.position(112);
        assertEquals(-0.75F, bytes.getFloat());
        assertEquals(0.0F, bytes.getFloat());
        assertEquals(0.0F, bytes.getFloat());
        assertEquals(16, bytes.getInt());
    }

    @Test
    void cameraTeleportResizeAndZeroOrMaximumSceneCountStayBounded() {
        NativeTerrainFrustum frustum = new NativeTerrainFrustum();
        Matrix4f resizedProjection = new Matrix4f().scale(
            0.5F,
            0.75F,
            1.0F
        );
        frustum.update(
            resizedProjection,
            -30_000_000,
            -64,
            30_000_000,
            -0.25F,
            -0.75F,
            -0.5F,
            0,
            Integer.MAX_VALUE,
            NativeTerrainFrustum.DEFAULT_CONSERVATIVE_EPSILON
        );
        assertTrue(
            frustum.intersects(
                -30_000_000,
                -64,
                30_000_000,
                UNIT
            ),
            "camera inside the section must remain visible"
        );
        frustum.update(
            resizedProjection,
            30_000_000,
            320,
            -30_000_000,
            0.0F,
            0.0F,
            0.0F,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            0.01F
        );
        assertTrue(
            frustum.intersects(
                30_000_000,
                320,
                -30_000_000,
                UNIT
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> frustum.update(
                new Matrix4f(),
                0,
                0,
                0,
                0.0F,
                0.0F,
                0.0F,
                17,
                16,
                0.0F
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> frustum.update(
                new Matrix4f(),
                0,
                0,
                0,
                0.25F,
                0.0F,
                0.0F,
                1,
                16,
                NativeTerrainFrustum
                    .DEFAULT_CONSERVATIVE_EPSILON
            )
        );
    }

    @Test
    void embedsCustomConservativeEpsilonInGpuPlanes() {
        NativeTerrainFrustum frustum = new NativeTerrainFrustum();
        frustum.update(
            new Matrix4f(),
            0,
            0,
            0,
            0.0F,
            0.0F,
            0.0F,
            1,
            16,
            0.25F
        );
        ByteBuffer bytes = ByteBuffer.allocateDirect(
            NativeTerrainFrustum.PUSH_CONSTANT_BYTES
        ).order(ByteOrder.LITTLE_ENDIAN);
        frustum.writePushConstants(bytes);
        bytes.flip();
        bytes.position(3 * Float.BYTES);
        assertEquals(1.25F, bytes.getFloat());
        assertTrue(
            frustum.intersects(
                -2,
                0,
                0,
                new Bounds(0.75F, 0, 0, 0.75F, 0, 0)
            )
        );
        assertFalse(
            frustum.intersects(
                -2,
                0,
                0,
                new Bounds(
                    0.749F,
                    0,
                    0,
                    0.749F,
                    0,
                    0
                )
            )
        );
    }

    @Test
    void writesExactAllocationFree128BytePushContract() {
        NativeTerrainFrustum frustum = identity();
        ByteBuffer bytes = ByteBuffer.allocateDirect(
            NativeTerrainFrustum.PUSH_CONSTANT_BYTES
        ).order(ByteOrder.LITTLE_ENDIAN);
        frustum.writePushConstants(bytes);
        assertEquals(
            NativeTerrainFrustum.PUSH_CONSTANT_BYTES,
            bytes.position()
        );
        bytes.flip();
        bytes.position(96);
        assertEquals(0, bytes.getInt());
        assertEquals(0, bytes.getInt());
        assertEquals(0, bytes.getInt());
        assertEquals(1, bytes.getInt());
        assertEquals(0.0F, bytes.getFloat());
        assertEquals(0.0F, bytes.getFloat());
        assertEquals(0.0F, bytes.getFloat());
        assertEquals(16, bytes.getInt());
    }

    private static NativeTerrainFrustum identity() {
        NativeTerrainFrustum frustum = new NativeTerrainFrustum();
        frustum.update(
            new Matrix4f(),
            0,
            0,
            0,
            0.0F,
            0.0F,
            0.0F,
            1,
            16,
            NativeTerrainFrustum.DEFAULT_CONSERVATIVE_EPSILON
        );
        return frustum;
    }
}
