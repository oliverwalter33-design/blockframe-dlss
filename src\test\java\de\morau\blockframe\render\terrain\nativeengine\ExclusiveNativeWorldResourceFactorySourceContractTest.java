package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact Minecraft-26.2 owner boundary and the remaining reasons why
 * the exclusive native backend must not suppress Mojang yet.
 */
class ExclusiveNativeWorldResourceFactorySourceContractTest {
    @Test
    void firstInvalidateIsPostWorldEntryAndPrecedesAllMojangTerrainOwners() {
        String extractor = mojangSource(
            "net/minecraft/client/renderer/extract/"
                + "LevelExtractor.java"
        );
        assertOrdered(
            methodBody(extractor, "public void setLevel("),
            "this.level = level",
            "this.allChanged()",
            "this.shouldResetLevelRenderData = true"
        );
        assertOrdered(
            methodBody(
                extractor,
                "public void extract("
            ),
            "this.levelRenderer.resetLevelRenderData()",
            "this.levelRenderer.invalidateCompiledGeometry(",
            "this.shouldInvalidateCompiledGeometry = false"
        );

        String renderer = mojangSource(
            "net/minecraft/client/renderer/LevelRenderer.java"
        );
        assertOrdered(
            methodBody(renderer, "invalidateCompiledGeometry("),
            "new SectionCompiler(",
            "new SectionRenderDispatcher(",
            "new ViewArea(",
            "this.sectionOcclusionGraph().waitAndReset(this.viewArea)"
        );

        String mixin = local(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "LevelRendererMixin.java"
        );
        String boundary = methodBody(
            mixin,
            "blockframe$createSelectedTerrainWorldResources("
        );
        assertOrdered(
            boundary,
            "beginReferenceWorldResourceCreation()",
            "original.call(level, options, camera, blockColors)"
        );
    }

    @Test
    void suppressingOnlyMojangFactoryWouldLeaveRequiredOwnersUnrouted() {
        String renderer = mojangSource(
            "net/minecraft/client/renderer/LevelRenderer.java"
        );
        String reposition = methodBody(
            renderer,
            "private void repositionCamera("
        );
        assertTrue(
            reposition.contains(
                "this.viewArea.repositionCamera(cameraSectionPos)"
            )
        );
        assertTrue(
            reposition.contains(
                "this.sectionRenderDispatcher.setCameraPosition("
            )
        );

        String render = methodBody(renderer, "public void render(");
        assertOrdered(
            render,
            "this.repositionCamera(cameraState)",
            "this.prepareChunkRenders(",
            "this.compileSections(cameraState)",
            "this.sectionRenderDispatcher.uploadTerrainBuffersToGpu()",
            "this.sectionOcclusionGraph.update("
        );

        String prepare = methodBody(
            renderer,
            "public ChunkSectionsToRender prepareChunkRenders("
        );
        assertTrue(prepare.contains("this.visibleSections.listIterator("));
        assertTrue(
            prepare.contains(
                "for (ChunkSectionLayer layer "
                    + ": ChunkSectionLayer.values())"
            )
        );
        assertTrue(prepare.contains("new RenderPass.Draw<>("));

        String extractor = mojangSource(
            "net/minecraft/client/renderer/extract/"
                + "LevelExtractor.java"
        );
        String frustum = methodBody(
            extractor,
            "private void applyFrustum("
        );
        assertTrue(frustum.contains(".sectionOcclusionGraph()"));
        assertTrue(frustum.contains(".addSectionsInFrustum("));
        assertTrue(
            methodBody(
                extractor,
                "private void setSectionDirty("
            ).contains("this.sectionUpdateTracker.setDirty(")
        );
    }

