package de.morau.blockframe.render.terrain.gpuscene;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.EngineCapabilities;
import de.morau.blockframe.vulkan.OpaqueSolidIndirectFeatureState;
import de.morau.nvidiadlss.NvidiaDlssMod;
import de.morau.nvidiadlss.mixin.RenderPassAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-level routing for the exact opaque-solid GPU-scene slice.
 *
 * <p>Publication comes only from section-mesh and UberGpuBuffer owner
 * mutation hooks. Frame preparation consumes Mojang's final visible section
 * list, touches no non-visible scene entry, and falls back before returning a
 * marker if any owned section is incomplete.</p>
 */
public final class OpaqueSolidGpuSceneRuntime {
    private static final Logger LOGGER =
        LoggerFactory.getLogger("blockframe-solid-gpu-scene");
    private static final String VANILLA_TERRAIN_VSH =
        "1201187c8a9fa4e649f4ea31a70e500bf2bfff32a242152a82a378531a5625ef";
    private static final String VANILLA_TERRAIN_FSH =
        "a924d89492315e77205f5a0d803eb8de7d5bb9d34e8ed7ca4b6081f20f47d9d4";
    private static final int KNOWN_BUFFER_CAPACITY = 64;
    private static final int INDEX_TYPE_SHORT = 1;
    private static final int INDEX_TYPE_INT = 2;
    private static final long SEQUENTIAL_INDEX_GENERATION = 1L;
    private static final long SEQUENTIAL_RANGE_GENERATION = 1L;

    private static final OpaqueSolidOwnerGenerationLedger LEDGER =
        new OpaqueSolidOwnerGenerationLedger();
    private static final OpaqueSolidGpuSceneModel MODEL =
        new OpaqueSolidGpuSceneModel();
    private static final Map<Object, RangeRecord> VERTEX_RANGES =
        new IdentityHashMap<>();
    private static final Map<Object, RangeRecord> INDEX_RANGES =
        new IdentityHashMap<>();
    private static final Map<Object, Object> MESH_OWNERS =
        new IdentityHashMap<>();
    private static final Map<Object, Object> CURRENT_MESHES =
        new IdentityHashMap<>();
    private static final Object[] KNOWN_BUFFERS =
        new Object[KNOWN_BUFFER_CAPACITY];
    private static final long[] KNOWN_BUFFER_HANDLES =
        new long[KNOWN_BUFFER_CAPACITY];
    private static final long[] KNOWN_BUFFER_GENERATIONS =
        new long[KNOWN_BUFFER_CAPACITY];

    private static int knownBufferCount;
    private static long rendererGeneration = 1L;
    private static long worldGeneration = 1L;
    private static Object rendererIdentity;
    private static Object worldIdentity;
    private static VulkanDevice device;
    private static OpaqueSolidGpuSceneDeviceResources resources;
    private static ChunkSectionsToRender preparedMarker;
    private static SectionRenderDispatcher preparedDispatcher;
    private static List<
        SectionRenderDispatcher.RenderSection
    > preparedVisibleSections;
    private static final Matrix4f PREPARED_MODEL_VIEW = new Matrix4f();
    private static int preparedMaximumSequentialIndices;
    private static boolean shaderAllowlisted;
    private static boolean ownerContractFaulted;
    private static String shaderReason = "resource-contract-not-checked";
    private static String gateStatus = "NOT_ATTEMPTED";
    private static long fallbackFrames;
    private static long preActivationFallbackFrames;
    private static long runtimeFallbackFrames;
    private static long indirectCalls;
    private static long indirectDrawCapacity;
    private static long ownerPublications;
    private static long ownerInvalidations;
    private static long preSubmissionFailures;
    private static long postSubmissionFailures;
    private static long sameFrameMojangFallbacks;
    private static boolean indirectSubmittedInOwnerGeneration;
    private static OpaqueSolidGpuSceneAuditWindow audit;
    private static long auditPrepareStarted;
    private static long auditUploadedBytesAtFrameStart;
    private static int auditEligibleRecords;

    private OpaqueSolidGpuSceneRuntime() {
    }

    static void recordAuditStage(int stage, long nanos) {
        OpaqueSolidGpuSceneAuditWindow current = audit;
        if (current != null) {
            current.record(stage, nanos);
        }
    }

    static boolean auditActive() {
        return audit != null;
    }

    static void recordAuditBarriers(long count) {
        OpaqueSolidGpuSceneAuditWindow current = audit;
        if (current != null) {
            current.recordBarriers(count);
        }
    }

    /**
     * Fail-open boundary used by owner mixins. An owner-observation failure
     * must never prevent Mojang's mutation or upload callback from running.
     */
    public static synchronized void ownerHookFailed(
        String hook,
        Throwable error
    ) {
        ownerContractFaulted = true;
        gateStatus =
            "MOJANG_ONLY_OWNER_HOOK_FAILURE:"
                + (hook == null ? "unknown" : hook);
        if (preparedMarker == null) {
            try {
                invalidateAllOwnerState("owner-hook-failure");
            } catch (Throwable ignored) {
                VERTEX_RANGES.clear();
                INDEX_RANGES.clear();
                MESH_OWNERS.clear();
                CURRENT_MESHES.clear();
            }
            gateStatus =
                "MOJANG_ONLY_OWNER_HOOK_FAILURE:"
                    + (hook == null ? "unknown" : hook);
        }
        try {
            LOGGER.warn(
                "Opaque-solid GPU-scene owner hook failed open at {}; "
                    + "Mojang ownership remains authoritative",
                hook,
                error
            );
        } catch (Throwable ignored) {
            // Failure reporting must not alter Mojang's owner transition.
        }
    }

    public static synchronized void rendererCreated(Object renderer) {
        if (renderer == null || rendererIdentity == renderer) {
            return;
        }
        invalidateAllOwnerState("renderer-created");
        rendererIdentity = renderer;
        rendererGeneration = increment(rendererGeneration);
    }

    public static synchronized void rendererReset(Object renderer) {
        if (renderer != null && rendererIdentity == renderer) {
            logLifecycleSnapshot("renderer-reset");
            invalidateAllOwnerState("renderer-reset");
            rendererGeneration = increment(rendererGeneration);
        }
    }

    public static synchronized void rendererClosed(Object renderer) {
        if (renderer != null && rendererIdentity == renderer) {
            logLifecycleSnapshot("renderer-close");
            invalidateAllOwnerState("renderer-close");
            rendererIdentity = null;
            rendererGeneration = increment(rendererGeneration);
        }
    }

    public static synchronized void worldUnavailable() {
        if (worldIdentity == null) {
            return;
        }
        logLifecycleSnapshot("world-unavailable");
        invalidateAllOwnerState("world-unavailable");
        worldIdentity = null;
        worldGeneration = increment(worldGeneration);
    }

