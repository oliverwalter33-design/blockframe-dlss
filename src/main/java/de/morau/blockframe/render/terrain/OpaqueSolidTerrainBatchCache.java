package de.morau.blockframe.render.terrain;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renderer-scoped adapter for the first Phase-2A.1 productive slice.
 *
 * <p>Only {@link ChunkSectionLayer#SOLID} draw records are reused. Mojang
 * continues to own visibility, meshes, dynamic uniforms, pipelines,
 * descriptors, render passes and every Vulkan submission.</p>
 */
public final class OpaqueSolidTerrainBatchCache implements AutoCloseable {
    private static final Logger LOGGER =
        LoggerFactory.getLogger("blockframe-solid-batch");
    private static final int CHUNK_SECTION_DESCRIPTOR_KEY =
        "ChunkSection".hashCode();
    private static long nextRendererGeneration;

    private final long rendererGeneration = nextRendererGeneration();
    private PersistentDrawTemplateTable table;
    private Object worldIdentity;
    private long worldGeneration;
    private boolean closed;
    private boolean failureLogged;
    private boolean pipelineFailureLogged;

    public ChunkSectionsToRender tryPrepare(
        Object nextWorldIdentity,
        List<SectionRenderDispatcher.RenderSection> visibleSections,
        SectionRenderDispatcher dispatcher,
        TextureManager textureManager,
        Matrix4fc modelViewMatrix,
        long deviceGeneration,
        long reloadEpoch
    ) {
        Objects.requireNonNull(visibleSections, "visibleSections");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(textureManager, "textureManager");
        Objects.requireNonNull(modelViewMatrix, "modelViewMatrix");
        if (
            this.closed
                || nextWorldIdentity == null
                || deviceGeneration <= 0L
                || (
                    SharedConstants.DEBUG_HOTKEYS
                        && Minecraft.getInstance().wireframe
                )
        ) {
            return null;
        }
        if (this.worldIdentity != nextWorldIdentity) {
            this.worldIdentity = nextWorldIdentity;
            this.worldGeneration = incrementSaturated(
                this.worldGeneration
            );
        }

        PersistentDrawTemplateTable active = this.ensureTable();
        if (
            active == null
                || !active.beginFrame(
                    Thread.currentThread(),
                    this.worldGeneration,
                    this.rendererGeneration,
                    deviceGeneration,
                    reloadEpoch
                )
        ) {
            return null;
        }

        long buildStarted = System.nanoTime();
        long visibilityStarted = buildStarted;
        try {
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
            GpuTextureView blockAtlas = textureManager
                .getTexture(TextureAtlas.LOCATION_BLOCKS)
                .getTextureView();
            int textureAtlasWidth = blockAtlas.getWidth(0);
            int textureAtlasHeight = blockAtlas.getHeight(0);
            RenderPipeline solidPipeline =
                ChunkSectionLayer.SOLID.pipeline();
            VertexFormat solidVertexFormat =
                solidPipeline.getVertexFormatBinding(0);
            if (
                !isCompatibleSolidPipeline(
                    solidPipeline,
                    solidVertexFormat
                )
            ) {
                this.logPipelineFailureOnce(
                    solidPipeline,
                    solidVertexFormat
                );
                active.abortBeforeSubmission(
                    PersistentDrawTemplateTable.Failure
                        .PIPELINE_ABI_UNSUPPORTED
                );
                return null;
            }
            int pipelineKey = pipelineKey(solidPipeline);
            int materialKey = materialKey(
                blockAtlas,
                textureAtlasWidth,
                textureAtlasHeight
            );
            int largestIndexCount = 0;
            int solidSubmissionCount = 0;
            long totalDrawRecords = 0L;

            dispatcher.lock();
            try {
                int visibleCount = visibleSections.size();
                for (
                    int sectionIndex = 0;
                    sectionIndex < visibleCount;
                    sectionIndex++
                ) {
                    SectionRenderDispatcher.RenderSection section =
                        visibleSections.get(sectionIndex);
                    SectionMesh sectionMesh = section.getSectionMesh();
                    BlockPos renderOffset = section.getRenderOrigin();
                    long sectionNode = section.getSectionNode();
                    long now = Util.getMillis();
                    int uboIndex = -1;

                    for (
                        ChunkSectionLayer layer
                            : ChunkSectionLayer.values()
                    ) {
                        SectionMesh.SectionDraw sectionDraw =
                            sectionMesh.getSectionDraw(layer);
                        SectionRenderDispatcher.RenderSectionBufferSlice
                            slice = dispatcher.getRenderSectionSlice(
                                sectionMesh,
                                layer
                            );
                        if (
                            slice == null
                                || sectionDraw == null
                                || (
                                    sectionDraw
                                            .hasCustomIndexBuffer()
                                        && slice.indexBuffer() == null
                                )
                        ) {
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
                                    textureAtlasWidth,
                                    textureAtlasHeight
                                )
                            );
                        }

                        int combinedHash = 173;
                        VertexFormat vertexFormat =
                            layer.pipeline()
                                .getVertexFormatBinding(0);
                        GpuBuffer vertexBuffer =
                            slice.vertexBuffer();
                        if (layer != ChunkSectionLayer.TRANSLUCENT) {
                            combinedHash =
                                31 * combinedHash
                                    + vertexBuffer.hashCode();
                        }

                        int firstIndex = 0;
                        GpuBuffer indexBuffer;
                        IndexType indexType;
                        if (!sectionDraw.hasCustomIndexBuffer()) {
                            largestIndexCount = Math.max(
                                largestIndexCount,
                                sectionDraw.indexCount()
                            );
                            indexBuffer = null;
                            indexType = null;
                        } else {
                            indexBuffer = slice.indexBuffer();
                            indexType = sectionDraw.indexType();
                            if (
                                layer
                                    != ChunkSectionLayer.TRANSLUCENT
                            ) {
                                combinedHash =
                                    31 * combinedHash
                                        + indexBuffer.hashCode();
                                combinedHash =
                                    31 * combinedHash
                                        + indexType.hashCode();
                            }
                            firstIndex = (int)(
                                slice.indexBufferOffset()
                                    / indexType.bytes
                            );
                        }
                        int baseVertex = (int)(
                            slice.vertexBufferOffset()
                                / vertexFormat.getVertexSize()
                        );
                        Int2ObjectOpenHashMap<
                            List<RenderPass.Draw<GpuBufferSlice[]>>
                        > layerGroups = drawGroups.get(layer);
                        List<RenderPass.Draw<GpuBufferSlice[]>>
                            draws = layerGroups.get(combinedHash);
                        if (draws == null) {
                            draws = new ArrayList<>();
                            layerGroups.put(combinedHash, draws);
                            if (layer == ChunkSectionLayer.SOLID) {
                                solidSubmissionCount++;
                            }
                        }

                        RenderPass.Draw<GpuBufferSlice[]> renderDraw;
                        if (layer == ChunkSectionLayer.SOLID) {
                            renderDraw = this.solidDraw(
                                active,
                                section,
                                sectionMesh,
                                sectionNode,
                                renderOffset,
                                slice,
                                sectionDraw,
                                vertexBuffer,
                                indexBuffer,
                                indexType,
                                firstIndex,
                                baseVertex,
                                solidPipeline,
                                pipelineKey,
                                solidVertexFormat,
                                blockAtlas,
                                materialKey,
                                uboIndex,
                                deviceGeneration,
                                reloadEpoch
                            );
                            if (renderDraw == null) {
                                active.abortBeforeSubmissionPreservingFailure();
                                return null;
                            }
                        } else {
                            int finalUboIndex = uboIndex;
                            renderDraw = new RenderPass.Draw<>(
                                0,
                                vertexBuffer,
                                indexBuffer,
                                indexType,
                                firstIndex,
                                sectionDraw.indexCount(),
                                baseVertex,
                                (sectionUbos, uploader) ->
                                    uploader.upload(
                                        "ChunkSection",
                                        sectionUbos[finalUboIndex]
                                    )
                            );
                        }
                        draws.add(renderDraw);
                        totalDrawRecords++;
                    }
                }
            } finally {
                dispatcher.unlock();
            }
            long visibilityFinished = System.nanoTime();

            GpuBufferSlice[] chunkSectionInfos =
                RenderSystem.getDynamicUniforms()
                    .writeChunkSections(
                        sectionInfos.toArray(
                            new DynamicUniforms.ChunkSectionInfo[0]
                        )
                    );
            ChunkSectionsToRender result = new ChunkSectionsToRender(
                blockAtlas,
                drawGroups,
                largestIndexCount,
                chunkSectionInfos
            );
            active.publishFrame(
                result,
                solidSubmissionCount,
                visibilityFinished - visibilityStarted,
                System.nanoTime() - buildStarted,
                totalDrawRecords,
                0L
            );
            return result;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            active.abortBeforeSubmission(
                PersistentDrawTemplateTable.Failure
                    .PRE_SUBMISSION_FAILURE
            );
            this.logFailureOnce(error);
            return null;
        }
    }

    public boolean beginSolidSubmission(Object marker) {
        PersistentDrawTemplateTable active = this.table;
        return active != null
            && active.beginSolidSubmission(marker);
    }

    public void recordSubmissionNanos(long nanos) {
        PersistentDrawTemplateTable active = this.table;
        if (active != null) {
            active.recordSubmissionNanos(nanos);
        }
    }

    public void finishOpaqueGroup(
        Object marker,
        boolean completedNormally
    ) {
        PersistentDrawTemplateTable active = this.table;
        if (active != null) {
            active.finishOpaqueGroup(marker, completedNormally);
        }
    }

    public void invalidate(
        PersistentDrawTemplateTable.Failure reason
    ) {
        PersistentDrawTemplateTable active = this.table;
        if (active != null) {
            active.invalidate(reason);
        }
    }

    public PersistentDrawTemplateTable.Snapshot snapshot() {
        PersistentDrawTemplateTable active = this.table;
        return active == null ? null : active.snapshot();
    }

    public boolean closeAndReport() {
        this.closed = true;
        PersistentDrawTemplateTable active = this.table;
        return active == null || active.closeAndReport();
    }

    @Override
    public void close() {
        this.closeAndReport();
    }

    private RenderPass.Draw<GpuBufferSlice[]> solidDraw(
        PersistentDrawTemplateTable active,
        SectionRenderDispatcher.RenderSection section,
        SectionMesh sectionMesh,
        long sectionNode,
        BlockPos renderOffset,
        SectionRenderDispatcher.RenderSectionBufferSlice slice,
        SectionMesh.SectionDraw sectionDraw,
        GpuBuffer vertexBuffer,
        GpuBuffer indexBuffer,
        IndexType indexType,
        int firstIndex,
        int baseVertex,
        RenderPipeline pipeline,
        int pipelineKey,
        VertexFormat vertexFormat,
        GpuTextureView blockAtlas,
        int materialKey,
        int uboIndex,
        long deviceGeneration,
        long reloadEpoch
    ) {
        PersistentDrawTemplateTable.Failure ownershipFailure =
            this.validateSolidOwnership(
                section,
                sectionNode,
                renderOffset,
                slice,
                sectionDraw,
                vertexBuffer,
                indexBuffer,
                indexType,
                vertexFormat
            );
        if (ownershipFailure != PersistentDrawTemplateTable.Failure.NONE) {
            active.recordMojangOnly();
            active.abortBeforeSubmission(ownershipFailure);
            return null;
        }

        long meshRevision = Integer.toUnsignedLong(
            System.identityHashCode(sectionMesh)
        );
        int indexTypeKey =
            indexType == null ? 0 : indexType.ordinal() + 1;
        int slot = active.acquireSlot(sectionNode);
        if (slot < 0) {
            active.abortBeforeSubmissionPreservingFailure();
            return null;
        }
        if (
            active.compatible(
                slot,
                this.worldGeneration,
                this.rendererGeneration,
                deviceGeneration,
                reloadEpoch,
                sectionMesh,
                meshRevision,
                vertexBuffer,
                slice.vertexBufferOffset(),
                indexBuffer,
                slice.indexBufferOffset(),
                firstIndex,
                sectionDraw.indexCount(),
                baseVertex,
                indexTypeKey,
                pipeline,
                pipelineKey,
                vertexFormat,
                CHUNK_SECTION_DESCRIPTOR_KEY,
                blockAtlas,
                materialKey,
                renderOffset.getX(),
                renderOffset.getY(),
                renderOffset.getZ()
            )
        ) {
            SolidDrawTemplate template =
                (SolidDrawTemplate)active.reuse(slot);
            if (template == null) {
                active.quarantine(slot);
                active.abortBeforeSubmission(
                    PersistentDrawTemplateTable.Failure
                        .VALIDATION_FAILED
                );
                return null;
            }
            template.uniformIndex = uboIndex;
            return template.draw;
        }

        active.beginBuild(slot);
        SolidDrawTemplate template;
        try {
            template = new SolidDrawTemplate(
                vertexBuffer,
                indexBuffer,
                indexType,
                firstIndex,
                sectionDraw.indexCount(),
                baseVertex,
                uboIndex
            );
            active.publishReady(
                slot,
                this.worldGeneration,
                this.rendererGeneration,
                deviceGeneration,
                reloadEpoch,
                sectionMesh,
                meshRevision,
                vertexBuffer,
                slice.vertexBufferOffset(),
                indexBuffer,
                slice.indexBufferOffset(),
                firstIndex,
                sectionDraw.indexCount(),
                baseVertex,
                indexTypeKey,
                pipeline,
                pipelineKey,
                vertexFormat,
                CHUNK_SECTION_DESCRIPTOR_KEY,
                blockAtlas,
                materialKey,
                renderOffset.getX(),
                renderOffset.getY(),
                renderOffset.getZ(),
                template
            );
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            active.quarantine(slot);
            throw error;
        }
        return template.draw;
    }

    private PersistentDrawTemplateTable.Failure
        validateSolidOwnership(
        SectionRenderDispatcher.RenderSection section,
        long sectionNode,
        BlockPos renderOffset,
        SectionRenderDispatcher.RenderSectionBufferSlice slice,
        SectionMesh.SectionDraw draw,
        GpuBuffer vertexBuffer,
        GpuBuffer indexBuffer,
        IndexType indexType,
        VertexFormat vertexFormat
    ) {
        int expectedX = SectionPos.sectionToBlockCoord(
            SectionPos.x(sectionNode)
        );
        int expectedY = SectionPos.sectionToBlockCoord(
            SectionPos.y(sectionNode)
        );
        int expectedZ = SectionPos.sectionToBlockCoord(
            SectionPos.z(sectionNode)
        );
        if (
            section.getSectionNode() != sectionNode
                || renderOffset.getX() != expectedX
                || renderOffset.getY() != expectedY
                || renderOffset.getZ() != expectedZ
        ) {
            return PersistentDrawTemplateTable.Failure
                .SECTION_IDENTITY_MISMATCH;
        }
        if (
            vertexBuffer.isClosed()
                || (vertexBuffer.usage() & GpuBuffer.USAGE_VERTEX) == 0
        ) {
            return PersistentDrawTemplateTable.Failure
                .VERTEX_BUFFER_INVALID;
        }
        if (
            vertexFormat.getVertexSize() <= 0
                || slice.vertexBufferOffset()
                    % vertexFormat.getVertexSize() != 0L
        ) {
            return PersistentDrawTemplateTable.Failure
                .VERTEX_LAYOUT_INVALID;
        }
        if (draw.indexCount() <= 0) {
            return PersistentDrawTemplateTable.Failure
                .DRAW_RANGE_INVALID;
        }
        if (!draw.hasCustomIndexBuffer()) {
            return indexBuffer == null
                && indexType == null
                && slice.indexBufferOffset() == 0L
                    ? PersistentDrawTemplateTable.Failure.NONE
                    : PersistentDrawTemplateTable.Failure
                        .INDEX_BUFFER_INVALID;
        }
        return indexBuffer != null
            && indexType != null
            && !indexBuffer.isClosed()
            && (indexBuffer.usage() & GpuBuffer.USAGE_INDEX) != 0
            && slice.indexBufferOffset() % indexType.bytes == 0L
                ? PersistentDrawTemplateTable.Failure.NONE
                : PersistentDrawTemplateTable.Failure
                    .INDEX_BUFFER_INVALID;
    }

    private PersistentDrawTemplateTable ensureTable() {
        if (this.table != null) {
            return this.table;
        }
        try {
            this.table = new PersistentDrawTemplateTable(
                de.morau.blockframe.core.BlockframeRuntime
                    .memoryBudgets()
            );
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            this.logFailureOnce(error);
            return null;
        }
        return this.table;
    }

    private void logFailureOnce(Throwable error) {
        if (!this.failureLogged) {
            this.failureLogged = true;
            LOGGER.warn(
                "Opaque-solid draw-template cache used Mojang fallback",
                error
            );
        }
    }

    private void logPipelineFailureOnce(
        RenderPipeline pipeline,
        VertexFormat vertexFormat
    ) {
        if (this.pipelineFailureLogged) {
            return;
        }
        this.pipelineFailureLogged = true;
        List<BindGroupLayout> layouts =
            pipeline.getBindGroupLayouts();
        List<BindGroupLayout> expectedLayouts = List.of(
            BindGroupLayouts.GLOBALS,
            BindGroupLayouts.FOG,
            BindGroupLayouts.SAMPLER0_SAMPLER2,
            BindGroupLayouts.PROJECTION,
            BindGroupLayouts.CHUNK_SECTION
        );
        LOGGER.warn(
            "Opaque-solid pipeline ABI rejected: location={} "
                + "vertexShader={} fragmentShader={} "
                + "vertexFormatBlock={} vertexSize={} topology={} "
                + "bindGroupCount={} expectedBindGroupIdentities={} "
                + "expectedBindGroupStructure={} "
                + "knownVulkanExtension={} "
                + "samplers={} uniforms={} "
                + "expectedSamplers={} expectedUniforms={}",
            pipeline.getLocation(),
            pipeline.getVertexShader(),
            pipeline.getFragmentShader(),
            vertexFormat == DefaultVertexFormat.BLOCK,
            vertexFormat == null ? -1 : vertexFormat.getVertexSize(),
            pipeline.getPrimitiveTopology(),
            layouts.size(),
            hasExpectedTerrainBindGroupIdentities(layouts),
            hasExpectedTerrainBindGroups(layouts),
            hasKnownVulkanTerrainExtension(layouts),
            BindGroupLayout.flattenSamplers(layouts),
            BindGroupLayout.flattenUniforms(layouts),
            BindGroupLayout.flattenSamplers(expectedLayouts),
            BindGroupLayout.flattenUniforms(expectedLayouts)
        );
    }

    private static boolean isCompatibleSolidPipeline(
        RenderPipeline pipeline,
        VertexFormat vertexFormat
    ) {
        Identifier location = pipeline.getLocation();
        return vertexFormat == DefaultVertexFormat.BLOCK
            && pipeline.getPrimitiveTopology()
                == PrimitiveTopology.QUADS
            && (
                (
                    isTerrainIdentifier(
                        location,
                        "minecraft",
                        "pipeline/solid_terrain"
                    )
                        && isTerrainShader(
                            pipeline.getVertexShader(),
                            "minecraft"
                        )
                        && isTerrainShader(
                            pipeline.getFragmentShader(),
                            "minecraft"
                        )
                        && hasExpectedTerrainBindGroups(
                            pipeline.getBindGroupLayouts()
                        )
                )
                    || (
                        isTerrainIdentifier(
                            location,
                            "milkshade",
                            "pipeline/solid_terrain"
                        )
                            && isTerrainShader(
                                pipeline.getVertexShader(),
                                "milkshade"
                            )
                            && isTerrainShader(
                                pipeline.getFragmentShader(),
                                "milkshade"
                            )
                            && hasKnownVulkanTerrainExtension(
                                pipeline.getBindGroupLayouts()
                            )
                    )
            );
    }

    private static boolean isTerrainShader(
        Identifier shader,
        String namespace
    ) {
        return isTerrainIdentifier(
            shader,
            namespace,
            "core/terrain"
        );
    }

    private static boolean isTerrainIdentifier(
        Identifier identifier,
        String namespace,
        String path
    ) {
        return identifier != null
            && namespace.equals(identifier.getNamespace())
            && path.equals(identifier.getPath());
    }

    static boolean hasExpectedTerrainBindGroups(
        List<BindGroupLayout> layouts
    ) {
        return layouts.size() == 5
            && hasExpectedTerrainBindGroupStructure(layouts);
    }

    static boolean hasKnownVulkanTerrainExtension(
        List<BindGroupLayout> layouts
    ) {
        return layouts.size() == 6
            && hasExpectedTerrainBindGroupStructure(layouts)
            && isMilkshadeDynamicLightsLayout(layouts.get(5));
    }

    private static boolean hasExpectedTerrainBindGroupIdentities(
        List<BindGroupLayout> layouts
    ) {
        return layouts.size() == 5
            && layouts.get(0) == BindGroupLayouts.GLOBALS
            && layouts.get(1) == BindGroupLayouts.FOG
            && layouts.get(2)
                == BindGroupLayouts.SAMPLER0_SAMPLER2
            && layouts.get(3) == BindGroupLayouts.PROJECTION
            && layouts.get(4) == BindGroupLayouts.CHUNK_SECTION;
    }

    private static boolean hasExpectedTerrainBindGroupStructure(
        List<BindGroupLayout> layouts
    ) {
        return layouts.size() >= 5
            && sameBindGroup(
                layouts.get(0),
                BindGroupLayouts.GLOBALS
            )
            && sameBindGroup(
                layouts.get(1),
                BindGroupLayouts.FOG
            )
            && sameBindGroup(
                layouts.get(2),
                BindGroupLayouts.SAMPLER0_SAMPLER2
            )
            && sameBindGroup(
                layouts.get(3),
                BindGroupLayouts.PROJECTION
            )
            && sameBindGroup(
                layouts.get(4),
                BindGroupLayouts.CHUNK_SECTION
            );
    }

    private static boolean sameBindGroup(
        BindGroupLayout left,
        BindGroupLayout right
    ) {
        return left != null
            && right != null
            && left.getSamplers().equals(right.getSamplers())
            && left.getUniforms().equals(right.getUniforms());
    }

    private static boolean isMilkshadeDynamicLightsLayout(
        BindGroupLayout layout
    ) {
        if (layout == null || !layout.getSamplers().isEmpty()) {
            return false;
        }
        List<BindGroupLayout.UniformDescription> uniforms =
            layout.getUniforms();
        if (uniforms.size() != 1) {
            return false;
        }
        BindGroupLayout.UniformDescription uniform =
            uniforms.get(0);
        return "MilkshadeDynamicLights".equals(uniform.name())
            && uniform.type() == UniformType.UNIFORM_BUFFER
            && uniform.gpuFormat() == null;
    }

    private static int pipelineKey(RenderPipeline pipeline) {
        int result = 173;
        result = 31 * result + pipeline.getLocation().hashCode();
        result = 31 * result + pipeline.getVertexShader().hashCode();
        result = 31 * result + pipeline.getFragmentShader().hashCode();
        result = 31 * result + pipeline.getBindGroupLayouts().hashCode();
        result =
            31 * result
                + System.identityHashCode(
                    pipeline.getVertexFormatBinding(0)
                );
        return result;
    }

    private static int materialKey(
        GpuTextureView atlas,
        int width,
        int height
    ) {
        int result = 173;
        result = 31 * result + System.identityHashCode(atlas);
        result = 31 * result + width;
        return 31 * result + height;
    }

    private static synchronized long nextRendererGeneration() {
        nextRendererGeneration = incrementSaturated(
            nextRendererGeneration
        );
        return nextRendererGeneration;
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static final class SolidDrawTemplate
        implements
            BiConsumer<
                GpuBufferSlice[],
                RenderPass.UniformUploader
            > {
        private final RenderPass.Draw<GpuBufferSlice[]> draw;
        private int uniformIndex;

        private SolidDrawTemplate(
            GpuBuffer vertexBuffer,
            GpuBuffer indexBuffer,
            IndexType indexType,
            int firstIndex,
            int indexCount,
            int baseVertex,
            int uniformIndex
        ) {
            this.uniformIndex = uniformIndex;
            this.draw = new RenderPass.Draw<>(
                0,
                vertexBuffer,
                indexBuffer,
                indexType,
                firstIndex,
                indexCount,
                baseVertex,
                this
            );
        }

        @Override
        public void accept(
            GpuBufferSlice[] sectionUbos,
            RenderPass.UniformUploader uploader
        ) {
            uploader.upload(
                "ChunkSection",
                sectionUbos[this.uniformIndex]
            );
        }
    }
}