    @Test
    void currentMojangPassAndNativeShaderCannotPublishCompleteOutputAbi() {
        String groups = mojangSource(
            "net/minecraft/client/renderer/chunk/"
                + "ChunkSectionsToRender.java"
        );
        String renderGroup = methodBody(groups, "public void renderGroup(");
        assertOrdered(
            renderGroup,
            ".createRenderPass(",
            "renderTarget.getColorTextureView()",
            "renderTarget.getDepthTextureView()",
            "renderPass.drawMultipleIndexed("
        );
        assertFalse(renderGroup.contains("RenderPassDescriptor"));
        assertFalse(renderGroup.contains("Motion"));
        assertFalse(renderGroup.contains("Normal"));
        assertFalse(renderGroup.contains("Surface"));

        String fragment = local(
            "src/main/resources/assets/voxellift/shaders/core/"
                + "native_terrain_v2.fsh"
        );
        assertTrue(fragment.contains("out vec4 fragColor;"));
        assertFalse(fragment.contains("layout(location = 1)"));
        assertFalse(fragment.contains("out vec2 fragMotion"));
        assertFalse(fragment.contains("out vec4 fragWorldNormal"));
        assertFalse(fragment.contains("out uint fragSurface"));
        assertFalse(fragment.contains("ChunkVisibility"));

        String capture = local(
            "src/main/java/de/morau/nvidiadlss/"
                + "DlssDebugCapture.java"
        );
        assertTrue(
            capture.contains(
                "erwartet RGBA8"
            )
        );
        assertFalse(capture.contains("R32_UINT"));
        assertFalse(capture.contains("RGBA16_SNORM"));

        String foundation = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainBackendFoundation.java"
        );
        String preflight = methodBody(
            foundation,
            "private static NativeTerrainBackendSelector.Preflight"
        );
        assertTrue(
            preflight.contains(
                "ExclusiveWorldFactoryAttestation("
            )
        );
        assertTrue(
            preflight.contains("FrameOutputAttestation(")
        );
        assertTrue(
            preflight.contains("ControlledFixtureAttestation(")
        );
        assertTrue(
            preflight.contains(
                "\"exclusive-world-routing-not-connected\""
            )
        );
        assertTrue(
                preflight.contains(
                    "\"native-mrt-motion-normal-surface-history-and-typed-\""
                )
        );
        assertTrue(
            preflight.contains(
                "\"reference-capture-not-connected\""
            )
        );
        assertTrue(
            preflight.contains(
                "\"controlled-native-fixture-not-connected\""
            )
        );
    }

    @Test
    void devEvidenceCountersObserveRealOwnerEntryPointsAndStayOptIn() {
        assertTrue(
            local(
                "src/main/java/de/morau/blockframe/render/terrain/"
                    + "nativeengine/BlockFrameSectionCompiler.java"
            ).contains(
                "blockFrameSectionCompiled("
            )
        );
        assertTrue(
            local(
                "src/main/java/de/morau/blockframe/render/terrain/"
                    + "nativeengine/NativeTerrainGeometryOwner.java"
            ).contains(
                "blockFramePayloadPublished("
            )
        );
        assertTrue(
            local(
                "src/main/java/de/morau/blockframe/render/terrain/"
                    + "nativeengine/NativeTerrainGeometryOwner.java"
            ).contains(
                "blockFrameGpuUploaded("
            )
        );
        assertTrue(
            local(
                "src/main/java/de/morau/blockframe/render/terrain/"
                    + "nativeengine/NativeTerrainGpuScene.java"
            ).contains(
                "blockFrameSceneEntriesPublished("
            )
        );
        assertTrue(
            local(
                "src/main/java/de/morau/blockframe/render/terrain/"
                    + "nativeengine/"
                    + "NativeTerrainGpuSceneVulkanResources.java"
            ).contains(
                "blockFrameComputeCullEncoded("
            )
        );
        assertTrue(
            local(
                "src/main/java/de/morau/blockframe/render/terrain/"
                    + "nativeengine/"
                    + "NativeTerrainSubmissionOwner.java"
            ).contains(
                "blockFrameIndirectSubmissionEncoded("
            )
        );

        String plugin = local(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "DlssMixinPlugin.java"
        );
        assertTrue(
            plugin.contains(
                "blockframe.nativeTerrain.exclusiveFixtureEvidence"
            )
        );
        assertTrue(
            plugin.contains(
                "NativeTerrainOpaqueSubmissionEvidenceMixin"
            )
        );
        String opaqueEvidence = local(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "NativeTerrainOpaqueSubmissionEvidenceMixin.java"
        );
        assertTrue(
            opaqueEvidence.contains(
                "group == ChunkSectionLayerGroup.OPAQUE"
            )
        );
        assertTrue(
            opaqueEvidence.contains("mojangOpaqueSubmitted()")
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

    private static String local(String relative) {
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
