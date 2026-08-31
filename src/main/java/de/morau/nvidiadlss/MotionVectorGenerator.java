package de.morau.nvidiadlss;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanDebug;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.budget.MemoryBudgetExceededException;
import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.core.diagnostics.GpuPassDiagnostics;
import de.morau.blockframe.core.diagnostics.GpuPassIdentity;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind;
import de.morau.blockframe.core.memory.ReusableNativeBlockPool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Vulkan compute pass which creates dense, render-resolution, current-to-previous motion vectors. */
public final class MotionVectorGenerator implements AutoCloseable {
    public static final int MAX_OBJECTS = 64;
    private static final int FRAME_RING_SIZE = 3;
    static final int RELEASE_DESCRIPTOR_BINDING_COUNT = 5;
    static final int DIAGNOSTIC_DESCRIPTOR_BINDING_COUNT = 8;
    static final int COMBINED_IMAGE_DESCRIPTORS = FRAME_RING_SIZE * 2;
    static final int RELEASE_STORAGE_IMAGE_DESCRIPTORS =
        FRAME_RING_SIZE * 2;
    static final int DIAGNOSTIC_STORAGE_IMAGE_DESCRIPTORS =
        FRAME_RING_SIZE * 5;
    static final int UNIFORM_BUFFER_DESCRIPTORS = FRAME_RING_SIZE;
    static final int DESCRIPTOR_COUNT =
        COMBINED_IMAGE_DESCRIPTORS
            + DIAGNOSTIC_STORAGE_IMAGE_DESCRIPTORS
            + UNIFORM_BUFFER_DESCRIPTORS;
    private static final int HEADER_FLOATS = 68;
    private static final int OBJECT_FLOATS = 20;
    private static final int UNIFORM_BYTES = (
        HEADER_FLOATS
            + MAX_OBJECTS * OBJECT_FLOATS
            + ThirdPersonGeometryBatch.MAX_PARTS
                * ThirdPersonGeometryBatch.PART_FLOATS
    ) * Float.BYTES;
    private static final String RELEASE_SHADER =
        "/assets/nvidia_dlss/native/win-x64/motion_vectors.comp.spv";
    private static final String DIAGNOSTIC_SHADER =
        "/assets/nvidia_dlss/native/win-x64/"
            + "motion_vectors.debug.comp.spv";
    private static final int SHADER_STAGING_BYTES = 32 * 1024;
    private static final long GPU_ALLOCATION_ALIGNMENT = 64L * 1024L;

    private final VulkanDevice backend;
    private final MemoryBudgetManager budgets;
    private final ShaderResourceInventory inventory;
    private final boolean developerDiagnostics;
    private static MotionVectorGenerator retainedFailedConstruction;
    private long budgetLease;
    private long shaderModule;
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private long sampler;
    private final long[] descriptorSets = new long[FRAME_RING_SIZE];
    private final GpuBuffer[] frameBuffers = new GpuBuffer[FRAME_RING_SIZE];
    private final boolean[] frameBufferCloseFailed =
        new boolean[FRAME_RING_SIZE];
    private int ringIndex;
    private boolean closing;
    private boolean closePrepared;
    private boolean closed;
    private boolean rawResourcesDestroyed;
    private boolean backendAlive = true;

