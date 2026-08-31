package de.morau.blockframe.render.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class OpaqueSolidTerrainSourceContractTest {
    private static final String TERRAIN_SOURCE =
        "src/main/java/de/morau/blockframe/render/terrain/";
    private static final String MIXIN_SOURCE =
        "src/main/java/de/morau/nvidiadlss/mixin/";

    @Test
    void mojangCreatesDrawRecordsEveryFrameFromVisibleSections() {
        String renderer = mojangSource(
            "net/minecraft/client/renderer/LevelRenderer.java"
        );
        String prepare = methodBody(
            renderer,
            "public ChunkSectionsToRender prepareChunkRenders"
        );
        assertOrdered(
            prepare,
            "this.visibleSections.listIterator(0)",
            "new EnumMap<>",
            "new ArrayList<>()",
            "section.getSectionMesh()",
            "getRenderSectionSlice(sectionMesh, layer)",
            "new DynamicUniforms.ChunkSectionInfo(",
            "new Matrix4f(modelViewMatrix)",
            "new RenderPass.Draw<>(",
            "writeChunkSections(",
            "new ChunkSectionsToRender("
        );
        assertTrue(
            prepare.contains(
                "uploader.upload(\"ChunkSection\", "
                    + "sectionUbos[finalUboIndex])"
            )
        );
    }

    @Test
    void mojangMeshIdentityOwnsUploadAllocationAndRetirement() {
        String dispatcher = mojangSource(
            "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher.java"
        );
        assertOrdered(
            methodBody(
                dispatcher,
                "public SectionRenderDispatcher.RenderSection."
                    + "SectionTask.SectionTaskResult doTask"
            ),
            "CompiledSectionMesh compiledSectionMesh = "
                + "new CompiledSectionMesh(",
            "RenderSection.this.addSectionBuffersToUberBuffer("
        );
        assertOrdered(
            methodBody(dispatcher, "checkSectionMesh"),
            "setSectionMesh(compiledSectionMesh)",
            "releaseSectionMesh(oldMesh)"
        );
        String release = methodBody(
            dispatcher,
            "private void releaseSectionMesh"
        );
        assertOrdered(
            release,
            "oldMesh.close()",
            "buffers.vertexBuffer.removeAllocation(oldMesh)",
            "buffers.indexBuffer.removeAllocation(oldMesh)"
        );
        String slices = methodBody(
            dispatcher,
            "getRenderSectionSlice"
        );
        assertTrue(slices.contains("getAllocation(sectionMesh)"));
        assertTrue(slices.contains("getGpuBuffer(vertexSlice)"));
        assertTrue(slices.contains("vertexSlice.getOffsetFromHeap()"));
    }

    @Test
    void currentShaderAbiProhibitsIndirectWithoutShaderChanges() {
        String pass = mojangSource(
            "com/mojang/blaze3d/vulkan/VulkanRenderPass.java"
        );
        String drawMultiple = methodBody(
            pass,
            "drawMultipleIndexed"
        );
        assertOrdered(
            drawMultiple,
            "for (RenderPass.Draw<T> draw : draws)",
            "uniformUploaderConsumer.accept(",
            "this.setIndexBuffer(",
            "this.setVertexBuffer(",
            "this.drawIndexed("
        );
        String drawIndexed = methodBody(pass, "drawIndexed");
        assertOrdered(
            drawIndexed,
            "this.pushDescriptors()",
            "vkCmdDrawIndexed("
        );
        String descriptors = methodBody(
            pass,
            "private void pushDescriptors"
        );
        assertTrue(
            descriptors.contains(
                "vkCmdPushDescriptorSetKHR"
            )
        );
        String vertex = mojangSource(
            "assets/minecraft/shaders/core/terrain.vsh"
        );
        String fragment = mojangSource(
            "assets/minecraft/shaders/core/terrain.fsh"
        );
        assertTrue(vertex.contains("chunksection.glsl"));
        assertTrue(fragment.contains("ChunkVisibility"));
        assertFalse(vertex.contains("gl_DrawID"));
        assertFalse(fragment.contains("gl_DrawID"));
    }

    @Test
    void retainedV1EvidenceUsesTemplatesButKeepsMojangSubmission() {
        String levelMixin = source(
            MIXIN_SOURCE
                + "OpaqueSolidTerrainLevelRendererMixin.java"
        );
        String groupMixin = source(
            MIXIN_SOURCE
                + "OpaqueSolidTerrainRenderGroupMixin.java"
        );
        String cache = source(
            TERRAIN_SOURCE
                + "OpaqueSolidTerrainBatchCache.java"
        );
        assertTrue(
            cache.contains("isCompatibleSolidPipeline(")
        );
        assertTrue(
            cache.contains(
                "vertexFormat == DefaultVertexFormat.BLOCK"
            )
        );
        assertTrue(cache.contains("\"core/terrain\""));
        assertTrue(cache.contains("\"milkshade\""));
        assertTrue(
            cache.contains("\"MilkshadeDynamicLights\"")
        );
        assertTrue(
            cache.contains(
                "hasKnownVulkanTerrainExtension("
            )
        );
        assertTrue(
            cache.contains(
                "== BindGroupLayouts.CHUNK_SECTION"
            )
        );
        assertFalse(
            cache.contains(
                "\"minecraft:pipeline/solid_terrain\".equals"
            )
        );
        assertTrue(
            levelMixin.contains(
                "@WrapMethod(method = \"prepareChunkRenders\")"
            )
        );
        assertTrue(
            levelMixin.contains(
                "return cached != null"
            )
        );
        assertTrue(
            occurrences(
                levelMixin,
                "original.call(modelViewMatrix)"
            ) >= 2
        );
        assertTrue(
            groupMixin.contains(
                "original.call("
            )
        );
        assertFalse(groupMixin.contains("ci.cancel()"));
        assertTrue(
            cache.contains(
                "private static final class SolidDrawTemplate"
            )
        );
        assertTrue(
            cache.contains(
                "new RenderPass.Draw<>("
            )
        );
        assertTrue(
            cache.contains(
                "ChunkSectionLayer.SOLID"
            )
        );
        assertFalse(cache.contains("drawIndexedIndirect("));
        assertFalse(cache.contains("multiDrawIndexed("));
    }

    @Test
    void openGlAndPolicyGatesRunBeforeCacheAllocation() {
        String runtime = source(
            TERRAIN_SOURCE
                + "OpaqueSolidTerrainBatchRuntime.java"
        );
        String levelMixin = source(
            MIXIN_SOURCE
                + "OpaqueSolidTerrainLevelRendererMixin.java"
        );
        String eligible = methodBody(
            runtime,
            "eligibleForPrepare"
        );
        assertTrue(eligible.contains("engineEnabled()"));
        assertTrue(eligible.contains("frameResourcesEnabled()"));
        assertTrue(eligible.contains("safeStartActive()"));
        assertTrue(
            eligible.contains(
                "EngineCapabilities.Backend.VULKAN"
            )
        );
        assertOrdered(
            methodBody(
                levelMixin,
                "blockframe$reuseOpaqueSolidDrawTemplates"
            ),
            "eligibleForPrepare()",
            "cacheOrCreate(null)",
            "OpaqueSolidTerrainBatchRuntime.tryPrepare(",
            "original.call(modelViewMatrix)"
        );
    }

    @Test
    void cacheOwnsOnlyBoundedRamAndNoVulkanOrSchedulerResource() {
        String table = source(
            TERRAIN_SOURCE
                + "PersistentDrawTemplateTable.java"
        );
        String cache = source(
            TERRAIN_SOURCE
                + "OpaqueSolidTerrainBatchCache.java"
        );
        String runtime = source(
            TERRAIN_SOURCE
                + "OpaqueSolidTerrainBatchRuntime.java"
        );
        String combined = table + cache + runtime;
        assertTrue(
            table.contains("DEFAULT_CAPACITY = 16_384")
        );
        assertTrue(
            table.contains(
                "DEFAULT_ACCOUNTED_BYTES = 8L * 1024L * 1024L"
            )
        );
        assertTrue(table.contains("MemoryKind.RAM"));
        assertTrue(table.contains("MemoryCategory.CACHES"));
        assertTrue(table.contains("registerEvictable("));
        assertTrue(table.contains("clearPhysicalStorage()"));
        assertFalse(combined.contains("MemoryKind.VRAM"));
        assertFalse(combined.contains("vkCmd"));
        assertFalse(combined.contains("createBuffer("));
        assertFalse(combined.contains("Executor"));
        assertFalse(combined.contains("ThreadPool"));
        assertFalse(combined.contains("CompletableFuture"));
        assertFalse(combined.contains("FrameBudgetController"));
    }

    @Test
    void submissionStateWalksOnlyCurrentFrameSlots() {
        String table = source(
            TERRAIN_SOURCE
                + "PersistentDrawTemplateTable.java"
        );
        assertTrue(table.contains("private int[] activeSlots;"));
        assertTrue(
            table.contains(
                "this.activeSlots = new int[this.capacity];"
            )
        );
        for (
            String method
                : new String[] {
                    "synchronized boolean beginSolidSubmission",
                    "private void quarantineCurrentFrame",
                    "private void finishFrameState",
                    "private long countSeenState"
                }
        ) {
            String body = methodBody(table, method);
            assertTrue(body.contains("this.activeSlotCount"));
            assertTrue(body.contains("this.activeSlots[index]"));
            assertFalse(body.contains("slot < this.capacity"));
        }
    }

    @Test
    void retainedV1EvidenceHasLifecycleInvalidationButNoProductionCallers() {
        String runtime = source(
            TERRAIN_SOURCE
                + "OpaqueSolidTerrainBatchRuntime.java"
        );
        String blockframeRuntime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String levelMixin = source(
            MIXIN_SOURCE
                + "OpaqueSolidTerrainLevelRendererMixin.java"
        );
        assertFalse(
            blockframeRuntime.contains(
                "OpaqueSolidTerrainBatchRuntime"
                    + ".resourceReloaded();"
            )
        );
        assertFalse(
            blockframeRuntime.contains(
                "OpaqueSolidTerrainBatchRuntime"
                    + ".deviceClosing();"
            )
        );
        assertFalse(
            blockframeRuntime.contains(
                "OpaqueSolidTerrainBatchRuntime"
                    + ".worldUnavailable();"
            )
        );
        assertTrue(
            levelMixin.contains(
                "@Inject(method = \"resize\""
            )
        );
        assertTrue(
            levelMixin.contains(
                "@Inject(method = \"resetLevelRenderData\""
            )
        );
        assertTrue(
            levelMixin.contains(
                "@WrapMethod(method = \"close\")"
            )
        );
        assertTrue(runtime.contains("rendererClosed("));
        assertFalse(
            methodBody(runtime, "tryPrepare").contains(
                "logLifecycleSnapshot("
            )
        );
        assertFalse(
            methodBody(runtime, "recordDrawSubmission").contains(
                "logLifecycleSnapshot("
            )
        );
        assertTrue(
            methodBody(runtime, "resourceReloaded").contains(
                "logLifecycleSnapshot("
            )
        );
        assertTrue(
            methodBody(runtime, "rendererClosed").contains(
                "logLifecycleSnapshot("
            )
        );
        assertTrue(
            methodBody(runtime, "worldUnavailable").contains(
                "\"INVALIDATED_WORLD_UNAVAILABLE\".equals(gateStatus)"
            )
        );
        assertTrue(
            runtime.contains(
                ".LIFECYCLE_INVALIDATION"
            )
        );
    }

    @Test
    void v1MixinsAreNotProductionRegisteredAndEvidenceIsRetained() {
        String mixins = source(
            "src/main/resources/nvidia_dlss.mixins.json"
        );
        String plugin = source(
            MIXIN_SOURCE + "DlssMixinPlugin.java"
        );
        assertFalse(
            mixins.contains(
                "\"OpaqueSolidTerrainLevelRendererMixin\""
            )
        );
        assertFalse(
            mixins.contains(
                "\"OpaqueSolidTerrainRenderGroupMixin\""
            )
        );
        assertTrue(
            Files.isRegularFile(
                projectRoot().resolve(
                    MIXIN_SOURCE
                        + "OpaqueSolidTerrainLevelRendererMixin.java"
                )
            )
        );
        assertTrue(
            Files.isRegularFile(
                projectRoot().resolve(
                    MIXIN_SOURCE
                        + "OpaqueSolidTerrainRenderGroupMixin.java"
                )
            )
        );
        assertTrue(
            plugin.contains(
                "SodiumCompatibility.mixinAllowed("
            )
        );
    }

    private static String mojangSource(String entryName) {
        Path artifacts = projectRoot()
            .resolve("build")
            .resolve("moddev")
            .resolve("artifacts");
        Path sourceJar;
        try (var paths = Files.list(artifacts)) {
            sourceJar = paths
                .filter(
                    path ->
                        path.getFileName()
                            .toString()
                            .startsWith("minecraft-patched-")
                            && path.getFileName()
                                .toString()
                                .endsWith("-sources.jar")
                )
                .findFirst()
                .orElseThrow();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
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

    private static String methodBody(
        String source,
        String method
    ) {
        int marker = source.indexOf(method + "(");
        assertTrue(marker >= 0, "missing method " + method);
        int open = source.indexOf('{', marker);
        int close = matchingBrace(source, open);
        assertTrue(close > open, "unclosed method " + method);
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
            int current = source.indexOf(
                needle,
                previous + 1
            );
            assertTrue(
                current > previous,
                "out of order: " + needle
            );
            previous = current;
        }
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while (
            (offset = source.indexOf(needle, offset)) >= 0
        ) {
            count++;
            offset += needle.length();
        }
        return count;
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

    private static Path projectRoot() {
        return Path.of(
            System.getProperty("blockframe.projectDir", ".")
        );
    }
}