    public static synchronized void resourceLoadFinished() {
        logLifecycleSnapshot("resource-reload");
        preparedMarker = null;
        preparedDispatcher = null;
        preparedVisibleSections = null;
        preparedMaximumSequentialIndices = 0;
        shaderAllowlisted = verifyVanillaTerrainShaderSources();
        if (!shaderAllowlisted) {
            invalidateAllOwnerState("resource-shader-abi-changed");
        }
        shaderReason = shaderAllowlisted
            ? "vanilla-terrain-shader-hashes-allowlisted"
            : "terrain-shader-source-not-allowlisted";
        gateStatus = shaderAllowlisted
            ? "RESOURCE_CONTRACT_READY"
            : "MOJANG_ONLY_UNKNOWN_SHADER";
    }

    public static synchronized void deviceConnected(
        VulkanDevice connected
    ) {
        device = connected;
        ownerContractFaulted = false;
        boolean resolved =
            connected != null
                && connected.vkDevice().getCapabilities()
                        .vkCmdDrawIndexedIndirectCount != 0L;
        OpaqueSolidIndirectFeatureState.publishFunctionResolution(
            resolved,
            resolved
                ? ""
                : "vkCmdDrawIndexedIndirectCount-unresolved"
        );
        gateStatus = resolved
            ? "DEVICE_FUNCTION_READY"
            : "MOJANG_ONLY_FUNCTION_UNRESOLVED";
        OpaqueSolidIndirectFeatureState.Snapshot feature =
            OpaqueSolidIndirectFeatureState.snapshot();
        LOGGER.info(
            "OPAQUE_SOLID_INDIRECT requested={} multiDrawIndirect={} "
                + "shaderDrawParameters={} drawIndirectCount={} "
                + "drawIndirectFirstInstance={} maxStorageBufferRange={} "
                + "maxTexelBufferElements={} maxDrawIndirectCount={} "
                + "enabled={} functionResolved={} unavailableReason={}",
            feature.requested(),
            feature.multiDrawIndirect(),
            feature.shaderDrawParameters(),
            feature.drawIndirectCount(),
            feature.drawIndirectFirstInstance(),
            Integer.toUnsignedLong(feature.maxStorageBufferRange()),
            Integer.toUnsignedLong(feature.maxTexelBufferElements()),
            Integer.toUnsignedLong(feature.maxDrawIndirectCount()),
            feature.enabled(),
            feature.functionResolved(),
            feature.unavailableReason().isBlank()
                ? "none"
                : feature.unavailableReason()
        );
    }

    public static synchronized void deviceClosing(
        VulkanDevice closing
    ) {
        if (device != closing) {
            return;
        }
        logLifecycleSnapshot("device-close");
        invalidateAllOwnerState("device-close");
        if (resources != null) {
            resources.close();
        }
        gateStatus = "DEVICE_CLOSE_PREPARED";
    }

    public static synchronized void finishDeviceCloseAfterEncoderDrain(
        VulkanDevice closing
    ) {
        if (device != closing) {
            return;
        }
        if (resources != null) {
            resources.finishCloseAfterEncoderDrain();
            resources = null;
        }
        audit = null;
        auditPrepareStarted = 0L;
        auditUploadedBytesAtFrameStart = 0L;
        auditEligibleRecords = 0;
        device = null;
        gateStatus = "DEVICE_CLOSED";
    }

    /**
     * Called by the wrapped upload callback after Mojang put the allocation
     * into its allocation map and before section publication can proceed.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static synchronized void rangePublished(
        UberGpuBuffer<?> owner,
        Object key,
        String name,
        int usage
    ) {
        if (
            owner == null
                || key == null
                || !"UberBuffer solid".equals(name)
                || (usage != GpuBuffer.USAGE_VERTEX
                    && usage != GpuBuffer.USAGE_INDEX)
        ) {
            return;
        }
        UberGpuBuffer rawOwner = owner;
        TlsfAllocator.Allocation allocation =
            rawOwner.getAllocation(key);
        if (allocation == null) {
            return;
        }
        GpuBuffer gpuBuffer = rawOwner.getGpuBuffer(allocation);
        if (!(gpuBuffer instanceof VulkanGpuBuffer vulkanBuffer)) {
            return;
        }
        long bufferGeneration = LEDGER.publishBuffer(
            gpuBuffer,
            vulkanBuffer.vkBuffer()
        );
        registerKnownBuffer(
            gpuBuffer,
            vulkanBuffer.vkBuffer(),
            bufferGeneration
        );
        OpaqueSolidOwnerGenerationLedger.RangeBinding binding =
            LEDGER.publishRange(
                owner,
                key,
                gpuBuffer,
                bufferGeneration,
                allocation.getOffsetFromHeap(),
                allocation.getSize()
            );
        RangeRecord record = new RangeRecord(
            owner,
            key,
            gpuBuffer,
            vulkanBuffer.vkBuffer(),
            bufferGeneration,
            binding.generation(),
            binding.offset(),
            binding.length()
        );
        Map<Object, RangeRecord> ranges =
            usage == GpuBuffer.USAGE_VERTEX
                ? VERTEX_RANGES
                : INDEX_RANGES;
        ranges.put(key, record);
    }

    /** Exact pre-free hook for both upload replacement and removal. */
    public static synchronized void rangeInvalidating(
        UberGpuBuffer<?> owner,
        Object key,
        String name,
        int usage
    ) {
        if (
            owner == null
                || key == null
                || !"UberBuffer solid".equals(name)
        ) {
            return;
        }
        Map<Object, RangeRecord> ranges =
            usage == GpuBuffer.USAGE_VERTEX
                ? VERTEX_RANGES
                : usage == GpuBuffer.USAGE_INDEX
                    ? INDEX_RANGES
                    : null;
        if (ranges == null) {
            return;
        }
        RangeRecord record = ranges.remove(key);
        if (record == null) {
            return;
        }
        LEDGER.invalidateRangeBeforeFree(
            record.owner(),
            record.key(),
            record.rangeGeneration()
        );
        Object sectionOwner = MESH_OWNERS.get(key);
        if (
            sectionOwner != null
                && CURRENT_MESHES.get(sectionOwner) == key
        ) {
            MODEL.invalidateBeforeReplace(sectionOwner);
            ownerInvalidations++;
        }
    }