    public MotionVectorGenerator(VulkanDevice backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.budgets = BlockframeRuntime.memoryBudgets();
        this.inventory = BlockframeRuntime.shaderResources();
        this.developerDiagnostics = DeveloperDiagnostics.ENABLED;
        if (!retryRetainedFailedConstruction()) {
            throw new IllegalStateException(
                "previous motion-resource construction cleanup remains unconfirmed"
            );
        }
        try {
            /*
             * vkCreateShaderModule consumes pCode synchronously. Keeping the
             * staging owner scoped to this call prevents either a pooled view
             * or a direct allocation from escaping into later GPU work.
             */
            try (ShaderCodeOwner shaderCode = loadShader()) {
                long requestedBytes = Math.addExact(
                    Math.multiplyExact(
                        (long)UNIFORM_BYTES,
                        FRAME_RING_SIZE
                    ),
                    shaderCode.byteCount()
                );
                long committedBytes = Math.addExact(
                    Math.multiplyExact(
                        align(
                            UNIFORM_BYTES,
                            GPU_ALLOCATION_ALIGNMENT
                        ),
                        FRAME_RING_SIZE
                    ),
                    align(
                        shaderCode.byteCount(),
                        GPU_ALLOCATION_ALIGNMENT
                    )
                );
                this.budgetLease = this.budgets.tryReserve(
                    MemoryKind.VRAM,
                    MemoryCategory.SHADER_RESOURCES,
                    requestedBytes,
                    committedBytes,
                    null
                );
                if (this.budgetLease == 0L) {
                    throw new MemoryBudgetExceededException(
                        "motion compute resources rejected by shader VRAM budget"
                    );
                }
                VulkanCreation shaderCreation =
                    shaderCode.createShaderModule(
                    this.backend
                );
                this.shaderModule = shaderCreation.handle();
                if (this.shaderModule != 0L) {
                    this.inventory.created(
                        ResourceKind.SHADER_MODULE
                    );
                }
                this.recordCreationOutcome(
                    ResourceKind.SHADER_MODULE,
                    shaderCreation.result(),
                    this.shaderModule
                );
                check(
                    shaderCreation.result(),
                    "Motion-Shadermodul"
                );
                requireHandle(
                    this.shaderModule,
                    "Motion-Shadermodul"
                );
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.callocLong(1);

                int descriptorBindingCount = this.developerDiagnostics
                    ? DIAGNOSTIC_DESCRIPTOR_BINDING_COUNT
                    : RELEASE_DESCRIPTOR_BINDING_COUNT;
                VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(
                        descriptorBindingCount,
                        stack
                    );
                descriptorBinding(bindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                descriptorBinding(bindings.get(1), 2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
                descriptorBinding(bindings.get(2), 3, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                if (this.developerDiagnostics) {
                    descriptorBinding(bindings.get(3), 4, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
                    descriptorBinding(bindings.get(4), 5, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
                    descriptorBinding(bindings.get(5), 7, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
                    descriptorBinding(bindings.get(6), 8, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                    descriptorBinding(bindings.get(7), 9, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
                } else {
                    descriptorBinding(bindings.get(3), 7, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
                    descriptorBinding(bindings.get(4), 8, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                }
                VkDescriptorSetLayoutCreateInfo setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
                handle.put(0, 0L);
                int descriptorLayoutResult =
                    VK12.vkCreateDescriptorSetLayout(
                        backend.vkDevice(),
                        setLayoutInfo,
                        null,
                        handle
                    );
                this.descriptorSetLayout = handle.get(0);
                if (this.descriptorSetLayout != 0L) {
                    this.inventory.created(
                        ResourceKind.DESCRIPTOR_SET_LAYOUT
                    );
                }
                this.recordCreationOutcome(
                    ResourceKind.DESCRIPTOR_SET_LAYOUT,
                    descriptorLayoutResult,
                    this.descriptorSetLayout
                );
                check(
                    descriptorLayoutResult,
                    "Motion-Descriptorlayout"
                );
                requireHandle(
                    this.descriptorSetLayout,
                    "Motion-Descriptorlayout"
                );

                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(this.descriptorSetLayout));
                handle.put(0, 0L);
                int pipelineLayoutResult =
                    VK12.vkCreatePipelineLayout(
                        backend.vkDevice(),
                        pipelineLayoutInfo,
                        null,
                        handle
                    );
                this.pipelineLayout = handle.get(0);
                if (this.pipelineLayout != 0L) {
                    this.inventory.created(
                        ResourceKind.PIPELINE_LAYOUT
                    );
                }
                this.recordCreationOutcome(
                    ResourceKind.PIPELINE_LAYOUT,
                    pipelineLayoutResult,
                    this.pipelineLayout
                );
                check(
                    pipelineLayoutResult,
                    "Motion-Pipelinelayout"
                );
                requireHandle(
                    this.pipelineLayout,
                    "Motion-Pipelinelayout"
                );

                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT).module(this.shaderModule).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0).sType$Default().stage(stage).layout(this.pipelineLayout);
                handle.put(0, 0L);
                int pipelineResult =
                    VK12.vkCreateComputePipelines(
                        backend.vkDevice(),
                        0L,
                        pipelineInfo,
                        null,
                        handle
                    );
                this.pipeline = handle.get(0);
                if (this.pipeline != 0L) {
                    this.inventory.created(
                        ResourceKind.COMPUTE_PIPELINE
                    );
                }
                this.recordCreationOutcome(
                    ResourceKind.COMPUTE_PIPELINE,
                    pipelineResult,
                    this.pipeline
                );
                check(
                    pipelineResult,
                    "Motion-Computepipeline"
                );
                requireHandle(
                    this.pipeline,
                    "Motion-Computepipeline"
                );

                VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default();
                samplerInfo.magFilter(VK12.VK_FILTER_NEAREST).minFilter(VK12.VK_FILTER_NEAREST)
                    .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0F).maxLod(0.0F).maxAnisotropy(1.0F);
                handle.put(0, 0L);
                int samplerResult = VK12.vkCreateSampler(
                    backend.vkDevice(),
                    samplerInfo,
                    null,
                    handle
                );
                this.sampler = handle.get(0);
                if (this.sampler != 0L) {
                    this.inventory.created(
                        ResourceKind.RAW_DEPTH_SAMPLER
                    );
                }
                this.recordCreationOutcome(
                    ResourceKind.RAW_DEPTH_SAMPLER,
                    samplerResult,
                    this.sampler
                );
                check(samplerResult, "Motion-Depthsampler");
                requireHandle(
                    this.sampler,
                    "Motion-Depthsampler"
                );

                VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
                poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(COMBINED_IMAGE_DESCRIPTORS);
                poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(
                    this.developerDiagnostics
                        ? DIAGNOSTIC_STORAGE_IMAGE_DESCRIPTORS
                        : RELEASE_STORAGE_IMAGE_DESCRIPTORS
                );
                poolSizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(UNIFORM_BUFFER_DESCRIPTORS);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(FRAME_RING_SIZE).pPoolSizes(poolSizes);
                handle.put(0, 0L);
                int descriptorPoolResult =
                    VK12.vkCreateDescriptorPool(
                        backend.vkDevice(),
                        poolInfo,
                        null,
                        handle
                    );
                this.descriptorPool = handle.get(0);
                if (this.descriptorPool != 0L) {
                    this.inventory.created(
                        ResourceKind.DESCRIPTOR_POOL
                    );
                }
                this.recordCreationOutcome(
                    ResourceKind.DESCRIPTOR_POOL,
                    descriptorPoolResult,
                    this.descriptorPool
                );
                check(
                    descriptorPoolResult,
                    "Motion-Descriptorpool"
                );
                requireHandle(
                    this.descriptorPool,
                    "Motion-Descriptorpool"
                );

                LongBuffer layouts = stack.mallocLong(FRAME_RING_SIZE);
                for (int i = 0; i < FRAME_RING_SIZE; i++) layouts.put(i, this.descriptorSetLayout);
                VkDescriptorSetAllocateInfo allocation = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.descriptorPool).pSetLayouts(layouts);
                LongBuffer sets = stack.callocLong(FRAME_RING_SIZE);
                int descriptorSetResult =
                    VK12.vkAllocateDescriptorSets(
                        backend.vkDevice(),
                        allocation,
                        sets
                    );
                if (descriptorSetResult != VK12.VK_SUCCESS) {
                    this.inventory.creationFailed(
                        ResourceKind.DESCRIPTOR_SET
                    );
                    this.inventory.creationFailed(
                        ResourceKind.DESCRIPTOR
                    );
                }
                check(
                    descriptorSetResult,
                    "Motion-Descriptorsets"
                );
                int allocatedDescriptorSets = 0;
                for (int i = 0; i < FRAME_RING_SIZE; i++) {
                    this.descriptorSets[i] = sets.get(i);
                    if (this.descriptorSets[i] != 0L) {
                        allocatedDescriptorSets++;
                    }
                }
                if (allocatedDescriptorSets != 0) {
                    this.inventory.created(
                        ResourceKind.DESCRIPTOR_SET,
                        allocatedDescriptorSets
                    );
                    this.inventory.created(
                        ResourceKind.DESCRIPTOR,
                        allocatedDescriptorSets
                            * descriptorBindingCount
                    );
                }
                if (allocatedDescriptorSets != FRAME_RING_SIZE) {
                    this.inventory.creationFailed(
                        ResourceKind.DESCRIPTOR_SET
                    );
                    this.inventory.creationFailed(
                        ResourceKind.DESCRIPTOR
                    );
                    throw new IllegalStateException(
                        "Motion-Descriptorsets lieferten "
                            + allocatedDescriptorSets
                            + "/"
                            + FRAME_RING_SIZE
                            + " gueltige Handles"
                    );
                }

                for (int i = 0; i < FRAME_RING_SIZE; i++) {
                    int slot = i;
                    try {
                        this.frameBuffers[i] =
                            Objects.requireNonNull(
                                backend.createBuffer(
                                    () -> "NVIDIA DLSS / Motion Frame Data " + slot,
                                    GpuBuffer.USAGE_MAP_WRITE
                                        | GpuBuffer.USAGE_UNIFORM,
                                    UNIFORM_BYTES
                                ),
                                "motion frame buffer"
                            );
                    } catch (Throwable error) {
                        this.inventory.creationFailed(
                            ResourceKind.MANAGED_UNIFORM_BUFFER
                        );
                        throw error;
                    }
                    this.inventory.created(
                        ResourceKind.MANAGED_UNIFORM_BUFFER
                    );
                }
                if (this.developerDiagnostics) {
                    this.labelNativeObjects();
                }
            }
        } catch (Throwable error) {
            boolean rollbackComplete;
            try {
                rollbackComplete = this.rollbackCreatedResources();
            } catch (Throwable cleanupError) {
                rollbackComplete = false;
                error.addSuppressed(cleanupError);
            }
            if (!rollbackComplete) {
                retainFailedConstruction(this);
            }
            throw error;
        }
        NvidiaDlssMod.LOGGER.info("DLSS Motion-Vector-Compute-Pipeline bereit (RG16F, {} Objekttransformationen)", MAX_OBJECTS);
    }

    public void dispatch(
        VkCommandBuffer commandBuffer,
        VulkanGpuTextureView currentColor,
        VulkanGpuTextureView currentDepth,
        VulkanGpuTextureView motionOutput,
        VulkanGpuTextureView depthDebugOutput,
        VulkanGpuTextureView motionDebugOutput,
        VulkanGpuTextureView motionValidityOutput,
        VulkanGpuTextureView transparencyHintOutput,
        Matrix4f inverseCurrentViewProjection,
        Matrix4f previousViewProjection,
        Matrix4f currentClipToPreviousClip,
        int width,
        int height,
        float jitterX,
        float jitterY,
        boolean reset,
        boolean captureDebug,
        List<MotionObject> objects
    ) {
        dispatchInternal(
            commandBuffer,
            currentColor,
            currentDepth,
            motionOutput,
            depthDebugOutput,
            motionDebugOutput,
            motionValidityOutput,
            transparencyHintOutput,
            inverseCurrentViewProjection,
            previousViewProjection,
            currentClipToPreviousClip,
            width,
            height,
            jitterX,
            jitterY,
            reset,
            captureDebug,
            Objects.requireNonNull(objects, "objects"),
            null
        );
    }

    public void dispatch(
        VkCommandBuffer commandBuffer,
        VulkanGpuTextureView currentColor,
        VulkanGpuTextureView currentDepth,
        VulkanGpuTextureView motionOutput,
        VulkanGpuTextureView depthDebugOutput,
        VulkanGpuTextureView motionDebugOutput,
        VulkanGpuTextureView motionValidityOutput,
        VulkanGpuTextureView transparencyHintOutput,
        Matrix4f inverseCurrentViewProjection,
        Matrix4f previousViewProjection,
        Matrix4f currentClipToPreviousClip,
        int width,
        int height,
        float jitterX,
        float jitterY,
        boolean reset,
        boolean captureDebug,
        MotionObjectBatch objects
    ) {
        dispatchInternal(
            commandBuffer,
            currentColor,
            currentDepth,
            motionOutput,
            depthDebugOutput,
            motionDebugOutput,
            motionValidityOutput,
            transparencyHintOutput,
            inverseCurrentViewProjection,
            previousViewProjection,
            currentClipToPreviousClip,
            width,
            height,
            jitterX,
            jitterY,
            reset,
            captureDebug,
            null,
            Objects.requireNonNull(objects, "objects")
        );
    }

    private void dispatchInternal(
        VkCommandBuffer commandBuffer,
        VulkanGpuTextureView currentColor,
        VulkanGpuTextureView currentDepth,
        VulkanGpuTextureView motionOutput,
        VulkanGpuTextureView depthDebugOutput,
        VulkanGpuTextureView motionDebugOutput,
        VulkanGpuTextureView motionValidityOutput,
        VulkanGpuTextureView transparencyHintOutput,
        Matrix4f inverseCurrentViewProjection,
        Matrix4f previousViewProjection,
        Matrix4f currentClipToPreviousClip,
        int width,
        int height,
        float jitterX,
        float jitterY,
        boolean reset,
        boolean captureDebug,
        List<MotionObject> legacyObjects,
        MotionObjectBatch batch
    ) {
        if (this.closing || this.closed) {
            throw new IllegalStateException(
                "Motion-Vector-Pipeline ist geschlossen"
            );
        }
        int slot = this.ringIndex;
        this.ringIndex = nextFrameRingIndex(slot);
        writeFrameData(this.frameBuffers[slot], inverseCurrentViewProjection, previousViewProjection,
            currentClipToPreviousClip, width, height, jitterX, jitterY, reset, captureDebug,
            legacyObjects, batch);
        if (this.developerDiagnostics) {
            updateDiagnosticDescriptors(
                slot,
                currentColor,
                currentDepth,
                motionOutput,
                depthDebugOutput,
                motionDebugOutput,
                motionValidityOutput,
                transparencyHintOutput
            );
        } else {
            updateReleaseDescriptors(
                slot,
                currentColor,
                currentDepth,
                motionOutput,
                transparencyHintOutput
            );
        }

        VulkanDebug debug = null;
        boolean debugGroup = false;
        if (this.developerDiagnostics) {
            debug = this.backend.instance().debug();
            debugGroup = GpuPassDiagnostics.beginDebugGroup(
                debug,
                commandBuffer,
                GpuPassIdentity.MOTION_COMPUTE
            );
        }
        try {
            memoryBarrier(commandBuffer, 65536L, 98304L, 2048L, 98304L);
            VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VK12.vkCmdBindDescriptorSets(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout,
                    0, stack.longs(this.descriptorSets[slot]), null);
            }
            VK12.vkCmdDispatch(commandBuffer, (width + 15) / 16, (height + 15) / 16, 1);
            memoryBarrier(commandBuffer, 2048L, 65536L, 65536L, 32768L);
        } finally {
            if (this.developerDiagnostics) {
                GpuPassDiagnostics.endDebugGroup(
                    debug,
                    commandBuffer,
                    debugGroup
                );
            }
        }
        if (this.developerDiagnostics) {
            BlockframeRuntime.recordMotionComputePass();
        }
    }

    static int nextFrameRingIndex(int slot) {
        return slot == FRAME_RING_SIZE - 1
            ? 0
            : slot + 1;
    }

    private void writeFrameData(
        GpuBuffer buffer,
        Matrix4f inverseCurrentViewProjection,
        Matrix4f previousViewProjection,
        Matrix4f currentClipToPreviousClip,
        int width,
        int height,
        float jitterX,
        float jitterY,
        boolean reset,
        boolean captureDebug,
        List<MotionObject> legacyObjects,
        MotionObjectBatch batch
    ) {
        int count = batch != null
            ? batch.size()
            : Objects.requireNonNull(
                legacyObjects,
                "legacyObjects"
            ).size();
        if (
            TemporalResetPolicy.motionObjectCapacityExceeded(
                count,
                MAX_OBJECTS
            )
        ) {
            throw new IllegalArgumentException(
                "motion-object transport exceeds shader capacity: "
                    + count
                    + " > "
                    + MAX_OBJECTS
            );
        }
        ThirdPersonGeometryBatch articulated =
            ThirdPersonGeometryMotion.dispatchBatch();
        try (GpuBufferSlice.MappedView mapped = buffer.map(false, true)) {
            ByteBuffer bytes = mapped.data().order(ByteOrder.nativeOrder());
            bytes.clear();
            putMatrix(bytes, inverseCurrentViewProjection);
            putMatrix(bytes, previousViewProjection);
            putMatrix(bytes, currentClipToPreviousClip);
            bytes.putFloat(width).putFloat(height).putFloat(jitterX).putFloat(jitterY);
            bytes.putInt(count).putInt(reset ? 1 : 0).putInt(0)
                .putInt(
                    this.developerDiagnostics && captureDebug
                        ? 1
                        : 0
                );
            bytes.putFloat(articulated.minX())
                .putFloat(articulated.minY())
                .putFloat(articulated.minZ())
                .putFloat(0.0F);
            bytes.putFloat(articulated.maxX())
                .putFloat(articulated.maxY())
                .putFloat(articulated.maxZ())
                .putFloat(0.0F);
            bytes.putInt(articulated.size())
                .putInt(articulated.overflow() ? 1 : 0)
                .putInt(0)
                .putInt(0);
            if (batch != null) {
                for (int i = 0; i < count; i++) {
                    batch.writeObject(i, bytes);
                }
            } else {
                for (int i = 0; i < count; i++) {
                    putObject(bytes, legacyObjects.get(i));
                }
            }
            int articulatedOffset = (
                HEADER_FLOATS + MAX_OBJECTS * OBJECT_FLOATS
            ) * Float.BYTES;
            bytes.position(articulatedOffset);
            articulated.writeParts(bytes);
            while (bytes.position() < UNIFORM_BYTES) bytes.put((byte)0);
        }
    }

    private void updateReleaseDescriptors(
        int slot,
        VulkanGpuTextureView currentColor,
        VulkanGpuTextureView currentDepth,
        VulkanGpuTextureView motionOutput,
        VulkanGpuTextureView transparencyHintOutput
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer currentInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(this.sampler).imageView(currentDepth.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer motionInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(0L).imageView(motionOutput.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer transparencyHintInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(0L).imageView(transparencyHintOutput.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer currentColorInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(this.sampler).imageView(currentColor.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(((VulkanGpuBuffer)this.frameBuffers[slot]).vkBuffer()).offset(0L).range(UNIFORM_BYTES);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(RELEASE_DESCRIPTOR_BINDING_COUNT, stack);
            descriptorWrite(writes.get(0), this.descriptorSets[slot], 0, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(currentInfo);
            descriptorWrite(writes.get(1), this.descriptorSets[slot], 2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(motionInfo);
            descriptorWrite(writes.get(2), this.descriptorSets[slot], 3, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(bufferInfo);
            descriptorWrite(writes.get(3), this.descriptorSets[slot], 7, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(transparencyHintInfo);
            descriptorWrite(writes.get(4), this.descriptorSets[slot], 8, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(currentColorInfo);
            VK12.vkUpdateDescriptorSets(this.backend.vkDevice(), writes, null);
        }
    }

    private void updateDiagnosticDescriptors(
        int slot,
        VulkanGpuTextureView currentColor,
        VulkanGpuTextureView currentDepth,
        VulkanGpuTextureView motionOutput,
        VulkanGpuTextureView depthDebugOutput,
        VulkanGpuTextureView motionDebugOutput,
        VulkanGpuTextureView motionValidityOutput,
        VulkanGpuTextureView transparencyHintOutput
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer currentInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(this.sampler).imageView(currentDepth.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer motionInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(0L).imageView(motionOutput.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer depthDebugInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(0L).imageView(depthDebugOutput.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer motionDebugInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(0L).imageView(motionDebugOutput.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer motionValidityInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(0L).imageView(motionValidityOutput.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer transparencyHintInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(0L).imageView(transparencyHintOutput.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer currentColorInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(this.sampler).imageView(currentColor.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(((VulkanGpuBuffer)this.frameBuffers[slot]).vkBuffer()).offset(0L).range(UNIFORM_BYTES);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(DIAGNOSTIC_DESCRIPTOR_BINDING_COUNT, stack);
            descriptorWrite(writes.get(0), this.descriptorSets[slot], 0, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(currentInfo);
            descriptorWrite(writes.get(1), this.descriptorSets[slot], 2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(motionInfo);
            descriptorWrite(writes.get(2), this.descriptorSets[slot], 3, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(bufferInfo);
            descriptorWrite(writes.get(3), this.descriptorSets[slot], 4, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(depthDebugInfo);
            descriptorWrite(writes.get(4), this.descriptorSets[slot], 5, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(motionDebugInfo);
            descriptorWrite(writes.get(5), this.descriptorSets[slot], 7, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(transparencyHintInfo);
            descriptorWrite(writes.get(6), this.descriptorSets[slot], 8, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(currentColorInfo);
            descriptorWrite(writes.get(7), this.descriptorSets[slot], 9, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(motionValidityInfo);
            VK12.vkUpdateDescriptorSets(this.backend.vkDevice(), writes, null);
        }
    }

    private static void descriptorBinding(VkDescriptorSetLayoutBinding binding, int index, int type) {
        binding.binding(index).descriptorType(type).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
    }

    private static VkWriteDescriptorSet descriptorWrite(VkWriteDescriptorSet write, long set, int binding, int type) {
        return write.sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1).descriptorType(type);
    }

    private static void putMatrix(ByteBuffer bytes, Matrix4f matrix) {
        bytes.putFloat(matrix.m00()).putFloat(matrix.m01())
            .putFloat(matrix.m02()).putFloat(matrix.m03());
        bytes.putFloat(matrix.m10()).putFloat(matrix.m11())
            .putFloat(matrix.m12()).putFloat(matrix.m13());
        bytes.putFloat(matrix.m20()).putFloat(matrix.m21())
            .putFloat(matrix.m22()).putFloat(matrix.m23());
        bytes.putFloat(matrix.m30()).putFloat(matrix.m31())
            .putFloat(matrix.m32()).putFloat(matrix.m33());
    }

    private static void putObject(ByteBuffer bytes, MotionObject object) {
        bytes.putFloat((float)object.minX).putFloat((float)object.minY).putFloat((float)object.minZ).putFloat(0.0F);
        bytes.putFloat((float)object.maxX).putFloat((float)object.maxY).putFloat((float)object.maxZ).putFloat(0.0F);
        bytes.putFloat((float)object.previousX).putFloat((float)object.previousY).putFloat((float)object.previousZ).putFloat(0.0F);
        bytes.putFloat((float)object.currentX).putFloat((float)object.currentY).putFloat((float)object.currentZ).putFloat(0.0F);
        bytes.putFloat(object.currentYaw).putFloat(object.previousYaw).putFloat(0.0F).putFloat(0.0F);
    }

    private static void memoryBarrier(VkCommandBuffer commandBuffer, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    static String shaderResource(boolean developerDiagnostics) {
        return developerDiagnostics
            ? DIAGNOSTIC_SHADER
            : RELEASE_SHADER;
    }

    private static ShaderCodeOwner loadShader() {
        String shaderResource = shaderResource(
            DeveloperDiagnostics.ENABLED
        );
        ReusableNativeBlockPool pool =
            BlockframeRuntime.nativeStagingPoolOrNull();
        if (pool != null) {
            try {
                ShaderCodeOwner pooled = tryLoadShaderFromPool(
                    pool,
                    shaderResource
                );
                if (pooled != null) {
                    return pooled;
                }
            } catch (RuntimeException error) {
                NvidiaDlssMod.LOGGER.warn(
                    "Optionaler Motion-Shader-Staging-Pool ist fehlgeschlagen; Direct-Fallback wird verwendet",
                    error
                );
            }
        }
        return loadShaderDirect(shaderResource);
    }

    private static ShaderCodeOwner tryLoadShaderFromPool(
        ReusableNativeBlockPool pool,
        String shaderResource
    ) {
        long token = pool.tryBorrow(SHADER_STAGING_BYTES);
        if (token == 0L) {
            return null;
        }

        boolean borrowTransferred = false;
        try {
            ByteBuffer staging = pool.buffer(
                token,
                SHADER_STAGING_BYTES
            );
            try (
                InputStream input =
                    MotionVectorGenerator.class.getResourceAsStream(
                        shaderResource
                    )
            ) {
                if (input == null) {
                    throw new IllegalStateException(
                        "Motion-SPIR-V-Ressource fehlt"
                    );
                }
                if (!readShaderIntoFixedBuffer(input, staging)) {
                    return null;
                }
            }

            ShaderCodeOwner result = new PooledShaderCodeOwner(
                staging,
                pool,
                token
            );
            borrowTransferred = true;
            return result;
        } catch (Exception error) {
            throw new IllegalStateException(
                "Motion-SPIR-V konnte nicht aus dem Staging-Pool geladen werden",
                error
            );
        } finally {
            if (!borrowTransferred) {
                pool.release(token);
            }
        }
    }

    static boolean readShaderIntoFixedBuffer(
        InputStream input,
        ByteBuffer target
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(target, "target");
        target.clear();
        ReadableByteChannel channel = Channels.newChannel(input);
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) {
                target.flip();
                return true;
            }
            if (read == 0) {
                int next = input.read();
                if (next < 0) {
                    target.flip();
                    return true;
                }
                target.put((byte)next);
            }
        }
        if (input.read() >= 0) {
            return false;
        }
        target.flip();
        return true;
    }

    private static ShaderCodeOwner loadShaderDirect(
        String shaderResource
    ) {
        ByteBuffer result = null;
        try (
            InputStream input = MotionVectorGenerator.class
                .getResourceAsStream(shaderResource)
        ) {
            if (input == null) throw new IllegalStateException("Motion-SPIR-V-Ressource fehlt");
            byte[] bytes = input.readAllBytes();
            result = MemoryUtil.memAlloc(bytes.length).order(ByteOrder.nativeOrder());
            result.put(bytes).flip();
            return new DirectShaderCodeOwner(result);
        } catch (Throwable error) {
            if (result != null) {
                MemoryUtil.memFree(result);
            }
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException("Motion-SPIR-V konnte nicht geladen werden", error);
        }
    }

    private abstract static class ShaderCodeOwner implements AutoCloseable {
        private ByteBuffer code;
        private boolean closed;

        private ShaderCodeOwner(ByteBuffer code) {
            this.code = Objects.requireNonNull(code, "code");
        }

        final int byteCount() {
            this.requireOpen();
            return this.code.remaining();
        }

        final VulkanCreation createShaderModule(
            VulkanDevice backend
        ) {
            this.requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.callocLong(1);
                VkShaderModuleCreateInfo shaderInfo =
                    VkShaderModuleCreateInfo
                        .calloc(stack)
                        .sType$Default();
                shaderInfo.pCode(this.code);
                int result =
                    VK12.vkCreateShaderModule(
                        backend.vkDevice(),
                        shaderInfo,
                        null,
                        handle
                    );
                return new VulkanCreation(
                    result,
                    handle.get(0)
                );
            }
        }

        final boolean isClosed() {
            return this.closed;
        }

        final void discardBorrowedView() {
            this.code = null;
            this.closed = true;
        }

        final void freeDirectAllocation() {
            MemoryUtil.memFree(this.code);
            this.code = null;
            this.closed = true;
        }

        @Override
        public abstract void close();

        private void requireOpen() {
            if (this.closed) {
                throw new IllegalStateException(
                    "Motion-SPIR-V-Owner ist geschlossen"
                );
            }
        }
    }

    private static final class PooledShaderCodeOwner
        extends ShaderCodeOwner {
        private ReusableNativeBlockPool pool;
        private long token;

        private PooledShaderCodeOwner(
            ByteBuffer code,
            ReusableNativeBlockPool pool,
            long token
        ) {
            super(code);
            this.pool = Objects.requireNonNull(pool, "pool");
            this.token = token;
        }

        @Override
        public void close() {
            if (this.isClosed()) {
                return;
            }
            this.pool.release(this.token);
            this.discardBorrowedView();
            this.pool = null;
            this.token = 0L;
        }
    }

    private static final class DirectShaderCodeOwner
        extends ShaderCodeOwner {
        private DirectShaderCodeOwner(ByteBuffer code) {
            super(code);
        }

        @Override
        public void close() {
            if (!this.isClosed()) {
                this.freeDirectAllocation();
            }
        }
    }

    private static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) throw new IllegalStateException(operation + " fehlgeschlagen (Vulkan " + result + ")");
    }

    private void recordCreationOutcome(
        ResourceKind kind,
        int result,
        long handle
    ) {
        if (result != VK12.VK_SUCCESS || handle == 0L) {
            this.inventory.creationFailed(kind);
        }
    }

    private static void requireHandle(
        long handle,
        String operation
    ) {
        if (handle == 0L) {
            throw new IllegalStateException(
                operation + " lieferte VK_NULL_HANDLE"
            );
        }
    }

    private record VulkanCreation(int result, long handle) {
    }

    private void labelNativeObjects() {
        try {
            var debug = this.backend.instance().debug();
            var device = this.backend.vkDevice();
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_SHADER_MODULE,
                this.shaderModule,
                "NVIDIA DLSS / Motion Shader Module"
            );
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT,
                this.descriptorSetLayout,
                "NVIDIA DLSS / Motion Descriptor Set Layout"
            );
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_PIPELINE_LAYOUT,
                this.pipelineLayout,
                "NVIDIA DLSS / Motion Pipeline Layout"
            );
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_PIPELINE,
                this.pipeline,
                "NVIDIA DLSS / Motion Compute Pipeline"
            );
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_DESCRIPTOR_POOL,
                this.descriptorPool,
                "NVIDIA DLSS / Motion Descriptor Pool"
            );
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_SAMPLER,
                this.sampler,
                "NVIDIA DLSS / Motion Depth Sampler"
            );
            for (int i = 0; i < FRAME_RING_SIZE; i++) {
                GpuPassDiagnostics.setObjectName(
                    debug,
                    device,
                    VK12.VK_OBJECT_TYPE_DESCRIPTOR_SET,
                    this.descriptorSets[i],
                    "NVIDIA DLSS / Motion Descriptor Set " + i
                );
            }
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // Optional names never roll back a published motion pipeline.
        }
    }

    private static long align(long value, long alignment) {
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    @Override
    public void close() {
        if (!this.prepareDeviceClose()) {
            NvidiaDlssMod.LOGGER.warn(
                "Motion-Ressourcen konnten nicht vollständig für den Device-Shutdown vorbereitet werden"
            );
        }
    }

    /**
     * Queues Mojang-managed buffers while the encoder is still live. Native
     * pipeline handles deliberately remain available until the encoder has
     * submitted and drained all pending work.
     */
    public boolean prepareDeviceClose() {
        if (this.closed || this.closePrepared) {
            return true;
        }
        this.closing = true;
        boolean buffersQueued = this.closeFrameBuffers();
        if (!buffersQueued) {
            return false;
        }
        this.closePrepared = true;
        return true;
    }

    /**
     * Destroys raw handles only after VulkanCommandEncoder.destroy() has
     * submitted its pending builder and waited for the device queue.
     */
    public boolean finishDeviceCloseAfterEncoderDrain() {
        if (this.closed) {
            return true;
        }
        if (!this.closePrepared) {
            NvidiaDlssMod.LOGGER.warn(
                "Motion-Buffer wurden vor dem Encoder-Drain nicht vollständig geschlossen"
            );
            return false;
        }
        if (!this.destroyRawResources()) {
            return false;
        }
        if (
            this.budgetLease != 0L
                && !this.budgets.release(this.budgetLease)
        ) {
            NvidiaDlssMod.LOGGER.warn(
                "Motion-Ressourcenbudget konnte nach dem Encoder-Drain nicht freigegeben werden"
            );
            return false;
        }
        this.budgetLease = 0L;
        this.closed = true;
        return true;
    }

    private boolean rollbackCreatedResources() {
        this.closing = true;
        boolean buffersQueued = this.closeFrameBuffers();
        boolean rawDestroyed = this.destroyRawResources();
        boolean leaseTransitioned = this.budgetLease == 0L;
        if (
            buffersQueued
                && rawDestroyed
                && this.budgetLease != 0L
        ) {
            try {
                if (
                    this.budgets.retireAfterGpuUse(
                        this.budgetLease
                    )
                ) {
                    this.budgetLease = 0L;
                    this.closePrepared = true;
                    leaseTransitioned = true;
                }
            } catch (Throwable error) {
                NvidiaDlssMod.LOGGER.warn(
                    "Motion-Konstruktionsrollback konnte sein Budget nicht in GPU-Retirement ueberfuehren",
                    error
                );
            }
        }
        this.closed =
            buffersQueued && rawDestroyed && leaseTransitioned;
        return this.closed;
    }

    public static synchronized boolean
        retryRetainedFailedConstruction() {
        MotionVectorGenerator retained =
            retainedFailedConstruction;
        if (retained == null) {
            return true;
        }
        if (!retained.backendAlive) {
            return false;
        }
        boolean cleaned;
        try {
            cleaned = retained.rollbackCreatedResources();
        } catch (Throwable error) {
            NvidiaDlssMod.LOGGER.warn(
                "Zurueckbehaltener Motion-Konstruktionsrollback konnte nicht wiederholt werden",
                error
            );
            return false;
        }
        if (
            cleaned
                && retainedFailedConstruction == retained
        ) {
            retainedFailedConstruction = null;
        }
        return cleaned;
    }

    static synchronized boolean hasRetainedFailedConstruction() {
        return retainedFailedConstruction != null;
    }

    static synchronized void deviceClosed(VulkanDevice device) {
        MotionVectorGenerator retained =
            retainedFailedConstruction;
        if (retained != null && retained.backend == device) {
            retained.backendAlive = false;
        }
    }

    void owningDeviceClosed(VulkanDevice device) {
        if (this.backend == device) {
            this.backendAlive = false;
        }
    }

    private static synchronized void retainFailedConstruction(
        MotionVectorGenerator owner
    ) {
        if (
            retainedFailedConstruction == null
                || retainedFailedConstruction == owner
        ) {
            retainedFailedConstruction = owner;
            return;
        }
        throw new IllegalStateException(
            "a motion construction cleanup owner is already retained"
        );
    }

    private boolean closeFrameBuffers() {
        if (!this.backendAlive) {
            for (GpuBuffer buffer : this.frameBuffers) {
                if (buffer != null) {
                    return false;
                }
            }
        }
        boolean fullyClosed = true;
        for (int i = this.frameBuffers.length - 1; i >= 0; i--) {
            GpuBuffer buffer = this.frameBuffers[i];
            if (buffer != null) {
                if (
                    this.frameBufferCloseFailed[i]
                        && buffer.isClosed()
                ) {
                    fullyClosed = false;
                    continue;
                }
                try {
                    buffer.close();
                    this.inventory.queuedForRetirement(
                        ResourceKind.MANAGED_UNIFORM_BUFFER
                    );
                    this.frameBuffers[i] = null;
                    this.frameBufferCloseFailed[i] = false;
                } catch (Throwable error) {
                    fullyClosed = false;
                    this.frameBufferCloseFailed[i] = true;
                    this.inventory.cleanupFailed(
                        ResourceKind.MANAGED_UNIFORM_BUFFER
                    );
                    NvidiaDlssMod.LOGGER.warn(
                        "Motion-Uniformbuffer konnte nicht geschlossen werden",
                        error
                    );
                }
            }
        }
        return fullyClosed;
    }

    private boolean destroyRawResources() {
        if (this.rawResourcesDestroyed) {
            return true;
        }
        if (
            !this.backendAlive
                && (
                    this.descriptorPool != 0L
                        || this.sampler != 0L
                        || this.pipeline != 0L
                        || this.pipelineLayout != 0L
                        || this.descriptorSetLayout != 0L
                        || this.shaderModule != 0L
                )
        ) {
            return false;
        }
        boolean fullyDestroyed = true;
        if (this.descriptorPool != 0L) {
            try {
                VK12.vkDestroyDescriptorPool(
                    this.backend.vkDevice(),
                    this.descriptorPool,
                    null
                );
                this.descriptorPool = 0L;
                this.inventory.destroyed(
                    ResourceKind.DESCRIPTOR_POOL
                );
                int descriptorSetCount = 0;
                for (
                    int index = 0;
                    index < this.descriptorSets.length;
                    index++
                ) {
                    if (this.descriptorSets[index] != 0L) {
                        descriptorSetCount++;
                        this.descriptorSets[index] = 0L;
                    }
                }
                if (descriptorSetCount != 0) {
                    this.inventory.destroyed(
                        ResourceKind.DESCRIPTOR_SET,
                        descriptorSetCount
                    );
                    this.inventory.destroyed(
                        ResourceKind.DESCRIPTOR,
                        descriptorSetCount
                            * (DESCRIPTOR_COUNT / FRAME_RING_SIZE)
                    );
                }
            } catch (Throwable error) {
                fullyDestroyed = false;
                this.inventory.cleanupFailed(
                    ResourceKind.DESCRIPTOR_POOL
                );
                logNativeDestroyFailure("Descriptorpool", error);
            }
        }
        if (this.sampler != 0L) {
            try {
                VK12.vkDestroySampler(
                    this.backend.vkDevice(),
                    this.sampler,
                    null
                );
                this.sampler = 0L;
                this.inventory.destroyed(
                    ResourceKind.RAW_DEPTH_SAMPLER
                );
            } catch (Throwable error) {
                fullyDestroyed = false;
                this.inventory.cleanupFailed(
                    ResourceKind.RAW_DEPTH_SAMPLER
                );
                logNativeDestroyFailure("Sampler", error);
            }
        }
        if (this.pipeline != 0L) {
            try {
                VK12.vkDestroyPipeline(
                    this.backend.vkDevice(),
                    this.pipeline,
                    null
                );
                this.pipeline = 0L;
                this.inventory.destroyed(
                    ResourceKind.COMPUTE_PIPELINE
                );
            } catch (Throwable error) {
                fullyDestroyed = false;
                this.inventory.cleanupFailed(
                    ResourceKind.COMPUTE_PIPELINE
                );
                logNativeDestroyFailure("Pipeline", error);
            }
        }
        if (this.pipelineLayout != 0L) {
            try {
                VK12.vkDestroyPipelineLayout(
                    this.backend.vkDevice(),
                    this.pipelineLayout,
                    null
                );
                this.pipelineLayout = 0L;
                this.inventory.destroyed(
                    ResourceKind.PIPELINE_LAYOUT
                );
            } catch (Throwable error) {
                fullyDestroyed = false;
                this.inventory.cleanupFailed(
                    ResourceKind.PIPELINE_LAYOUT
                );
                logNativeDestroyFailure("Pipelinelayout", error);
            }
        }
        if (this.descriptorSetLayout != 0L) {
            try {
                VK12.vkDestroyDescriptorSetLayout(
                    this.backend.vkDevice(),
                    this.descriptorSetLayout,
                    null
                );
                this.descriptorSetLayout = 0L;
                this.inventory.destroyed(
                    ResourceKind.DESCRIPTOR_SET_LAYOUT
                );
            } catch (Throwable error) {
                fullyDestroyed = false;
                this.inventory.cleanupFailed(
                    ResourceKind.DESCRIPTOR_SET_LAYOUT
                );
                logNativeDestroyFailure("Descriptorlayout", error);
            }
        }
        if (this.shaderModule != 0L) {
            try {
                VK12.vkDestroyShaderModule(
                    this.backend.vkDevice(),
                    this.shaderModule,
                    null
                );
                this.shaderModule = 0L;
                this.inventory.destroyed(
                    ResourceKind.SHADER_MODULE
                );
            } catch (Throwable error) {
                fullyDestroyed = false;
                this.inventory.cleanupFailed(
                    ResourceKind.SHADER_MODULE
                );
                logNativeDestroyFailure("Shadermodul", error);
            }
        }
        if (fullyDestroyed) {
            this.rawResourcesDestroyed = true;
        }
        return fullyDestroyed;
    }

    private static void logNativeDestroyFailure(
        String object,
        Throwable error
    ) {
        NvidiaDlssMod.LOGGER.warn(
            "Motion-{} konnte nicht sauber geschlossen werden",
            object,
            error
        );
    }

    public record MotionObject(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        double previousX, double previousY, double previousZ,
        double currentX, double currentY, double currentZ,
        float currentYaw, float previousYaw
    ) {}
}
