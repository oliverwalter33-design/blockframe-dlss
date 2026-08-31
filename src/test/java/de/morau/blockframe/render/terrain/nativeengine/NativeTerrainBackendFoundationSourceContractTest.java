package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class NativeTerrainBackendFoundationSourceContractTest {
    @Test
    void backendChoiceUsesPostCensusWorldResourceBoundary() {
        String minecraftMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "MinecraftLifecycleMixin.java"
        );
        assertFalse(
            minecraftMixin.contains(
                "selectTerrainBackendBeforeWorldRenderer"
            )
        );
        assertFalse(
            minecraftMixin.contains(
                "NativeTerrainBackendFoundation"
                    + ".selectAtFirstWorldResourceBoundary()"
            )
        );

        String ownerMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "LevelRendererMixin.java"
        );
        String creation = methodBody(
            ownerMixin,
            "private void "
                + "blockframe$createSelectedTerrainWorldResources"
        );
        assertOrdered(
            creation,
            "beginReferenceWorldResourceCreation()",
            "original.call(level, options, camera, blockColors)",
            "completeWorldResourceCreation(permit)",
            "finally",
            "abortReferenceWorldResourceCreation(permit)"
        );

        String levelRenderer = mojangSource(
            "net/minecraft/client/renderer/LevelRenderer.java"
        );
        String invalidate = methodBody(
            levelRenderer,
            "invalidateCompiledGeometry("
        );
        assertOrdered(
            invalidate,
            "new SectionCompiler(",
            "new SectionRenderDispatcher(",
            "new ViewArea("
        );

        String foundation = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainBackendFoundation.java"
        );
        String begin = methodBody(
            foundation,
            "beginReferenceWorldResourceCreation() {"
        );
        assertOrdered(
            begin,
            "if (SELECTOR.phase() == Phase.UNSELECTED)",
            "selectAtFirstWorldResourceBoundary()",
            "if (SELECTOR.selection().nativeBackendSelected())",
            "SELECTOR.beginWorldResourceCreation()"
        );
        assertTrue(
            begin.contains(
                "Mojang construction is forbidden"
            )
        );
        assertTrue(
            begin.contains(
                "Mojang rebuild is forbidden"
            )
        );
        assertOrdered(
            begin,
            "try {",
            "selectAtFirstWorldResourceBoundary()",
            "catch (",
            "SELECTOR.failClosedBeforeWorldResources("
        );

        String modelMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "NativeTerrainModelManagerMixin.java"
        );
        assertTrue(
            modelMixin.contains(
                "NativeTerrainBackendFoundation.modelsReloaded("
            )
        );
    }

    @Test
    void preDeviceNegotiationIsIndependentAndGenerationBound() {
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        assertTrue(
            methodBody(
                bootstrap,
                "public static void beginVulkanDeviceCreation"
            ).contains(
                "NativeTerrainBackendFoundation"
                    + ".beginVulkanDeviceCreation()"
            )
        );
        String configure = methodBody(
            bootstrap,
            "public static void configureDeviceCapabilities"
        );
        assertOrdered(
            configure,
            "NativeTerrainBackendFoundation.configureDeviceCapabilities(",
            "VulkanDeviceCapabilityProbe.query("
        );
        assertFalse(
            configure.contains("OpaqueSolidIndirectNegotiator")
        );

        String hook = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanBackendMixin.java"
        );
        assertTrue(
            hook.contains(
                "VulkanBackend;createDevice(Ljava/util/Collection;"
                    + "Lcom/mojang/blaze3d/vulkan/"
                    + "VulkanPhysicalDevice;Ljava/util/Set;)"
                    + "Lorg/lwjgl/vulkan/VkDevice;"
            )
        );
        assertTrue(hook.contains("shift = At.Shift.BEFORE"));
    }

    @Test
    void productionCoordinatorCannotManufactureNativeEligibility() {
        String foundation = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainBackendFoundation.java"
        );
        String preflight = methodBody(
            foundation,
            "incompleteProductionPreflight() {"
        );
        assertTrue(
            preflight.contains(
                "NativeTerrainAssetCensus.capture("
            )
        );
        assertOrdered(
            preflight,
            "NativeTerrainAssetCensus.capture(",
            "resourceGeneration,",
            "false,",
            "List.of()"
        );
        assertTrue(
            preflight.contains(
                "EnumSet.of(Category.SOLID, Category.CUTOUT)"
            )
        );
        assertTrue(
            preflight.contains(
                "\"minecraft-shader-and-material-adapter-not-connected\""
            )
        );
        assertFalse(
            foundation.contains(
                "demoteUnavailableNativeFactory("
            )
        );
        assertTrue(
            foundation.contains(
                "ExclusiveNativeWorldResourceFactory"
            )
        );
        assertFalse(foundation.contains("new SectionRenderDispatcher"));
        assertFalse(foundation.contains("new SectionCompiler"));
    }

    @Test
    void openGlCannotRequestEvenTheNativeModelCensus() {
        String foundation = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainBackendFoundation.java"
        );
        String census = methodBody(
            foundation,
            "censusRequested() {"
        );
        assertOrdered(
            census,
            "Attestation capability = capabilityAttestation",
            "capability != null",
            "capability.vulkan()",
            "!capability.closed()"
        );
    }

    @Test
    void compilerFoundationOwnsNoMinecraftPayloadOrVulkanObject() {
        for (String relative : new String[] {
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainSectionSnapshot.java",
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainAssetCensus.java",
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/BlockFrameSectionCompiler.java",
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainSectionLifecycle.java",
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainPayloadOwner.java"
        }) {
            String contract = source(relative);
            assertFalse(contract.contains("import net.minecraft"));
            assertFalse(contract.contains("import com.mojang"));
            assertFalse(contract.contains("import org.lwjgl"));
            assertFalse(contract.contains("OpaqueSolidGpuScene"));
        }
    }

    @Test
    void archivedV16WrappersCannotTransformProductionClasses() {
        String mixins = source(
            "src/main/resources/nvidia_dlss.mixins.json"
        );
        for (String name : new String[] {
            "OpaqueSolidGpuSceneLevelRendererMixin",
            "OpaqueSolidGpuSceneRenderGroupMixin",
            "OpaqueSolidGpuSceneRenderSectionMixin",
            "OpaqueSolidGpuSceneUberBufferMixin"
        }) {
            assertFalse(mixins.contains("\"" + name + "\""));
        }
        String policy = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidGpuScenePolicy.java"
        );
        assertTrue(policy.contains("return false;"));
        assertFalse(policy.contains("featureEnabledIfInitialized("));
        assertFalse(
            source(
                "src/main/java/de/morau/nvidiadlss/"
                    + "NvidiaDlssMod.java"
            ).contains("OpaqueSolidGpuScene")
        );
        assertFalse(
            source(
                "src/main/java/de/morau/nvidiadlss/mixin/"
                    + "VulkanDeviceMixin.java"
            ).contains("OpaqueSolidGpuScene")
        );
    }

    private static String methodBody(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, "missing method " + marker);
        int open = source.indexOf('{', start);
        int close = matchingBrace(source, open);
        assertTrue(close > open, "unclosed method " + marker);
        return source.substring(open, close + 1);
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static void assertOrdered(
        String source,
        String... needles
    ) {
        int previous = -1;
        for (String needle : needles) {
            int current = source.indexOf(needle, previous + 1);
            assertTrue(current > previous, "out of order: " + needle);
            previous = current;
        }
    }

    private static String source(String relative) {
        try {
            return Files.readString(
                projectRoot().resolve(relative),
                StandardCharsets.UTF_8
            );
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String mojangSource(String entryName) {
        Path sourceJar = projectRoot()
            .resolve("build")
            .resolve("moddev")
            .resolve("artifacts")
            .resolve(
                "minecraft-patched-26.2.0.23-beta-sources.jar"
            );
        try (ZipFile zip = new ZipFile(sourceJar.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException(
                    "missing Mojang source " + entryName
                );
            }
            return new String(
                zip.getInputStream(entry).readAllBytes(),
                StandardCharsets.UTF_8
            );
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Path projectRoot() {
        return Path.of(
            System.getProperty("blockframe.projectDir", ".")
        );
    }
}
