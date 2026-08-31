package de.morau.blockframe.render.terrain.gpuscene;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class OpaqueSolidGpuSceneOwnerSourceContractTest {
    @Test
    void uploadPublishesExactRangeBeforeSectionMeshCallback() {
        String uber = mojangSource(
            "com/mojang/blaze3d/vertex/UberGpuBuffer.java"
        );
        String upload = methodBody(
            uber,
            "public boolean uploadStagedAllocations"
        );
        assertOrdered(
            upload,
            "this.freeAllocation(key)",
            "allocation = node.getFirst().allocate(",
            "uploader.copyTo(",
            "this.allocationMap.put(",
            "runCallbackUnchecked("
        );
        String remove = methodBody(
            uber,
            "public void removeAllocation"
        );
        assertOrdered(
            remove,
            "this.skippedStagedAllocations.add(",
            "this.freeAllocation("
        );
        String free = methodBody(uber, "private void freeAllocation");
        assertOrdered(
            free,
            "this.allocationMap.remove(",
            "node.getFirst().free("
        );
        assertTrue(
            methodBody(uber, "public GpuBuffer getGpuBuffer")
                .contains("allocation.getHeap()")
        );
    }

    @Test
    void meshReplacementAndResetHaveExactPreFreeBoundaries() {
        String dispatcher = mojangSource(
            "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher.java"
        );
        assertOrdered(
            methodBody(dispatcher, "checkSectionMesh"),
            "this.setSectionMesh(compiledSectionMesh)",
            "this.releaseSectionMesh(oldMesh)"
        );
        assertOrdered(
            methodBody(dispatcher, "private void releaseSectionMesh"),
            "oldMesh.close()",
            "buffers.vertexBuffer.removeAllocation(oldMesh)",
            "buffers.indexBuffer.removeAllocation(oldMesh)"
        );
        assertOrdered(
            methodBody(dispatcher, "public void reset"),
            "this.sectionMesh.getAndSet(",
            "this.releaseSectionMesh(mesh)"
        );
        assertOrdered(
            methodBody(
                dispatcher,
                "private SectionMesh setSectionMesh"
            ),
            "this.sectionMesh.getAndSet(sectionMesh)",
            "this.onSectionMeshUpdate.accept(this)"
        );
    }

    @Test
    void physicalBuffersRetireOnlyAfterConfirmedSubmitCompletion() {
        String buffer = mojangSource(
            "com/mojang/blaze3d/vulkan/VulkanGpuBuffer.java"
        );
        assertOrdered(
            methodBody(buffer, "public void close"),
            "this.closed = true",
            "this.device.createCommandEncoder().queueForDestroy(this)"
        );
        String encoder = mojangSource(
            "com/mojang/blaze3d/vulkan/VulkanCommandEncoder.java"
        );
        String submit = methodBody(encoder, "public void submit");
        assertOrdered(
            submit,
            "this.currentSubmitIndex++",
            "this.awaitSubmitCompletion(this.currentSubmitIndex - 2L",
            "this.destroyQueue.rotate()"
        );
        String queue = mojangSource(
            "com/mojang/blaze3d/vulkan/DestructionQueue.java"
        );
        assertOrdered(
            methodBody(queue, "public boolean rotate"),
            "this.currentDestructionQueueIndex++",
            "this.destroyCallback.begin(",
            "currentQueue.forEach(this.destroyCallback::destroy)"
        );
    }

    @Test
    void sequentialIndexOwnerInvalidatesBeforePhysicalReplacement() {
        String renderSystem = mojangSource(
            "com/mojang/blaze3d/systems/RenderSystem.java"
        );
        String ensure = methodBody(
            renderSystem,
            "private void ensureStorage"
        );
        assertOrdered(
            ensure,
            "if (this.buffer != null)",
            "this.buffer.close()",
            "this.buffer = RenderSystem.getDevice().createBuffer("
        );
        assertTrue(
            ensure.contains("this.type = type")
        );
    }

    @Test
    void currentShaderAbiRequiresAnExplicitIndirectVariant() {
        String vertex = mojangSource(
            "assets/minecraft/shaders/core/terrain.vsh"
        );
        String fragment = mojangSource(
            "assets/minecraft/shaders/core/terrain.fsh"
        );
        String renderPass = mojangSource(
            "com/mojang/blaze3d/vulkan/VulkanRenderPass.java"
        );
        assertTrue(vertex.contains("ChunkPosition"));
        assertTrue(fragment.contains("ChunkVisibility"));
        assertFalse(vertex.contains("gl_DrawID"));
        assertFalse(vertex.contains("gl_BaseInstance"));
        assertOrdered(
            methodBody(renderPass, "drawMultipleIndexed"),
            "for (RenderPass.Draw<T> draw : draws)",
            "uniformUploaderConsumer.accept(",
            "this.setVertexBuffer(",
            "this.drawIndexed("
        );
        assertFalse(
            renderPass.contains("vkCmdDrawIndexedIndirectCount")
        );
    }

    @Test
    void indirectCountMustBeSeparatelyQueriedAndEnabled() {
        String backend = mojangSource(
            "com/mojang/blaze3d/vulkan/VulkanBackend.java"
        );
        assertTrue(backend.contains("\"multiDrawIndirect\""));
        assertTrue(backend.contains("\"shaderDrawParameters\""));
        assertFalse(backend.contains("\"drawIndirectCount\""));
        String deviceFeatures = mojangSource(
            "com/mojang/blaze3d/systems/DeviceFeatures.java"
        );
        assertFalse(deviceFeatures.contains("drawIndirectCount"));
    }

    @Test
    void noGoV1IsAbsentFromProductionMixinAndRuntimePaths() {
        String mixins = localSource(
            "src/main/resources/nvidia_dlss.mixins.json"
        );
        String runtime = localSource(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String overlay = localSource(
            "src/main/java/de/morau/nvidiadlss/"
                + "DlssDebugOverlay.java"
        );
        assertFalse(
            mixins.contains("OpaqueSolidTerrainLevelRendererMixin")
        );
        assertFalse(
            mixins.contains("OpaqueSolidTerrainRenderGroupMixin")
        );
        assertFalse(
            runtime.contains("OpaqueSolidTerrainBatchRuntime")
        );
        assertFalse(
            overlay.contains("OpaqueSolidTerrainBatchRuntime")
        );
        assertTrue(
            overlay.contains(
                "Opaque-solid templates V1: NO_GO_DISABLED"
            )
        );
    }

    @Test
    void generationLedgerExposesNoEnumerationOrScanApi() {
        String ledger = localSource(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidOwnerGenerationLedger.java"
        );
        assertFalse(ledger.contains("entrySet()"));
        assertFalse(ledger.contains("values()"));
        assertFalse(ledger.contains("forEach("));
        assertFalse(ledger.contains("allSlots"));
        assertTrue(ledger.contains("invalidateMeshBeforeReplace("));
        assertTrue(ledger.contains("invalidateRangeBeforeFree("));
        assertTrue(ledger.contains("invalidateBufferBeforeClose("));
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

    private static String methodBody(String source, String method) {
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
            int current = source.indexOf(needle, previous + 1);
            assertTrue(current > previous, "out of order: " + needle);
            previous = current;
        }
    }

    private static String localSource(String relative) {
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
