package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class BricksCompositeDistanceSourceContractTest {
    private static final Path PROJECT = Path.of(
        System.getProperty("blockframe.projectDir", ".")
    );

    @Test
    void mixinRedirectsOnlyVerifiedDispatcherDecisionAndExtraction()
            throws IOException {
        String source = local(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "BricksCompositeBlockEntityDistanceMixin.java"
        );

        assertTrue(source.contains("@Mixin(BlockEntityRenderDispatcher.class)"));
        assertTrue(source.contains("@Redirect("));
        assertTrue(source.contains("method = \"tryExtractRenderState(\""));
        assertTrue(source.contains("renderer/culling/Frustum;)"));
        assertTrue(source.contains("BlockEntityRenderer;shouldRender("));
        assertTrue(source.contains("BlockEntityRenderer;extractRenderState("));
        assertTrue(source.contains("BricksFarLodRuntime.extractFarState("));
        assertTrue(source.contains("require = 1"));
        assertTrue(source.contains("allow = 1"));
        assertTrue(source.contains("renderer.shouldRender(blockEntity, cameraPosition)"));
        assertFalse(source.contains("com.matnx.omni.client.micro.CompositeBlockEntityRenderer"));
        assertFalse(source.contains("MaterialLever"));
    }

    @Test
    void exactClassNameGateAndMixinRegistrationStaySeparateFromTerrain()
            throws IOException {
        String gate = local(
            "src/main/java/de/morau/nvidiadlss/BricksCompatibility.java"
        );
        String plugin = local(
            "src/main/java/de/morau/nvidiadlss/mixin/DlssMixinPlugin.java"
        );
        String mixins = local("src/main/resources/nvidia_dlss.mixins.json");

        assertTrue(gate.contains(
            "com.matnx.omni.client.micro.CompositeBlockEntityRenderer"
        ));
        assertFalse(gate.contains("MaterialLeverBlockEntityRenderer"));
        assertTrue(plugin.contains("isExpectedMixinTarget("));
        assertTrue(plugin.contains("BricksCompatibility.mixinAllowed()"));
        assertTrue(mixins.contains("\"client\": ["));
        assertTrue(mixins.contains("BricksCompositeBlockEntityDistanceMixin"));
        assertTrue(mixins.contains("BricksFarLodLevelRendererMixin"));
        assertFalse(mixins.contains("\"mixins\": ["));
    }

    @Test
    void materialLeverIsNotSilentlyBroadenedIntoThisFix() {
        assertFalse(BricksCompatibility.COMPOSITE_RENDERER_CLASS.contains(
            "MaterialLever"
        ));
        assertTrue(BricksCompatibility.isBricksMixin(
            "de.morau.nvidiadlss.mixin.BricksCompositeBlockEntityDistanceMixin"
        ));
        assertTrue(BricksCompatibility.isBricksMixin(
            "de.morau.nvidiadlss.mixin.BricksFarLodLevelRendererMixin"
        ));
        assertTrue(BricksCompatibility.isExpectedMixinTarget(
            "de.morau.nvidiadlss.mixin.BricksFarLodLevelRendererMixin",
            "net.minecraft.client.renderer.LevelRenderer"
        ));
        assertFalse(BricksCompatibility.isExpectedMixinTarget(
            "de.morau.nvidiadlss.mixin.BricksFarLodLevelRendererMixin",
            "net.minecraft.client.renderer.blockentity."
                + "BlockEntityRenderDispatcher"
        ));
        assertFalse(BricksCompatibility.isBricksMixin(
            "de.morau.nvidiadlss.mixin.MaterialLeverDistanceMixin"
        ));
    }

    private static String local(String relative) throws IOException {
        return Files.readString(PROJECT.resolve(relative));
    }
}
