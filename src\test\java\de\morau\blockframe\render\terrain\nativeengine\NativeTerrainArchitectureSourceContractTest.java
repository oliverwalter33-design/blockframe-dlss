package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class NativeTerrainArchitectureSourceContractTest {
    private static final String MOJANG_SOURCES_SHA256 =
        "6836961dbbdc1a25b91a1a8b0e8cc748"
            + "eea29fad8e3ffe324ef7c4c3de038ae6";
    private static final String MOJANG_NAMED_SHA256 =
        "44880a713fabddf1ca43e161db30cd0c9"
            + "1a56733fd0a17d7aa20e762d293405a";

    @Test
    void exactDocumentedMinecraftArtifactsAreTheSourceContract() {
        assertEquals(
            MOJANG_SOURCES_SHA256,
            sha256(mojangSourcesJar())
        );
        assertEquals(
            MOJANG_NAMED_SHA256,
            sha256(
                projectRoot()
                    .resolve("build")
                    .resolve("moddev")
                    .resolve("artifacts")
                    .resolve(
                        "minecraft-patched-26.2.0.23-beta.jar"
                    )
            )
        );
    }

    @Test
    void exactMinecraftPayloadLifetimeLeavesLiteralPublicationGateBlocked() {
        String dispatcher = mojangSource(
            "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher.java"
        );
        String compile = methodBody(
            dispatcher,
            "public SectionRenderDispatcher.RenderSection.SectionTask."
                + "SectionTaskResult doTask"
        );
        assertOrdered(
            compile,
            "addSectionBuffersToUberBuffer(",
            "meshData.vertexBuffer()",
            "meshData.close()"
        );
        String add = methodBody(
            dispatcher,
            "private boolean addSectionBuffersToUberBuffer"
        );
        assertTrue(add.contains("chunkUberBuffers.get(layer)"));
        assertTrue(
            add.contains("sectionBuffers.vertexBuffer.addAllocation(")
        );
        assertTrue(
            add.contains("sectionBuffers.indexBuffer.addAllocation(")
        );
        assertTrue(add.contains("this.vertexBufferUploadCallback("));
        assertTrue(add.contains("this.indexBufferUploadCallback("));
        assertTrue(add.contains("key.setIndexBufferUploaded(layer)"));
        assertOrdered(
            methodBody(dispatcher, "private void checkSectionMesh"),
            "compiledSectionMesh.isIndexBufferUploaded(layer)",
            "compiledSectionMesh.isVertexBufferUploaded(layer)",
            "this.setSectionMesh(compiledSectionMesh)",
            "this.releaseSectionMesh(oldMesh)"
        );
        assertOrdered(
            methodBody(dispatcher, "public void reset"),
            "this.cancelTasks()",
            "this.sectionMesh.getAndSet(",
            "SectionRenderDispatcher.this.copyLock.lock()",
            "this.releaseSectionMesh(mesh)"
        );

        String uber = mojangSource(
            "com/mojang/blaze3d/vertex/UberGpuBuffer.java"
        );
        String allocation = methodBody(
            uber,
            "public <U extends T> boolean addAllocation"
        );
        assertTrue(allocation.contains("this.stagingBuffer.tryAppend("));
    }

    @Test
    void finalLayerPayloadCannotProveBlockOnlyProvenance() {
        String compiler = mojangSource(
            "net/minecraft/client/renderer/chunk/SectionCompiler.java"
        );
        assertOrdered(
            compiler,
            "Map<ChunkSectionLayer, BufferBuilder> startedLayers",
            "FluidRenderer.Output fluidOutput = layerx -> "
                + "this.getOrBeginLayer(startedLayers, builders, layerx)",
            "FluidState fluidState = blockState.getFluidState()",
            "fluidRenderer.tesselate(",
            "blockRenderer.tesselateBlock(",
            "ClientHooks.addAdditionalGeometry(",
            "MeshData mesh = entry.getValue().build()",
            "results.renderedLayers.put(layer, mesh)"
        );

        String compiled = mojangSource(
            "net/minecraft/client/renderer/chunk/CompiledSectionMesh.java"
        );
        assertFalse(compiled.contains("FluidState"));
        assertFalse(compiled.contains("AdditionalSectionRenderer"));
        assertFalse(compiled.contains("ContentProvenance"));
    }

    @Test
    void normalMojangOpaquePreparationIsTheLinearWorkToReplace() {
        String level = mojangSource(
            "net/minecraft/client/renderer/LevelRenderer.java"
        );
        String prepare = methodBody(
            level,
            "public ChunkSectionsToRender prepareChunkRenders"
        );
        assertTrue(prepare.contains("this.visibleSections"));
        assertTrue(
            prepare.contains("for (ChunkSectionLayer layer")
        );
        assertTrue(prepare.contains("new RenderPass.Draw<>("));

        String groups = mojangSource(
            "net/minecraft/client/renderer/chunk/"
                + "ChunkSectionLayerGroup.java"
        );
        assertTrue(
            groups.contains(
                "OPAQUE(ChunkSectionLayer.SOLID, "
                    + "ChunkSectionLayer.CUTOUT)"
            )
        );
    }

    @Test
    void archivedGpuSceneDoesNotTransformTheTerrainWarmPath() {
        String mixinConfiguration = localSource(
            "src/main/resources/nvidia_dlss.mixins.json"
        );
        for (String archivedMixin : new String[] {
            "OpaqueSolidGpuSceneUberBufferMixin",
            "OpaqueSolidGpuSceneLevelRendererMixin",
            "OpaqueSolidGpuSceneRenderSectionMixin",
            "OpaqueSolidGpuSceneRenderGroupMixin"
        }) {
            assertFalse(
                mixinConfiguration.contains("\"" + archivedMixin + "\""),
                archivedMixin + " must remain unregistered"
            );
        }

        String policy = localSource(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidGpuScenePolicy.java"
        );
        assertTrue(policy.contains("return false;"));
        assertFalse(policy.contains("featureEnabledIfInitialized("));
        assertFalse(policy.contains("OpaqueSolidGpuSceneRuntime"));

        String uber = localSource(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "OpaqueSolidGpuSceneUberBufferMixin.java"
        );
        assertTrue(uber.contains("@WrapMethod"));
        assertTrue(uber.contains("Operation<"));
        assertTrue(uber.contains("original.call("));

        String bootstrap = localSource(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        assertFalse(
            bootstrap.contains("OpaqueSolidIndirectNegotiator")
        );
        assertFalse(
            bootstrap.contains("OpaqueSolidIndirectFeatureState")
        );
    }

    @Test
    void permanentContractsOwnNoMinecraftOrVulkanObject() {
        String abi = localSource(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/TerrainMeshProducerABI.java"
        );
        String ownership = localSource(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/"
                + "TerrainGeometryOwnershipTransaction.java"
        );
        String boundary = localSource(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/TerrainSubmissionBoundary.java"
        );
        for (String source : new String[] {abi, ownership, boundary}) {
            assertFalse(source.contains("net.minecraft"));
            assertFalse(source.contains("com.mojang"));
            assertFalse(source.contains("org.lwjgl"));
            assertFalse(source.contains("OpaqueSolidGpuScene"));
            assertFalse(source.contains("import java.nio.ByteBuffer"));
            assertFalse(source.contains("Executor"));
            assertFalse(source.contains("ThreadPool"));
        }
        assertTrue(abi.contains("CompatibilityProof"));
        assertTrue(
            abi.contains(
                "Structural descriptor validity alone can never"
            )
        );
        assertTrue(abi.contains("ContentProvenance"));
        assertTrue(ownership.contains("PayloadOwnershipPermit"));
        assertFalse(
            ownership.contains("class UploadSuppressionPermit")
        );
        assertTrue(
            ownership.contains(
                "is not by itself production authority to suppress"
            )
        );
        assertTrue(ownership.contains("NativeDrawPermit"));
        assertTrue(
            ownership.contains("MOJANG_NEXT_FRAME_NO_REPLAY")
        );
        assertTrue(
            ownership.contains(
                "MOJANG_ENCODE_NATIVE_SLICE_BEFORE_SUBMISSION"
            )
        );
        assertTrue(
            ownership.contains(
                "confirmEncodedSliceFallbackSubmitted("
            )
        );
        assertTrue(
            ownership.contains(
                "minimumFallbackSubmissionSerial"
            )
        );
        assertFalse(ownership.contains("beginSubmission("));
        assertTrue(
            boundary.contains(
                "One render-thread-owned no-replay boundary"
            )
        );
        assertTrue(boundary.contains("beginNativeSubmission("));
        assertTrue(
            boundary.contains("NEXT_FRAME_ONLY_NO_REPLAY")
        );
    }

    @Test
    void canonicalArchitectureRecordsExclusiveFactoryBlockerWithoutClaims() {
        String architecture = localSource(
            "NATIVE_TERRAIN_ARCHITECTURE.md"
        );
        assertTrue(
            architecture.contains(
                "Status: "
                    + "`EXCLUSIVE_NATIVE_WORLD_FACTORY_BLOCKED_BY_"
                    + "WORLD_ROUTING_AND_TYPED_FRAME_OUTPUT_OWNERSHIP`"
            )
        );
        assertTrue(
            architecture.contains(
                "`PHASE_2A_1E_GPU_VISIBILITY_COMPACTION_V1` is cancelled"
            )
        );
        assertTrue(architecture.contains("TerrainMeshProducerABI"));
        assertTrue(architecture.contains("TerrainGeometryOwner"));
        assertTrue(architecture.contains("PersistentTerrainGpuScene"));
        assertTrue(architecture.contains("GpuTerrainVisibility"));
        assertTrue(
            architecture.contains("GpuTerrainCommandGeneration")
        );
        assertTrue(architecture.contains("TerrainMaterialSystem"));
        assertTrue(architecture.contains("TerrainSubmissionOwner"));
        assertTrue(
            architecture.contains(
                "That boundary is not used by the new backend "
                    + "for productive ownership"
            )
        );
        assertTrue(
            architecture.contains(
                "`MinecraftTerrainAssetCensusAdapter`, "
                    + "`MinecraftTerrainSectionSnapshot`, "
                    + "`MinecraftTerrainModelAdapter` and "
                    + "`BlockFrameSectionCompiler` operate before "
                    + "any mixed Mojang payload"
            )
        );
        assertTrue(
            architecture.contains(
                "`NativeTerrainGeometryOwner` owns budgeted "
                    + "device-local pages and staging"
            )
        );
        assertTrue(
            architecture.contains(
                "`NativeTerrainDeviceCapabilityNegotiator` is "
                    + "independent of V16"
            )
        );
        assertTrue(architecture.contains("`Operation.call(Object...)`"));
        assertTrue(architecture.contains("normal terrain warm path"));
        assertTrue(
            architecture.contains(
                "current production preflight attests exclusive\n"
                    + "world routing, stored frame outputs/typed "
                    + "captures and the controlled fixture\n"
                    + "as unavailable"
            )
        );
        assertTrue(
            architecture.contains(
                "`ExclusiveNativeWorldResourceFactory` is the one "
                    + "transaction owner"
            )
        );
        assertTrue(
            architecture.contains(
                "do not manufacture a concrete native\n"
                    + "`LevelRenderer` owner"
            )
        );
        assertTrue(
            architecture.contains(
                "`NativeTerrainFrameOutputAbi.VERSION == 1`"
            )
        );
        assertTrue(
            architecture.contains(
                "`TerrainMeshProducerABI.VERSION == 2`"
            )
        );
        assertTrue(
            architecture.contains(
                "`RENDERER_C_PERSISTENT_GPU_SCENE_FRUSTUM_INDIRECT_V1`"
            )
        );
        assertTrue(
            architecture.contains(
                "Vulkan title-only real census/compile/upload/"
                    + "publish/retire/close:\n  `PASSED`"
            )
        );
        assertTrue(architecture.contains("image parity: `NOT_RUN`"));
        assertTrue(architecture.contains("Sodium comparison: `NOT_RUN`"));
        assertTrue(
            architecture.contains(
                "Minecraft FPS/frame-time comparison: `NOT_RUN`"
            )
        );
        assertFalse(architecture.contains("Status: `NATIVE_TERRAIN_GO`"));
        assertFalse(
            architecture.contains(
                "Status: "
                    + "`EXCLUSIVE_NATIVE_WORLD_FACTORY_ACTIVE_"
                    + "SOLID_CUTOUT_FIXTURE_PASSED`"
            )
        );
    }

    private static String mojangSource(String entryName) {
        Path sourceJar = mojangSourcesJar();
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

    private static Path mojangSourcesJar() {
        return projectRoot()
            .resolve("build")
            .resolve("moddev")
            .resolve("artifacts")
            .resolve(
                "minecraft-patched-26.2.0.23-beta-sources.jar"
            );
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
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