    /** Lifecycle owner event for UberGpuBuffer.close(), which clears maps. */
    public static synchronized void uberBufferClosing(
        UberGpuBuffer<?> owner,
        String name,
        int usage
    ) {
        if (
            owner == null
                || !"UberBuffer solid".equals(name)
        ) {
            return;
        }
        Map<Object, RangeRecord> ranges =
            usage == GpuBuffer.USAGE_VERTEX
                ? VERTEX_RANGES
                : usage == GpuBuffer.USAGE_INDEX
                    ? INDEX_RANGES
                    : null;
        if (ranges == null || ranges.isEmpty()) {
            return;
        }
        var iterator = ranges.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            RangeRecord record = entry.getValue();
            if (record.owner() != owner) {
                continue;
            }
            LEDGER.invalidateRangeBeforeFree(
                record.owner(),
                record.key(),
                record.rangeGeneration()
            );
            Object sectionOwner = MESH_OWNERS.get(record.key());
            if (
                sectionOwner != null
                    && CURRENT_MESHES.get(sectionOwner)
                        == record.key()
            ) {
                MODEL.invalidateBeforeReplace(sectionOwner);
                ownerInvalidations++;
            }
            iterator.remove();
        }
    }

    /** Publishes invalidation before SectionMesh replacement or release. */
    public static synchronized void sectionMeshInvalidating(
        Object sectionOwner,
        Object oldMesh
    ) {
        if (sectionOwner == null || oldMesh == null) {
            return;
        }
        if (CURRENT_MESHES.get(sectionOwner) != oldMesh) {
            return;
        }
        MODEL.invalidateBeforeReplace(sectionOwner);
        LEDGER.invalidateMeshBeforeReplace(sectionOwner, oldMesh);
        CURRENT_MESHES.remove(sectionOwner);
        MESH_OWNERS.remove(oldMesh);
        ownerInvalidations++;
    }

    /**
     * Publishes the immutable token only after Mojang has atomically made the
     * fully uploaded mesh current.
     */
    public static synchronized void sectionMeshPublished(
        SectionRenderDispatcher.RenderSection section,
        SectionMesh mesh
    ) {
        if (section == null || mesh == null) {
            return;
        }
        SectionMesh.SectionDraw draw =
            mesh.getSectionDraw(ChunkSectionLayer.SOLID);
        if (draw == null || draw.indexCount() <= 0) {
            MODEL.removeAfterInvalidation(section);
            return;
        }
        RangeRecord vertex = VERTEX_RANGES.get(mesh);
        RangeRecord index = INDEX_RANGES.get(mesh);
        if (
            vertex == null
                || (
                    draw.hasCustomIndexBuffer()
                        && index == null
                )
                || !(vertex.buffer() instanceof VulkanGpuBuffer)
        ) {
            gateStatus = "MOJANG_ONLY_OWNER_PUBLICATION_INCOMPLETE";
            return;
        }
        int indexTypeKey = draw.indexType() == IndexType.INT
            ? INDEX_TYPE_INT
            : INDEX_TYPE_SHORT;
        int vertexStride =
            DefaultVertexFormat.BLOCK.getVertexSize();
        if (
            vertex.offset() % vertexStride != 0L
                || vertex.offset() / vertexStride > Integer.MAX_VALUE
        ) {
            gateStatus = "MOJANG_ONLY_VERTEX_RANGE_UNREPRESENTABLE";
            return;
        }
        long meshGeneration = LEDGER.publishMesh(section, mesh);
        long indexHandle = draw.hasCustomIndexBuffer()
            ? index.nativeHandle()
            : 0L;
        long indexOffset = draw.hasCustomIndexBuffer()
            ? index.offset()
            : 0L;
        long indexLength = draw.hasCustomIndexBuffer()
            ? index.length()
            : 0L;
        OpaqueSolidGpuGenerationToken token =
            new OpaqueSolidGpuGenerationToken(
                Math.max(1L, BlockframeRuntime.deviceGeneration()),
                rendererGeneration,
                worldGeneration,
                meshGeneration,
                vertex.bufferGeneration(),
                vertex.rangeGeneration(),
                draw.hasCustomIndexBuffer()
                    ? index.bufferGeneration()
                    : SEQUENTIAL_INDEX_GENERATION,
                draw.hasCustomIndexBuffer()
                    ? index.rangeGeneration()
                    : SEQUENTIAL_RANGE_GENERATION,
                section.getSectionNode(),
                vertex.nativeHandle(),
                vertex.offset(),
                vertex.length(),
                indexHandle,
                indexOffset,
                indexLength,
                draw.hasCustomIndexBuffer()
                    ? OpaqueSolidGpuGenerationToken.INDEX_BINDING_CUSTOM
                    : OpaqueSolidGpuGenerationToken
                        .INDEX_BINDING_SEQUENTIAL_QUAD,
                indexTypeKey,
                draw.indexCount(),
                Math.toIntExact(vertex.offset() / vertexStride),
                OpaqueSolidGpuScenePipelines.PIPELINE_KEY,
                OpaqueSolidGpuScenePipelines.SHADER_ABI_KEY,
                OpaqueSolidGpuScenePipelines.MATERIAL_KEY
            );
        OpaqueSolidGpuSceneModel.PublishResult result =
            MODEL.publish(section, token);
        if (
            result
                == OpaqueSolidGpuSceneModel.PublishResult.PUBLISHED
                || result
                    == OpaqueSolidGpuSceneModel.PublishResult.UNCHANGED
        ) {
            CURRENT_MESHES.put(section, mesh);
            MESH_OWNERS.put(mesh, section);
            ownerPublications++;
        } else {
            LEDGER.invalidateMeshBeforeReplace(
                section,
                mesh
            );
            gateStatus = result.name();
        }
    }

    public static synchronized void sectionRemoved(
        Object sectionOwner
    ) {
        if (sectionOwner == null) {
            return;
        }
        Object old = CURRENT_MESHES.remove(sectionOwner);
        if (old != null) {
            MESH_OWNERS.remove(old);
        }
        MODEL.removeAfterInvalidation(sectionOwner);
    }

    /**
     * Exact replacement for Mojang prepareChunkRenders. Solid draw objects are
     * never constructed on a successful GPU-scene frame; cutout and
     * translucent records retain the vanilla implementation.
     */
    public static synchronized ChunkSectionsToRender tryPrepare(
        Object renderer,
        Object world,
        List<SectionRenderDispatcher.RenderSection> visibleSections,
        SectionRenderDispatcher dispatcher,
        TextureManager textureManager,
        Matrix4fc modelViewMatrix
    ) {
        preparedMarker = null;
        preparedDispatcher = null;
        preparedVisibleSections = null;
        if (
            renderer == null
                || world == null
                || dispatcher == null
                || visibleSections == null
                || textureManager == null
                || modelViewMatrix == null
                || !eligible()
        ) {
            recordFallbackFrame();
            return null;
        }
        if (rendererIdentity != renderer) {
            rendererCreated(renderer);
        }
        if (worldIdentity == null) {
            worldIdentity = world;
        } else if (worldIdentity != world) {
            invalidateAllOwnerState("world-generation-change");
            worldIdentity = world;
            worldGeneration = increment(worldGeneration);
        }
        String pipelineMismatch =
            OpaqueSolidPipelineAbi.mismatch(
                ChunkSectionLayer.SOLID.pipeline()
            );
        if (!shaderAllowlisted || pipelineMismatch != null) {
            gateStatus = !shaderAllowlisted
                ? "MOJANG_ONLY_UNKNOWN_SHADER"
                : "MOJANG_ONLY_PIPELINE_ABI_"
                    + pipelineMismatch;
            recordFallbackFrame();
            return null;
        }
        if (!ensureResources()) {
            recordFallbackFrame();
            return null;
        }
        if (
            !RenderSystem.getDevice()
                .precompilePipeline(
                    OpaqueSolidGpuScenePipelines
                        .OPAQUE_SOLID_INDIRECT
                )
                .isValid()
        ) {
            gateStatus = "MOJANG_ONLY_GRAPHICS_PIPELINE_INVALID";
            recordFallbackFrame();
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (
            SharedConstants.DEBUG_HOTKEYS
                && minecraft.wireframe
        ) {
            gateStatus = "MOJANG_ONLY_WIREFRAME";
            recordFallbackFrame();
            return null;
        }

        OpaqueSolidGpuSceneAuditWindow currentAudit = audit;
        auditPrepareStarted = currentAudit == null
            ? 0L
            : System.nanoTime();
        auditUploadedBytesAtFrameStart =
            currentAudit == null
                ? 0L
                : resources.snapshot().uploadedBytes();
        auditEligibleRecords = 0;
        if (currentAudit != null) {
            currentAudit.beginFrame();
        }

        EnumMap<
            ChunkSectionLayer,
            Int2ObjectOpenHashMap<
                List<RenderPass.Draw<GpuBufferSlice[]>>
            >
        > drawGroups = new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            drawGroups.put(layer, new Int2ObjectOpenHashMap<>());
        }
        List<DynamicUniforms.ChunkSectionInfo> sectionInfos =
            new ArrayList<>();
        GpuTextureView blockAtlas =
            textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS)
                .getTextureView();
        int textureWidth = blockAtlas.getWidth(0);
        int textureHeight = blockAtlas.getHeight(0);
        int largestIndexCount = 0;
        int largestSolidSequential = 0;
        int solidCount = 0;
        MODEL.beginVisibilityFrame();

        long visibleScanStarted = currentAudit == null
            ? 0L
            : System.nanoTime();
        dispatcher.lock();
        try {
            int visibleCount = visibleSections.size();
            for (int sectionIndex = 0;
                sectionIndex < visibleCount;
                sectionIndex++) {
                SectionRenderDispatcher.RenderSection section =
                    visibleSections.get(sectionIndex);
                SectionMesh mesh = section.getSectionMesh();
                BlockPos renderOffset = section.getRenderOrigin();
                long now = Util.getMillis();
                int uboIndex = -1;
                for (
                    ChunkSectionLayer layer
                        : ChunkSectionLayer.values()
                ) {
                    SectionMesh.SectionDraw draw =
                        mesh.getSectionDraw(layer);
                    SectionRenderDispatcher.RenderSectionBufferSlice
                        slice = dispatcher.getRenderSectionSlice(
                            mesh,
                            layer
                        );
                    if (
                        draw == null
                            || slice == null
                            || (
                                draw.hasCustomIndexBuffer()
                                    && slice.indexBuffer() == null
                            )
                    ) {
                        continue;
                    }
                    if (layer == ChunkSectionLayer.SOLID) {
                        if (
                            !MODEL.appendVisible(
                                section,
                                section.getVisibility(now)
                            )
                        ) {
                            if (currentAudit != null) {
                                currentAudit.cancelFrame();
                            }
                            gateStatus =
                                "MOJANG_FALLBACK_INCOMPLETE_VISIBLE_OWNER";
                            recordFallbackFrame();
                            return null;
                        }
                        if (!draw.hasCustomIndexBuffer()) {
                            largestSolidSequential = Math.max(
                                largestSolidSequential,
                                draw.indexCount()
                            );
                        }
                        solidCount++;
                        continue;
                    }
                    if (uboIndex == -1) {
                        uboIndex = sectionInfos.size();
                        sectionInfos.add(
                            new DynamicUniforms.ChunkSectionInfo(
                                new Matrix4f(modelViewMatrix),
                                renderOffset.getX(),
                                renderOffset.getY(),
                                renderOffset.getZ(),
                                section.getVisibility(now),
                                textureWidth,
                                textureHeight
                            )
                        );
                    }
                    int combinedHash = 173;
                    VertexFormat vertexFormat =
                        layer.pipeline().getVertexFormatBinding(0);
                    GpuBuffer vertexBuffer = slice.vertexBuffer();
                    if (layer != ChunkSectionLayer.TRANSLUCENT) {
                        combinedHash =
                            31 * combinedHash
                                + vertexBuffer.hashCode();
                    }
                    int firstIndex = 0;
                    GpuBuffer indexBuffer;
                    IndexType indexType;
                    if (!draw.hasCustomIndexBuffer()) {
                        largestIndexCount = Math.max(
                            largestIndexCount,
                            draw.indexCount()
                        );
                        indexBuffer = null;
                        indexType = null;
                    } else {
                        indexBuffer = slice.indexBuffer();
                        indexType = draw.indexType();
                        if (layer != ChunkSectionLayer.TRANSLUCENT) {
                            combinedHash =
                                31 * combinedHash
                                    + indexBuffer.hashCode();
                            combinedHash =
                                31 * combinedHash
                                    + indexType.hashCode();
                        }
                        firstIndex = Math.toIntExact(
                            slice.indexBufferOffset()
                                / indexType.bytes
                        );
                    }
                    int finalUboIndex = uboIndex;
                    int baseVertex = Math.toIntExact(
                        slice.vertexBufferOffset()
                            / vertexFormat.getVertexSize()
                    );
                    List<RenderPass.Draw<GpuBufferSlice[]>> draws =
                        drawGroups.get(layer).computeIfAbsent(
                            combinedHash,
                            ignored -> new ArrayList<>()
                        );
                    draws.add(
                        new RenderPass.Draw<>(
                            0,
                            vertexBuffer,
                            indexBuffer,
                            indexType,
                            firstIndex,
                            draw.indexCount(),
                            baseVertex,
                            (sectionUbos, uploader) ->
                                uploader.upload(
                                    "ChunkSection",
                                    sectionUbos[finalUboIndex]
                                )
                        )
                    );
                }
            }
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            if (currentAudit != null) {
                currentAudit.cancelFrame();
            }
            gateStatus =
                "MOJANG_FALLBACK_PREPARE:"
                    + error.getClass().getSimpleName();
            preSubmissionFailures++;
            recordFallbackFrame();
            return null;
        } finally {
            dispatcher.unlock();
            if (currentAudit != null) {
                currentAudit.record(
                    OpaqueSolidGpuSceneAuditWindow
                        .VISIBLE_AND_LAYER_SCAN,
                    System.nanoTime() - visibleScanStarted
                );
            }
        }
        if (solidCount == 0) {
            if (currentAudit != null) {
                currentAudit.cancelFrame();
            }
            gateStatus = "MOJANG_ONLY_NO_SOLID_CONTENT";
            recordFallbackFrame();
            return null;
        }
        if (
            !resources.prepare(
                MODEL,
                modelViewMatrix,
                textureWidth,
                textureHeight
            )
        ) {
            if (currentAudit != null) {
                currentAudit.cancelFrame();
            }
            gateStatus = "MOJANG_FALLBACK_COMPUTE_PREPARE";
            preSubmissionFailures++;
            recordFallbackFrame();
            return null;
        }
        largestIndexCount = Math.max(
            largestIndexCount,
            largestSolidSequential
        );
        long residualEncodingStarted = currentAudit == null
            ? 0L
            : System.nanoTime();
        try {
            GpuBufferSlice[] chunkSectionInfos =
                RenderSystem.getDynamicUniforms()
                    .writeChunkSections(
                        sectionInfos.toArray(
                            new DynamicUniforms.ChunkSectionInfo[0]
                        )
                    );
            ChunkSectionsToRender marker =
                new ChunkSectionsToRender(
                    blockAtlas,
                    drawGroups,
                    largestIndexCount,
                    chunkSectionInfos
                );
            preparedMarker = marker;
            preparedDispatcher = dispatcher;
            preparedVisibleSections = visibleSections;
            PREPARED_MODEL_VIEW.set(modelViewMatrix);
            preparedMaximumSequentialIndices =
                largestSolidSequential;
            auditEligibleRecords = solidCount;
            if (currentAudit != null) {
                currentAudit.record(
                    OpaqueSolidGpuSceneAuditWindow
                        .MOJANG_RESIDUAL_ENCODING,
                    System.nanoTime() - residualEncodingStarted
                );
                currentAudit.record(
                    OpaqueSolidGpuSceneAuditWindow.PREPARE_TOTAL,
                    System.nanoTime() - auditPrepareStarted
                );
            }
            gateStatus = "GPU_SCENE_COMPUTE_READY";
            return marker;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            if (currentAudit != null) {
                currentAudit.cancelFrame();
            }
            resources.cancelBeforeSubmission();
            gateStatus =
                "MOJANG_FALLBACK_MARKER_PUBLICATION:"
                    + error.getClass().getSimpleName();
            preSubmissionFailures++;
            recordFallbackFrame();
            return null;
        }
    }

    /**
     * Recreates Mojang's OPAQUE group exactly, substituting only the empty
     * solid layer with one indirect-count call per physical-buffer bucket.
     */
    public static synchronized boolean renderOpaqueGroup(
        ChunkSectionsToRender marker,
        ChunkSectionLayerGroup group,
        GpuSampler sampler
    ) {
        OpaqueSolidGpuSceneAuditWindow currentAudit = audit;
        if (
            marker != preparedMarker
                || group != ChunkSectionLayerGroup.OPAQUE
                || resources == null
                || preparedDispatcher == null
        ) {
            return false;
        }
        if (ownerContractFaulted) {
            if (currentAudit != null) {
                currentAudit.cancelFrame();
            }
            preSubmissionFailures++;
            try {
                renderMojangSolidFallback(marker, group, sampler);
                sameFrameMojangFallbacks++;
                return false;
            } finally {
                resources.finishSubmission(false);
                preparedMarker = null;
                preparedDispatcher = null;
                preparedVisibleSections = null;
                preparedMaximumSequentialIndices = 0;
            }
        }
        OpaqueSolidGpuSceneDeviceResources.FrameResources frame =
            resources.preparedFrame();
        if (frame == null) {
            throw new IllegalStateException(
                "GPU-scene marker lost its prepared frame"
            );
        }
        RenderSystem.AutoStorageIndexBuffer autoIndices =
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer defaultIndexBuffer =
            preparedMaximumSequentialIndices == 0
                ? null
                : autoIndices.getBuffer(
                    preparedMaximumSequentialIndices
                );
        IndexType defaultIndexType =
            preparedMaximumSequentialIndices == 0
                ? null
                : autoIndices.type();
        long generationPreflightStarted = currentAudit == null
            ? 0L
            : System.nanoTime();
        boolean bucketPreflightPassed =
            preflightBuckets(defaultIndexBuffer);
        if (currentAudit != null) {
            currentAudit.record(
                OpaqueSolidGpuSceneAuditWindow
                    .GENERATION_TOKEN_PREFLIGHT,
                System.nanoTime() - generationPreflightStarted
            );
        }
        if (!bucketPreflightPassed) {
            if (currentAudit != null) {
                currentAudit.cancelFrame();
            }
            gateStatus = "MOJANG_FALLBACK_BUCKET_PREFLIGHT";
            preSubmissionFailures++;
            try {
                renderMojangSolidFallback(marker, group, sampler);
                sameFrameMojangFallbacks++;
                return false;
            } finally {
                resources.finishSubmission(false);
                preparedMarker = null;
                preparedDispatcher = null;
                preparedVisibleSections = null;
                preparedMaximumSequentialIndices = 0;
            }
        }

        Minecraft minecraft = Minecraft.getInstance();
        var renderTarget = group.outputTarget();
        boolean completed = false;
        try (
            RenderPass renderPass =
                RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                        () -> "Section layers for " + group.label(),
                        renderTarget.getColorTextureView(),
                        java.util.Optional.empty(),
                        renderTarget.getDepthTextureView(),
                        java.util.OptionalDouble.empty()
                    )
        ) {
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture(
                "Sampler0",
                marker.textureView(),
                sampler
            );
            renderPass.bindTexture(
                "Sampler2",
                minecraft.gameRenderer.lightmap(),
                RenderSystem.getSamplerCache().getClampToEdge(
                    FilterMode.LINEAR
                )
            );
            renderPass.setPipeline(
                OpaqueSolidGpuScenePipelines.OPAQUE_SOLID_INDIRECT
            );
            renderPass.setUniform(
                "OpaqueSolidFrame",
                frame.frameBuffer()
            );
            renderPass.setUniform(
                "OpaqueSolidScene",
                frame.sceneBuffer()
            );
            renderPass.setUniform(
                "OpaqueSolidVisibility",
                frame.visibilityBuffer()
            );
            OpaqueSolidIndirectRenderPass indirect =
                (OpaqueSolidIndirectRenderPass)
                    ((RenderPassAccessor)(Object)renderPass)
                        .blockframe$backend();
            int bucketCount = resources.preparedBucketCount();
            int firstBucket = -1;
            for (int bucket = 0; bucket < bucketCount; bucket++) {
                if (MODEL.bucketActive(bucket)) {
                    firstBucket = bucket;
                    break;
                }
            }
            if (firstBucket < 0) {
                throw new IllegalStateException(
                    "opaque-solid prepared scene has no active bucket"
                );
            }
            bindBucket(
                renderPass,
                firstBucket,
                defaultIndexBuffer,
                defaultIndexType
            );
            indirect.blockframe$prepareOpaqueSolidDescriptors(
                frame.sceneBufferView(),
                frame.visibilityBufferView()
            );
            if (!resources.beginSubmission()) {
                throw new IllegalStateException(
                    "opaque-solid submission state rejected"
                );
            }
            for (
                int bucket = firstBucket;
                bucket < bucketCount;
                bucket++
            ) {
                if (!MODEL.bucketActive(bucket)) {
                    continue;
                }
                if (bucket != firstBucket) {
                    bindBucket(
                        renderPass,
                        bucket,
                        defaultIndexBuffer,
                        defaultIndexType
                    );
                }
                long indirectStarted = currentAudit == null
                    ? 0L
                    : System.nanoTime();
                indirect.blockframe$drawIndexedIndirectCount(
                    frame.commandBuffer(),
                    (long)bucket
                        * OpaqueSolidGpuSceneDeviceResources.CAPACITY
                        * VkDrawIndexedIndirectCommand.SIZEOF,
                    frame.countBuffer(),
                    (long)bucket * Integer.BYTES,
                    OpaqueSolidGpuSceneDeviceResources.CAPACITY,
                    VkDrawIndexedIndirectCommand.SIZEOF
                );
                if (currentAudit != null) {
                    currentAudit.record(
                        OpaqueSolidGpuSceneAuditWindow
                            .INDIRECT_CPU_SUBMISSION,
                        System.nanoTime() - indirectStarted
                    );
                }
                BlockframeRuntime.recordDrawCall();
                indirectCalls++;
                indirectDrawCapacity +=
                    OpaqueSolidGpuSceneDeviceResources.CAPACITY;
            }

            long residualEncodingStarted =
                currentAudit == null ? 0L : System.nanoTime();
            for (
                ChunkSectionLayer layer : group.layers()
            ) {
                if (layer == ChunkSectionLayer.SOLID) {
                    continue;
                }
                renderPass.setPipeline(layer.pipeline());
                Int2ObjectOpenHashMap<
                    List<RenderPass.Draw<GpuBufferSlice[]>>
                > layerGroups =
                    marker.drawGroupsPerLayer().get(layer);
                for (
                    List<RenderPass.Draw<GpuBufferSlice[]>> draws
                        : layerGroups.values()
                ) {
                    if (!draws.isEmpty()) {
                        renderPass.drawMultipleIndexed(
                            draws,
                            marker.maxIndicesRequired() == 0
                                ? null
                                : autoIndices.getBuffer(
                                    marker.maxIndicesRequired()
                                ),
                            marker.maxIndicesRequired() == 0
                                ? null
                                : autoIndices.type(),
                            List.of("ChunkSection"),
                            marker.chunkSectionInfos()
                        );
                    }
                }
            }
            if (currentAudit != null) {
                currentAudit.record(
                    OpaqueSolidGpuSceneAuditWindow
                        .MOJANG_RESIDUAL_ENCODING,
                    System.nanoTime() - residualEncodingStarted
                );
            }
            completed = true;
            indirectSubmittedInOwnerGeneration = true;
            gateStatus = "GPU_SCENE_INDIRECT_SUBMITTED";
            return true;
        } catch (RuntimeException | LinkageError error) {
            if (resources.snapshot().submissionStarted()) {
                postSubmissionFailures++;
                gateStatus =
                    "GPU_SCENE_POST_SUBMISSION_FAILURE:"
                        + error.getClass().getSimpleName();
            } else {
                preSubmissionFailures++;
                gateStatus =
                    "GPU_SCENE_PRE_SUBMISSION_FAILURE:"
                        + error.getClass().getSimpleName();
                resources.cancelBeforeSubmission();
                try {
                    renderMojangSolidFallback(marker, group, sampler);
                    sameFrameMojangFallbacks++;
                    return false;
                } catch (RuntimeException | LinkageError fallbackError) {
                    error.addSuppressed(fallbackError);
                }
            }
            throw error;
        } finally {
            if (currentAudit != null) {
                if (completed) {
                    long uploadedNow =
                        resources.snapshot().uploadedBytes();
                    currentAudit.recordUploadBytes(
                        Math.max(
                            0L,
                            uploadedNow
                                - auditUploadedBytesAtFrameStart
                        )
                    );
                    currentAudit.finishFrame(
                        MODEL.snapshot().visible(),
                        auditEligibleRecords
                    );
                } else {
                    currentAudit.cancelFrame();
                }
            }
            resources.finishSubmission(completed);
            preparedMarker = null;
            preparedDispatcher = null;
            preparedVisibleSections = null;
            preparedMaximumSequentialIndices = 0;
        }
    }

    private static void bindBucket(
        RenderPass renderPass,
        int bucket,
        GpuBuffer defaultIndexBuffer,
        IndexType defaultIndexType
    ) {
        OpaqueSolidGpuSceneModel.BucketKey key = MODEL.bucket(bucket);
        GpuBuffer vertex =
            knownBuffer(
                key.vertexBufferHandle(),
                key.vertexBufferGeneration()
            );
        GpuBuffer index =
            key.indexBindingKey()
                    == OpaqueSolidGpuGenerationToken.INDEX_BINDING_CUSTOM
                ? knownBuffer(
                    key.indexBufferHandle(),
                    key.indexBufferGeneration()
                )
                : defaultIndexBuffer;
        IndexType indexType =
            key.indexBindingKey()
                    == OpaqueSolidGpuGenerationToken.INDEX_BINDING_CUSTOM
                ? key.indexTypeKey() == INDEX_TYPE_SHORT
                    ? IndexType.SHORT
                    : IndexType.INT
                : defaultIndexType;
        if (
            vertex == null
                || index == null
                || indexType == null
                || vertex.isClosed()
                || index.isClosed()
        ) {
            throw new IllegalStateException(
                "opaque-solid bucket became unavailable before draw"
            );
        }
        renderPass.setVertexBuffer(0, vertex.slice());
        renderPass.setIndexBuffer(index, indexType);
    }

    /**
     * Error-only same-frame reconstruction of Mojang's SOLID records. It is
     * never entered on a successful indirect frame. The caller returns false
     * afterwards so the original marker draws its retained CUTOUT records.
     */
    private static void renderMojangSolidFallback(
        ChunkSectionsToRender originalMarker,
        ChunkSectionLayerGroup group,
        GpuSampler sampler
    ) {
        List<SectionRenderDispatcher.RenderSection> visible =
            preparedVisibleSections;
        SectionRenderDispatcher dispatcher = preparedDispatcher;
        if (visible == null || dispatcher == null) {
            throw new IllegalStateException(
                "same-frame solid fallback lost prepared owners"
            );
        }
        resources.cancelBeforeSubmission();
        EnumMap<
            ChunkSectionLayer,
            Int2ObjectOpenHashMap<
                List<RenderPass.Draw<GpuBufferSlice[]>>
            >
        > drawGroups = new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            drawGroups.put(layer, new Int2ObjectOpenHashMap<>());
        }
        List<DynamicUniforms.ChunkSectionInfo> sectionInfos =
            new ArrayList<>();
        int textureWidth = originalMarker.textureView().getWidth(0);
        int textureHeight = originalMarker.textureView().getHeight(0);
        int largestIndexCount = 0;
        dispatcher.lock();
        try {
            long now = Util.getMillis();
            for (int index = 0; index < visible.size(); index++) {
                SectionRenderDispatcher.RenderSection section =
                    visible.get(index);
                SectionMesh mesh = section.getSectionMesh();
                SectionMesh.SectionDraw draw =
                    mesh.getSectionDraw(ChunkSectionLayer.SOLID);
                SectionRenderDispatcher.RenderSectionBufferSlice slice =
                    dispatcher.getRenderSectionSlice(
                        mesh,
                        ChunkSectionLayer.SOLID
                    );
                if (
                    draw == null
                        || slice == null
                        || (
                            draw.hasCustomIndexBuffer()
                                && slice.indexBuffer() == null
                        )
                ) {
                    continue;
                }
                BlockPos renderOffset = section.getRenderOrigin();
                int uboIndex = sectionInfos.size();
                sectionInfos.add(
                    new DynamicUniforms.ChunkSectionInfo(
                        new Matrix4f(PREPARED_MODEL_VIEW),
                        renderOffset.getX(),
                        renderOffset.getY(),
                        renderOffset.getZ(),
                        section.getVisibility(now),
                        textureWidth,
                        textureHeight
                    )
                );
                GpuBuffer vertexBuffer = slice.vertexBuffer();
                int combinedHash =
                    31 * 173 + vertexBuffer.hashCode();
                GpuBuffer indexBuffer;
                IndexType indexType;
                int firstIndex;
                if (draw.hasCustomIndexBuffer()) {
                    indexBuffer = slice.indexBuffer();
                    indexType = draw.indexType();
                    combinedHash =
                        31 * combinedHash + indexBuffer.hashCode();
                    combinedHash =
                        31 * combinedHash + indexType.hashCode();
                    firstIndex = Math.toIntExact(
                        slice.indexBufferOffset() / indexType.bytes
                    );
                } else {
                    indexBuffer = null;
                    indexType = null;
                    firstIndex = 0;
                    largestIndexCount = Math.max(
                        largestIndexCount,
                        draw.indexCount()
                    );
                }
                int baseVertex = Math.toIntExact(
                    slice.vertexBufferOffset()
                        / DefaultVertexFormat.BLOCK.getVertexSize()
                );
                int finalUboIndex = uboIndex;
                drawGroups.get(ChunkSectionLayer.SOLID)
                    .computeIfAbsent(
                        combinedHash,
                        ignored -> new ArrayList<>()
                    )
                    .add(
                        new RenderPass.Draw<>(
                            0,
                            vertexBuffer,
                            indexBuffer,
                            indexType,
                            firstIndex,
                            draw.indexCount(),
                            baseVertex,
                            (sectionUbos, uploader) ->
                                uploader.upload(
                                    "ChunkSection",
                                    sectionUbos[finalUboIndex]
                                )
                        )
                    );
            }
        } finally {
            dispatcher.unlock();
        }
        if (sectionInfos.isEmpty()) {
            throw new IllegalStateException(
                "same-frame solid fallback found no renderable content"
            );
        }
        GpuBufferSlice[] sectionUbos =
            RenderSystem.getDynamicUniforms()
                .writeChunkSections(
                    sectionInfos.toArray(
                        new DynamicUniforms.ChunkSectionInfo[0]
                    )
                );
        ChunkSectionsToRender solidFallback =
            new ChunkSectionsToRender(
                originalMarker.textureView(),
                drawGroups,
                largestIndexCount,
                sectionUbos
            );
        solidFallback.renderGroup(group, sampler);
        gateStatus = "MOJANG_SAME_FRAME_SOLID_FALLBACK";
    }

    public static synchronized List<String> debugLines() {
        OpaqueSolidGpuSceneModel.Snapshot model = MODEL.snapshot();
        OpaqueSolidGpuSceneDeviceResources.Snapshot gpu =
            resources == null ? null : resources.snapshot();
        return List.of(
            "Opaque-solid GPU scene: " + gateStatus,
            "feature requested/multi/shader/count/first/function: "
                + featureLine(),
            "scene entries/buckets/visible: "
                + model.entries()
                + " / "
                + model.buckets()
                + " / "
                + model.visible(),
            "owner published/invalidated/fallbacks: "
                + ownerPublications
                + " / "
                + ownerInvalidations
                + " / "
                + fallbackFrames,
            "fallbacks before activation/runtime: "
                + preActivationFallbackFrames
                + " / "
                + runtimeFallbackFrames,
            "indirect calls/capacity/pre/post failures: "
                + indirectCalls
                + " / "
                + indirectDrawCapacity
                + " / "
                + preSubmissionFailures
                + " / "
                + postSubmissionFailures,
            "same-frame Mojang solid fallbacks: "
                + sameFrameMojangFallbacks,
            gpu == null
                ? "GPU scene RAM/VRAM/upload: 0 / 0 / 0 bytes"
                : "GPU scene RAM/VRAM/upload: "
                    + gpu.ramBytes()
                    + " / "
                    + gpu.vramBytes()
                    + " / "
                    + gpu.uploadedBytes()
                    + " bytes",
            "shader allowlist: "
                + shaderAllowlisted
                + " ("
                + shaderReason
                + ")"
        );
    }

    private static void logLifecycleSnapshot(String event) {
        OpaqueSolidGpuSceneModel.Snapshot model = MODEL.snapshot();
        OpaqueSolidGpuSceneDeviceResources.Snapshot gpu =
            resources == null ? null : resources.snapshot();
        LOGGER.info(
            "OPAQUE_SOLID_GPU_SCENE event={} status={} entries={} "
                + "buckets={} visible={} published={} invalidated={} "
                + "fallbackFrames={} preActivationFallbackFrames={} "
                + "runtimeFallbackFrames={} indirectCalls={} "
                + "indirectCapacity={} preFailures={} postFailures={} "
                + "sameFrameFallbacks={} dispatchedFrames={} "
                + "ramBytes={} vramBytes={} uploadedBytes={} "
                + "ownerFaulted={}",
            event,
            gateStatus,
            model.entries(),
            model.buckets(),
            model.visible(),
            ownerPublications,
            ownerInvalidations,
            fallbackFrames,
            preActivationFallbackFrames,
            runtimeFallbackFrames,
            indirectCalls,
            indirectDrawCapacity,
            preSubmissionFailures,
            postSubmissionFailures,
            sameFrameMojangFallbacks,
            gpu == null ? 0L : gpu.dispatchedFrames(),
            gpu == null ? 0L : gpu.ramBytes(),
            gpu == null ? 0L : gpu.vramBytes(),
            gpu == null ? 0L : gpu.uploadedBytes(),
            ownerContractFaulted
        );
        OpaqueSolidGpuSceneAuditWindow currentAudit = audit;
        if (currentAudit != null) {
            LOGGER.info(
                "OPAQUE_SOLID_GPU_SCENE_AUDIT event={} {}",
                event,
                currentAudit.summary()
            );
        } else {
            LOGGER.info(
                "OPAQUE_SOLID_GPU_SCENE_AUDIT event={} enabled=false "
                    + "reason=SYSTEM_PROPERTY_FALSE",
                event
            );
        }
    }

    private static boolean eligible() {
        try {
            if (
                ownerContractFaulted
            ) {
                gateStatus = "MOJANG_ONLY_OWNER_CONTRACT_FAULT";
                return false;
            }
            if (
                !BlockframeRuntime.engine()
                    .config()
                    .settings()
                    .engineEnabled()
                    || !BlockframeRuntime.engine()
                        .config()
                        .settings()
                        .frameResourcesEnabled()
                    || BlockframeRuntime.safeStartActive()
                    || !BlockframeRuntime.featureEnabled(
                        de.morau.blockframe.core.state.FeatureId
                            .OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
                    )
            ) {
                gateStatus = "MOJANG_ONLY_POLICY";
                return false;
            }
            if (
                EngineCapabilities.Backend.from(
                    RenderSystem.getDevice()
                        .getDeviceInfo()
                        .backendName()
                ) != EngineCapabilities.Backend.VULKAN
            ) {
                gateStatus = "MOJANG_ONLY_OPENGL";
                return false;
            }
            OpaqueSolidIndirectFeatureState.Snapshot feature =
                OpaqueSolidIndirectFeatureState.snapshot();
            if (!feature.usable()) {
                gateStatus =
                    "MOJANG_ONLY_CAPABILITY:"
                        + feature.unavailableReason();
                return false;
            }
            return true;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            gateStatus =
                "MOJANG_ONLY_GATE_FAILURE:"
                    + error.getClass().getSimpleName();
            return false;
        }
    }

    private static boolean ensureResources() {
        if (resources != null) {
            ensureAuditWindow();
            return true;
        }
        if (device == null) {
            gateStatus = "MOJANG_ONLY_NO_VULKAN_DEVICE";
            return false;
        }
        try {
            resources =
                new OpaqueSolidGpuSceneDeviceResources(device);
            ensureAuditWindow();
            gateStatus = "GPU_SCENE_RESOURCES_READY";
            return true;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            gateStatus =
                "MOJANG_ONLY_RESOURCE_CREATION:"
                    + error.getClass().getSimpleName();
            LOGGER.warn(
                "Opaque-solid GPU scene fell back to Mojang during "
                    + "resource construction",
                error
            );
            return false;
        }
    }

    private static void ensureAuditWindow() {
        if (
            audit == null
                && Boolean.getBoolean(
                    OpaqueSolidGpuSceneAuditWindow.ENABLE_PROPERTY
                )
        ) {
            audit = new OpaqueSolidGpuSceneAuditWindow();
        }
    }

    private static boolean preflightBuckets(
        GpuBuffer defaultIndexBuffer
    ) {
        int count = resources.preparedBucketCount();
        for (int bucket = 0; bucket < count; bucket++) {
            if (!MODEL.bucketActive(bucket)) {
                continue;
            }
            OpaqueSolidGpuSceneModel.BucketKey key =
                MODEL.bucket(bucket);
            GpuBuffer vertex =
                knownBuffer(
                    key.vertexBufferHandle(),
                    key.vertexBufferGeneration()
                );
            if (vertex == null || vertex.isClosed()) {
                return false;
            }
            if (
                key.indexBindingKey()
                    == OpaqueSolidGpuGenerationToken
                        .INDEX_BINDING_CUSTOM
            ) {
                GpuBuffer index =
                    knownBuffer(
                        key.indexBufferHandle(),
                        key.indexBufferGeneration()
                    );
                if (index == null || index.isClosed()) {
                    return false;
                }
            } else if (
                defaultIndexBuffer == null
                    || defaultIndexBuffer.isClosed()
            ) {
                return false;
            }
        }
        return true;
    }

    private static void registerKnownBuffer(
        Object buffer,
        long handle,
        long generation
    ) {
        for (int index = 0; index < knownBufferCount; index++) {
            if (KNOWN_BUFFERS[index] == buffer) {
                KNOWN_BUFFER_HANDLES[index] = handle;
                KNOWN_BUFFER_GENERATIONS[index] = generation;
                return;
            }
        }
        for (int index = 0; index < knownBufferCount; index++) {
            if (
                KNOWN_BUFFERS[index] == null
                    || (
                        KNOWN_BUFFERS[index] instanceof GpuBuffer old
                            && old.isClosed()
                    )
            ) {
                KNOWN_BUFFERS[index] = buffer;
                KNOWN_BUFFER_HANDLES[index] = handle;
                KNOWN_BUFFER_GENERATIONS[index] = generation;
                return;
            }
        }
        if (knownBufferCount >= KNOWN_BUFFER_CAPACITY) {
            ownerContractFaulted = true;
            gateStatus = "MOJANG_ONLY_KNOWN_BUFFER_CAPACITY";
            return;
        }
        KNOWN_BUFFERS[knownBufferCount] = buffer;
        KNOWN_BUFFER_HANDLES[knownBufferCount] = handle;
        KNOWN_BUFFER_GENERATIONS[knownBufferCount] = generation;
        knownBufferCount++;
    }

    private static GpuBuffer knownBuffer(
        long handle,
        long generation
    ) {
        for (int index = 0; index < knownBufferCount; index++) {
            if (
                KNOWN_BUFFER_HANDLES[index] == handle
                    && KNOWN_BUFFER_GENERATIONS[index] == generation
            ) {
                return (GpuBuffer)KNOWN_BUFFERS[index];
            }
        }
        return null;
    }

    private static void invalidateAllOwnerState(String reason) {
        long lifecycleStarted = audit == null
            ? 0L
            : System.nanoTime();
        preparedMarker = null;
        preparedDispatcher = null;
        preparedVisibleSections = null;
        preparedMaximumSequentialIndices = 0;
        MODEL.clearAfterOwnerInvalidation();
        LEDGER.clear();
        VERTEX_RANGES.clear();
        INDEX_RANGES.clear();
        MESH_OWNERS.clear();
        CURRENT_MESHES.clear();
        for (int index = 0; index < knownBufferCount; index++) {
            KNOWN_BUFFERS[index] = null;
            KNOWN_BUFFER_HANDLES[index] = 0L;
            KNOWN_BUFFER_GENERATIONS[index] = 0L;
        }
        knownBufferCount = 0;
        indirectSubmittedInOwnerGeneration = false;
        gateStatus = "INVALIDATED_" + reason.toUpperCase(Locale.ROOT);
        if (audit != null) {
            audit.record(
                OpaqueSolidGpuSceneAuditWindow
                    .LIFECYCLE_AND_RETIREMENT,
                System.nanoTime() - lifecycleStarted
            );
        }
    }

    private static void recordFallbackFrame() {
        fallbackFrames++;
        if (indirectSubmittedInOwnerGeneration) {
            runtimeFallbackFrames++;
        } else {
            preActivationFallbackFrames++;
        }
    }

    private static boolean verifyVanillaTerrainShaderSources() {
        try {
            return VANILLA_TERRAIN_VSH.equals(
                    resourceHash(
                        Identifier.fromNamespaceAndPath(
                            "minecraft",
                            "shaders/core/terrain.vsh"
                        )
                    )
                )
                && VANILLA_TERRAIN_FSH.equals(
                    resourceHash(
                        Identifier.fromNamespaceAndPath(
                            "minecraft",
                            "shaders/core/terrain.fsh"
                        )
                    )
                );
        } catch (Throwable error) {
            LOGGER.warn(
                "Terrain shader allowlist check failed; Mojang path retained",
                error
            );
            return false;
        }
    }

    private static String resourceHash(Identifier id) throws Exception {
        var resource = Minecraft.getInstance()
            .getResourceManager()
            .getResource(id)
            .orElseThrow();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = resource.open()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read != 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String featureLine() {
        OpaqueSolidIndirectFeatureState.Snapshot feature =
            OpaqueSolidIndirectFeatureState.snapshot();
        return feature.requested()
            + "/"
            + feature.multiDrawIndirect()
            + "/"
            + feature.shaderDrawParameters()
            + "/"
            + feature.drawIndirectCount()
            + "/"
            + feature.drawIndirectFirstInstance()
            + "/"
            + feature.functionResolved();
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private record RangeRecord(
        Object owner,
        Object key,
        GpuBuffer buffer,
        long nativeHandle,
        long bufferGeneration,
        long rangeGeneration,
        long offset,
        long length
    ) {
    }
}
