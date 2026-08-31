package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class FoundationBSourceContractTest {
    @Test
    void censusRunsAfterModelBakeAndEnumeratesVariantGraphs() {
        String mixins = local("src/main/resources/nvidia_dlss.mixins.json");
        assertTrue(mixins.contains(
            "\"NativeTerrainModelManagerMixin\""
        ));
        assertTrue(mixins.contains(
            "\"accessor.NativeTerrainWeightedVariantsAccessor\""
        ));
        assertTrue(mixins.contains(
            "\"accessor.NativeTerrainMultiPartModelAccessor\""
        ));

        String hook = local(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "NativeTerrainModelManagerMixin.java"
        );
        assertTrue(hook.contains("method = \"apply\""));
        assertTrue(hook.contains("@At(\"HEAD\")"));
        assertTrue(hook.contains("@At(\"RETURN\")"));

        String weighted = mojang(
            "net/minecraft/client/renderer/block/dispatch/"
                + "WeightedVariants.java"
        );
        assertTrue(weighted.contains(
            "private final WeightedList<BlockStateModel> list;"
        ));
        assertTrue(weighted.contains(
            "this.list.getRandomOrThrow(random)"
        ));
        String multipart = mojang(
            "net/minecraft/client/renderer/block/dispatch/multipart/"
                + "MultiPartModel.java"
        );
        assertTrue(multipart.contains(
            "private @Nullable List<BlockStateModel> models;"
        ));
        assertTrue(multipart.contains(
            "this.models = this.shared.selectModels(this.blockState);"
        ));

        String census = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/MinecraftTerrainAssetCensusAdapter.java"
        );
        assertTrue(census.contains("collectAllPossibleParts("));
        assertTrue(census.contains(
            ".blockframe$getNativeTerrainVariants()"
        ));
        assertTrue(census.contains(
            ".blockframe$getNativeTerrainSelectedModels()"
        ));
        assertFalse(census.contains("getRandomOrThrow("));
    }

    @Test
    void snapshotAndCompilerCannotReachLiveWorldAfterCapture() {
        String snapshot = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/MinecraftTerrainSectionSnapshot.java"
        );
        assertTrue(snapshot.contains("public static final int HALO = 1;"));
        assertTrue(snapshot.contains("BlockState[] blockStates"));
        assertTrue(snapshot.contains("FluidState[] fluidStates"));
        assertTrue(snapshot.contains("ModelData[] modelData"));
        assertTrue(snapshot.contains("byte[] blockLight"));
        assertTrue(snapshot.contains("byte[] skyLight"));
        assertFalse(snapshot.contains("private final ClientLevel"));
        assertFalse(snapshot.contains("private final RenderSectionRegion"));

        String adapter = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/MinecraftTerrainModelAdapter.java"
        );
        assertFalse(adapter.contains("ClientLevel"));
        assertFalse(adapter.contains("RenderSectionRegion"));
        assertFalse(adapter.contains("import com.mojang.blaze3d.vertex.MeshData"));
        assertFalse(adapter.contains("import com.mojang.blaze3d.vertex.BufferBuilder"));
        assertTrue(adapter.contains("state.getSeed(position)"));
    }

    @Test
    void uploadBorrowsMojangDeviceAllocatorEncoderAndSubmitCadence() {
        String owner = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainGeometryOwner.java"
        );
        assertTrue(owner.contains("device.createBuffer("));
        assertTrue(owner.contains("device.createCommandEncoder()"));
        assertTrue(owner.contains("this.encoder.copyToBuffer("));
        assertTrue(owner.contains("this.encoder.createFence()"));
        assertTrue(owner.contains("awaitCompletion(0L)"));
        assertFalse(owner.contains("vkCreateDevice"));
        assertFalse(owner.contains("vmaCreateAllocator"));
        assertFalse(owner.contains(".submit("));
        assertFalse(owner.contains("new Thread"));
        assertFalse(owner.contains("Executor"));
    }

    private static String local(String relative) {
        try {
            return Files.readString(
                root().resolve(relative),
                StandardCharsets.UTF_8
            );
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String mojang(String entryName) {
        Path sourceJar = root()
            .resolve("build")
            .resolve("moddev")
            .resolve("artifacts")
            .resolve("minecraft-patched-26.2.0.23-beta-sources.jar");
        try (ZipFile zip = new ZipFile(sourceJar.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("missing Mojang source " + entryName);
            }
            return new String(
                zip.getInputStream(entry).readAllBytes(),
                StandardCharsets.UTF_8
            );
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Path root() {
        return Path.of(System.getProperty("blockframe.projectDir", "."));
    }
}
