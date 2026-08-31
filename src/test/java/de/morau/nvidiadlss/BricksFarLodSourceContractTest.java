package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class BricksFarLodSourceContractTest {
    private static final Path PROJECT = Path.of(
        System.getProperty("blockframe.projectDir", ".")
    );

    @Test
    void farMeshPreservesBricksTextureAndComponentContracts()
            throws IOException {
        String mesh = local(
            "src/main/java/de/morau/nvidiadlss/BricksFarLodMesh.java"
        );

        assertTrue(mesh.contains("IdentityHashMap<MicroComponent, Integer>"));
        assertTrue(mesh.contains("componentId"));
        assertTrue(mesh.contains("material.fullTexture()"));
        assertTrue(mesh.contains("material.partsTexture()"));
        assertTrue(mesh.contains("material.rodTexture()"));
        assertTrue(mesh.contains("sourceMinS"));
        assertTrue(mesh.contains("sourceMaxT"));
        assertTrue(mesh.contains("rotateUv"));
        assertTrue(mesh.contains("Faces at a neighboring block boundary"));
        assertFalse(mesh.contains("CompositeBlockEntityRenderer"));
    }

    @Test
    void farExtractionCachesByBricksListIdentityAndNeverRunsOriginalExtractor()
            throws IOException {
        String runtime = local(
            "src/main/java/de/morau/nvidiadlss/BricksFarLodRuntime.java"
        );

        assertTrue(runtime.contains("new WeakHashMap<>()"));
        assertTrue(runtime.contains("cached.sourceFaces() != source"));
        assertTrue(runtime.contains("BlockEntityRenderState.extractBase("));
        assertTrue(runtime.contains("BricksFarLodMesh.build(source)"));
        assertTrue(runtime.contains("BATCHES.computeIfAbsent("));
        assertTrue(runtime.contains("FAR_STATES.get(state)"));
        assertTrue(runtime.contains("batch.immutableSnapshot()"));
        assertTrue(runtime.contains("SubmittedFarBatch"));
        assertTrue(runtime.contains("submitted::render"));
        assertTrue(runtime.contains("No array or"));
        assertTrue(runtime.contains("list reachable from this callback"));
        assertFalse(runtime.contains("batch::render"));
        assertFalse(runtime.contains("renderer.extractRenderState"));
        assertFalse(runtime.contains("LinkedHashMap<TextureBatch"));
    }

    @Test
    void levelHookBypassesOnlyMarkedFarStatesAndFlushesOnce()
            throws IOException {
        String mixin = local(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "BricksFarLodLevelRendererMixin.java"
        );

        assertTrue(mixin.contains("@Mixin(LevelRenderer.class)"));
        assertTrue(mixin.contains("method = \"submitBlockEntities\""));
        assertTrue(mixin.contains("BlockEntityRenderDispatcher;submit("));
        assertTrue(mixin.contains("require = 1"));
        assertTrue(mixin.contains("allow = 1"));
        assertTrue(mixin.contains("queueFarState(state, camera)"));
        assertTrue(mixin.contains(
            "dispatcher.submit(state, poseStack, collector, camera)"
        ));
        assertTrue(mixin.contains(
            "flushFarBatches(poseStack, collector)"
        ));
        assertFalse(mixin.contains("sodium"));
        assertFalse(mixin.contains("terrain"));
    }

    private static String local(String relative) throws IOException {
        return Files.readString(PROJECT.resolve(relative));
    }
}
