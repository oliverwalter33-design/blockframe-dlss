package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeTerrainPipelinesTest {
    @Test
    void vertexAbiHasExactV2OffsetsFormatsAndStride() {
        var elements =
            NativeTerrainPipelines.BLOCK_PAYLOAD_V2.getElements();
        assertEquals(
            List.of("Position", "Color", "UV0", "UV2", "Normal"),
            elements.stream().map(value -> value.name()).toList()
        );
        assertEquals(
            List.of(0, 12, 16, 24, 28),
            elements.stream().map(value -> value.offset()).toList()
        );
        assertEquals(
            List.of(
                GpuFormat.RGB32_FLOAT,
                GpuFormat.RGBA8_UNORM,
                GpuFormat.RG32_FLOAT,
                GpuFormat.RG16_SINT,
                GpuFormat.RGBA8_SNORM
            ),
            elements.stream().map(value -> value.format()).toList()
        );
        assertEquals(
            TerrainMeshProducerABI.BLOCK_PAYLOAD_V2_STRIDE_BYTES,
            NativeTerrainPipelines.BLOCK_PAYLOAD_V2.getVertexSize()
        );
    }

    @Test
    void solidAndCutoutKeepMojangReversedDepthCullAndExactCutoff() {
        assertSame(
            DepthStencilState.DEFAULT,
            NativeTerrainPipelines.SOLID.getDepthStencilState()
        );
        assertSame(
            DepthStencilState.DEFAULT,
            NativeTerrainPipelines.CUTOUT.getDepthStencilState()
        );
        assertTrue(NativeTerrainPipelines.SOLID.isCull());
        assertTrue(NativeTerrainPipelines.CUTOUT.isCull());
        assertEquals(
            Float.toString(
                Float.intBitsToFloat(
                    TerrainMeshProducerABI
                        .MOJANG_CUTOUT_ALPHA_CUTOFF_BITS
                )
            ),
            NativeTerrainPipelines.CUTOUT
                .getShaderDefines()
                .values()
                .get("ALPHA_CUTOUT")
        );
    }
}
