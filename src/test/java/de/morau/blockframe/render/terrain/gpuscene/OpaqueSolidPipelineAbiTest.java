package de.morau.blockframe.render.terrain.gpuscene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

class OpaqueSolidPipelineAbiTest {
    @Test
    void acceptsAnEquivalentRebuiltMojangPipeline() {
        RenderPipeline rebuilt =
            RenderPipelines.SOLID_TERRAIN.toBuilder().build();

        assertNotSame(RenderPipelines.SOLID_TERRAIN, rebuilt);
        assertNull(OpaqueSolidPipelineAbi.mismatch(rebuilt));
    }

    @Test
    void rejectsChangedRasterState() {
        RenderPipeline changed =
            RenderPipelines.SOLID_TERRAIN
                .toBuilder()
                .withCull(!RenderPipelines.SOLID_TERRAIN.isCull())
                .build();

        assertEquals(
            "CULL",
            OpaqueSolidPipelineAbi.mismatch(changed)
        );
    }

    @Test
    void rejectsChangedShaderAbi() {
        RenderPipeline changed =
            RenderPipelines.SOLID_TERRAIN
                .toBuilder()
                .withVertexShader("core/position")
                .build();

        assertEquals(
            "VERTEX_SHADER",
            OpaqueSolidPipelineAbi.mismatch(changed)
        );
    }

    @Test
    void rejectsChangedVertexFormat() {
        RenderPipeline changed =
            RenderPipelines.SOLID_TERRAIN
                .toBuilder()
                .withVertexBinding(
                    0,
                    DefaultVertexFormat.POSITION
                )
                .build();

        assertEquals(
            "VERTEX_BINDINGS",
            OpaqueSolidPipelineAbi.mismatch(changed)
        );
    }
}
