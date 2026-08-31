package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.BufferKind;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.GeometryHandle;
import de.morau.nvidiadlss.mixin.RenderPassAccessor;
import java.util.Objects;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

/**
 * Fixed-bucket Solid/Cutout encoder for the native terrain scene.
 *
 * <p>Binding arrays and indirect slices are rebuilt only when a geometry page
 * is published. The warm frame loop performs two indirect-count calls per
 * active vertex page and allocates no Java objects.</p>
 */
public final class NativeTerrainSubmissionOwner {
    public enum Result {
        SUBMITTED,
        FAILED_BEFORE_SUBMISSION,
        FAILED_AFTER_SUBMISSION
    }

    public record Metrics(
        long submittedFrames,
        long failedBeforeSubmission,
        long failedAfterSubmission,
        long indirectCalls,
        long lastCpuNanos,
        int vertexPages,
        int callsPerFrame
    ) {
    }

    private final NativeTerrainGeometryOwner geometryOwner;
    private final NativeTerrainGpuScene scene;
    private final NativeTerrainGpuSceneVulkanResources vulkan;
    private final GpuBufferSlice sharedIndex;
    private final int maximumEntries;
    private GpuBufferSlice[] vertexPages = new GpuBufferSlice[0];
    private GpuBufferSlice[] commandBuckets = new GpuBufferSlice[0];
    private GpuBufferSlice[] countBuckets = new GpuBufferSlice[0];
    private long bindingGeneration;
    private long submittedFrames;
    private long failedBeforeSubmission;
    private long failedAfterSubmission;
    private long indirectCalls;
    private long lastCpuNanos;

    public NativeTerrainSubmissionOwner(
        NativeTerrainGeometryOwner geometryOwner,
        NativeTerrainGpuScene scene,
        NativeTerrainGpuSceneVulkanResources vulkan
    ) {
        this.geometryOwner = Objects.requireNonNull(
            geometryOwner,
            "geometryOwner"
        );
        this.scene = Objects.requireNonNull(scene, "scene");
        this.vulkan = Objects.requireNonNull(vulkan, "vulkan");
        GeometryHandle indexHandle = scene.resources().require(
            BufferKind.SHARED_INDEX
        );
        this.sharedIndex =
            geometryOwner.requireVulkanSlice(indexHandle);
        if (this.sharedIndex.offset() != 0L) {
            throw new IllegalStateException(
                "shared index buffer must begin at its Vulkan buffer base"
            );
        }
        this.maximumEntries = scene.maximumEntries();
        refreshBindings();
    }

    /**
     * Refreshes only after dirty scene/geometry publication, never per frame.
     */
    public synchronized void refreshBindings() {
        NativeTerrainGpuScene.BindingTable table =
            this.scene.bindingTable();
        if (table.generation() == this.bindingGeneration) {
            long[] knownPages = table.vertexPageSerials();
            if (knownPages.length == this.vertexPages.length) {
                return;
            }
        }
        long[] pageSerials = table.vertexPageSerials();
        GpuBufferSlice[] pages =
            new GpuBufferSlice[pageSerials.length];
        GpuBufferSlice[] commands =
            new GpuBufferSlice[
                pageSerials.length
                    * NativeTerrainGpuScene
                        .BUCKETS_PER_VERTEX_PAGE
            ];
        GpuBufferSlice[] counts =
            new GpuBufferSlice[commands.length];
        long commandBytesPerBucket = Math.multiplyExact(
            (long)this.maximumEntries,
            VkDrawIndexedIndirectCommand.SIZEOF
        );
        for (int page = 0; page < pageSerials.length; page++) {
            pages[page] =
                this.geometryOwner.requireVulkanPageSlice(
                    BufferKind.VERTEX,
                    pageSerials[page]
                );
            for (
                int layer = 0;
                layer
                    < NativeTerrainGpuScene
                        .BUCKETS_PER_VERTEX_PAGE;
                layer++
            ) {
                int bucket =
                    page
                        * NativeTerrainGpuScene
                            .BUCKETS_PER_VERTEX_PAGE
                        + layer;
                commands[bucket] = this.vulkan.commandSlice().slice(
                    Math.multiplyExact(
                        (long)bucket,
                        commandBytesPerBucket
                    ),
                    commandBytesPerBucket
                );
                counts[bucket] = this.vulkan.countSlice().slice(
                    Math.multiplyExact(
                        (long)bucket,
                        Integer.BYTES
                    ),
                    Integer.BYTES
                );
            }
        }
        this.vertexPages = pages;
        this.commandBuckets = commands;
        this.countBuckets = counts;
        this.bindingGeneration = table.generation();
    }

    /**
     * The caller already owns the correct Minecraft color/depth render pass,
     * default uniforms, atlas sampler and lightmap sampler.
     */
    public Result submit(
        RenderPass renderPass,
        GpuBufferSlice dynamicTransforms
    ) {
        Objects.requireNonNull(renderPass, "renderPass");
        Objects.requireNonNull(
            dynamicTransforms,
            "dynamicTransforms"
        );
        long started = System.nanoTime();
        boolean crossedBoundary = false;
        try {
            if (!this.vulkan.beginSubmission()) {
                this.failedBeforeSubmission++;
                return Result.FAILED_BEFORE_SUBMISSION;
            }
            NativeTerrainIndirectRenderPass indirect =
                (NativeTerrainIndirectRenderPass)
                    ((RenderPassAccessor)(Object)renderPass)
                        .blockframe$backend();
            renderPass.setIndexBuffer(
                this.sharedIndex.buffer(),
                IndexType.SHORT
            );
            renderPass.setUniform(
                "DynamicTransforms",
                dynamicTransforms
            );
            for (int page = 0; page < this.vertexPages.length; page++) {
                renderPass.setVertexBuffer(
                    0,
                    this.vertexPages[page]
                );
                int solidBucket =
                    page
                        * NativeTerrainGpuScene
                            .BUCKETS_PER_VERTEX_PAGE;
                renderPass.setPipeline(NativeTerrainPipelines.SOLID);
                indirect.blockframe$prepareNativeTerrainDescriptors(
                    this.vulkan.sceneBufferView()
                );
                indirect.blockframe$drawNativeTerrainIndirectCount(
                    this.commandBuckets[solidBucket],
                    this.countBuckets[solidBucket],
                    this.maximumEntries,
                    VkDrawIndexedIndirectCommand.SIZEOF
                );
                crossedBoundary = true;
                this.indirectCalls++;

                int cutoutBucket = solidBucket + 1;
                renderPass.setPipeline(NativeTerrainPipelines.CUTOUT);
                indirect.blockframe$prepareNativeTerrainDescriptors(
                    this.vulkan.sceneBufferView()
                );
                indirect.blockframe$drawNativeTerrainIndirectCount(
                    this.commandBuckets[cutoutBucket],
                    this.countBuckets[cutoutBucket],
                    this.maximumEntries,
                    VkDrawIndexedIndirectCommand.SIZEOF
                );
                this.indirectCalls++;
            }
            this.vulkan.finishSubmission();
            this.submittedFrames++;
            NativeTerrainOwnershipEvidence
                .blockFrameIndirectSubmissionEncoded(
                    this.scene.evidenceToken()
                );
            return Result.SUBMITTED;
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            if (crossedBoundary) {
                this.failedAfterSubmission++;
                return Result.FAILED_AFTER_SUBMISSION;
            }
            try {
                this.vulkan.cancelBeforeSubmission();
            } catch (RuntimeException ignored) {
                // A later frame must demote if cancellation is uncertain.
            }
            this.failedBeforeSubmission++;
            return Result.FAILED_BEFORE_SUBMISSION;
        } finally {
            this.lastCpuNanos = System.nanoTime() - started;
        }
    }

    public Metrics metrics() {
        return new Metrics(
            this.submittedFrames,
            this.failedBeforeSubmission,
            this.failedAfterSubmission,
            this.indirectCalls,
            this.lastCpuNanos,
            this.vertexPages.length,
            this.vertexPages.length
                * NativeTerrainGpuScene.BUCKETS_PER_VERTEX_PAGE
        );
    }
}
