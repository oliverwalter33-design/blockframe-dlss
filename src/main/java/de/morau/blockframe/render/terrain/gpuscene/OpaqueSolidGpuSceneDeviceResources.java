package de.morau.blockframe.render.terrain.gpuscene;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind;
import de.morau.nvidiadlss.NvidiaDlssMod;
import de.morau.nvidiadlss.mixin.VulkanCommandEncoderAccessor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.joml.Matrix4fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Two-submit-ring Vulkan resources for one bounded opaque-solid GPU scene.
 *
 * <p>Host-visible scene, visibility-input, count and frame buffers are
 * persistently mapped. Device-local indirect commands are produced only by
 * the compute pipeline. There is no count readback and no geometry copy.</p>
 */
public final class OpaqueSolidGpuSceneDeviceResources
    implements
        AutoCloseable,
        OpaqueSolidGpuSceneModel.DirtyWriter,
        OpaqueSolidGpuSceneModel.VisibilityWriter {
    public static final int STORAGE_BUFFER_USAGE = 1 << 10;
    public static final int FRAME_COUNT = 2;
    public static final int CAPACITY =
        OpaqueSolidGpuSceneModel.DEFAULT_CAPACITY;
    public static final int BUCKET_CAPACITY =
        OpaqueSolidGpuSceneModel.DEFAULT_BUCKET_CAPACITY;
    public static final int SCENE_WORDS = 8;
    public static final int SCENE_BYTES =
        CAPACITY * SCENE_WORDS * Integer.BYTES;
    public static final int VISIBLE_BYTES =
        CAPACITY * 2 * Integer.BYTES;
    public static final int COMMAND_STRIDE_BYTES = 5 * Integer.BYTES;
    public static final int COMMAND_BYTES =
        BUCKET_CAPACITY * CAPACITY * COMMAND_STRIDE_BYTES;
    public static final int COUNT_BYTES =
        BUCKET_CAPACITY * Integer.BYTES;
    public static final int VISIBILITY_BYTES =
        CAPACITY * Integer.BYTES;
    public static final int FRAME_UNIFORM_BYTES = 80;
    public static final long HOST_VISIBLE_BYTES_PER_FRAME =
        (long)SCENE_BYTES
            + VISIBLE_BYTES
            + COUNT_BYTES
            + FRAME_UNIFORM_BYTES;
    public static final long VRAM_BYTES_PER_FRAME =
        HOST_VISIBLE_BYTES_PER_FRAME
            + COMMAND_BYTES
            + VISIBILITY_BYTES;
    public static final long RAM_BYTES =
        HOST_VISIBLE_BYTES_PER_FRAME * FRAME_COUNT;
    public static final long VRAM_BYTES =
        VRAM_BYTES_PER_FRAME * FRAME_COUNT;

    private static final String COMPUTE_SHADER =
        "/assets/voxellift/shaders/core/"
            + "opaque_solid_gpu_scene_compact_v1.comp";

    private final VulkanDevice device;
    private final MemoryBudgetManager budgets;
    private final ShaderResourceInventory inventory;
    private final FrameResources[] frames = new FrameResources[FRAME_COUNT];
    private long ramLease;
    private long vramLease;
    private long shaderModule;
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private boolean descriptorSetsInventoried;
    private int writeFrameIndex;
    private int preparedFrameIndex = -1;
    private int preparedBucketCount;
    private int preparedVisibleCount;
    private long dispatchedFrames;
    private long uploadedBytes;
    private boolean submissionStarted;
    private boolean closePrepared;
    private boolean closed;

    public OpaqueSolidGpuSceneDeviceResources(VulkanDevice device) {
        this.device = Objects.requireNonNull(device, "device");
        this.budgets = BlockframeRuntime.memoryBudgets();
        this.inventory = BlockframeRuntime.shaderResources();
        if (
            device.vkDevice().getCapabilities()
                    .vkCmdDrawIndexedIndirectCount == 0L
        ) {
            throw new IllegalStateException(
                "vkCmdDrawIndexedIndirectCount-function-unresolved"
            );
        }
        try {
            this.reserveBudgets();
            this.createComputeObjects();
            for (int index = 0; index < FRAME_COUNT; index++) {
                this.frames[index] = new FrameResources(index);
            }
            this.allocateAndWriteDescriptorSets();
        } catch (Throwable error) {
            try {
                this.rollbackConstruction();
            } catch (Throwable cleanup) {
                error.addSuppressed(cleanup);
            }
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException(
                "opaque-solid GPU-scene resource creation failed",
                error
            );
        }
    }

    /**
     * Writes only dirty owner events and Mojang's final visible identities,
     * then records the compute compaction pass into the current submit.
     */
    public boolean prepare(
        OpaqueSolidGpuSceneModel model,
        Matrix4fc modelView,
        int textureWidth,
        int textureHeight
    ) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(modelView, "modelView");
        if (
            this.closed
                || this.closePrepared
                || textureWidth <= 0
                || textureHeight <= 0
        ) {
            return false;
        }
        OpaqueSolidGpuSceneModel.Snapshot snapshot = model.snapshot();
        if (
            snapshot.visible() <= 0
                || snapshot.buckets() <= 0
                || snapshot.buckets() > BUCKET_CAPACITY
        ) {
            return false;
        }
        int frame = currentFrameIndex(this.device);
        FrameResources resources = this.frames[frame];
        this.writeFrameIndex = frame;
        long stagingStarted =
            OpaqueSolidGpuSceneRuntime.auditActive()
                ? System.nanoTime()
                : 0L;
        if (!model.drainDirty(frame, this)) {
            return false;
        }
        if (!model.writeVisibility(this)) {
            return false;
        }
        writeFrameUniform(
            resources.frameBytes,
            modelView,
            textureWidth,
            textureHeight
        );
        zeroCounts(resources.countWords);
        if (stagingStarted != 0L) {
            OpaqueSolidGpuSceneRuntime.recordAuditStage(
                OpaqueSolidGpuSceneAuditWindow
                    .STAGING_AND_VISIBILITY_UPLOAD,
                System.nanoTime() - stagingStarted
            );
        }

        long computeStarted =
            OpaqueSolidGpuSceneRuntime.auditActive()
                ? System.nanoTime()
                : 0L;
        VkCommandBuffer commandBuffer =
            ((VulkanCommandEncoderAccessor)(Object)
                    this.device.createCommandEncoder())
                .nvidiaDlss$commandBuffer();
        memoryBarrier(
            commandBuffer,
            16384L,
            16384L,
            2048L,
            96L
        );
        VK12.vkCmdBindPipeline(
            commandBuffer,
            VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
            this.pipeline
        );
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindDescriptorSets(
                commandBuffer,
                VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                this.pipelineLayout,
                0,
                stack.longs(resources.descriptorSet),
                null
            );
            IntBuffer visibleCount = stack.ints(snapshot.visible());
            VK12.vkCmdPushConstants(
                commandBuffer,
                this.pipelineLayout,
                VK12.VK_SHADER_STAGE_COMPUTE_BIT,
                0,
                visibleCount
            );
        }
        VK12.vkCmdDispatch(
            commandBuffer,
            (snapshot.visible() + 63) / 64,
            1,
            1
        );
        memoryBarrier(
            commandBuffer,
            2048L,
            64L,
            10L,
            33L
        );
        if (computeStarted != 0L) {
            OpaqueSolidGpuSceneRuntime.recordAuditStage(
                OpaqueSolidGpuSceneAuditWindow
                    .COMPUTE_AND_BARRIERS,
                System.nanoTime() - computeStarted
            );
            OpaqueSolidGpuSceneRuntime.recordAuditBarriers(2L);
        }
        this.preparedFrameIndex = frame;
        this.preparedBucketCount = snapshot.buckets();
        this.preparedVisibleCount = snapshot.visible();
        this.submissionStarted = false;
        this.dispatchedFrames++;
        return true;
    }

    @Override
    public boolean write(
        int slot,
        int bucket,
        OpaqueSolidGpuGenerationToken token
    ) {
        if (slot < 0 || slot >= CAPACITY) {
            return false;
        }
        IntBuffer scene = this.frames[this.writeFrameIndex].sceneWords;
        int base = slot * SCENE_WORDS;
        if (token == null) {
            for (int word = 0; word < SCENE_WORDS; word++) {
                scene.put(base + word, 0);
            }
            this.uploadedBytes += SCENE_WORDS * Integer.BYTES;
            return true;
        }
        if (
            bucket < 0
                || bucket >= BUCKET_CAPACITY
                || token.indexCount() <= 0
        ) {
            return false;
        }
        int firstIndex =
            token.indexBindingKey()
                    == OpaqueSolidGpuGenerationToken
                        .INDEX_BINDING_CUSTOM
                ? Math.toIntExact(
                    token.indexOffset()
                        / (token.indexTypeKey() == 1 ? 2L : 4L)
                )
                : 0;
        int sectionX = net.minecraft.core.SectionPos.sectionToBlockCoord(
            net.minecraft.core.SectionPos.x(token.sectionNode())
        );
        int sectionY = net.minecraft.core.SectionPos.sectionToBlockCoord(
            net.minecraft.core.SectionPos.y(token.sectionNode())
        );
        int sectionZ = net.minecraft.core.SectionPos.sectionToBlockCoord(
            net.minecraft.core.SectionPos.z(token.sectionNode())
        );
        scene.put(base, 1);
        scene.put(base + 1, bucket);
        scene.put(base + 2, token.indexCount());
        scene.put(base + 3, firstIndex);
        scene.put(base + 4, token.baseVertex());
        scene.put(base + 5, sectionX);
        scene.put(base + 6, sectionY);
        scene.put(base + 7, sectionZ);
        this.uploadedBytes += SCENE_WORDS * Integer.BYTES;
        return true;
    }

    @Override
    public boolean write(
        int ordinal,
        int slot,
        int visibilityBits
    ) {
        if (
            ordinal < 0
                || ordinal >= CAPACITY
                || slot < 0
                || slot >= CAPACITY
        ) {
            return false;
        }
        IntBuffer visible =
            this.frames[this.writeFrameIndex].visibleWords;
        visible.put(ordinal * 2, slot);
        visible.put(ordinal * 2 + 1, visibilityBits);
        this.uploadedBytes += 2L * Integer.BYTES;
        return true;
    }

    public FrameResources preparedFrame() {
        return this.preparedFrameIndex < 0
            ? null
            : this.frames[this.preparedFrameIndex];
    }

    public int preparedBucketCount() {
        return this.preparedBucketCount;
    }

    public int preparedVisibleCount() {
        return this.preparedVisibleCount;
    }

    public boolean beginSubmission() {
        if (
            this.preparedFrameIndex < 0
                || this.submissionStarted
                || this.closed
        ) {
            return false;
        }
        this.submissionStarted = true;
        return true;
    }

    public void finishSubmission(boolean completed) {
        this.preparedFrameIndex = -1;
        this.preparedBucketCount = 0;
        this.preparedVisibleCount = 0;
        this.submissionStarted = false;
        if (!completed) {
            // The no-replay boundary was already crossed; next frame rebuilds
            // visibility and count state from the same persistent scene.
        }
    }

    public void cancelBeforeSubmission() {
        if (this.submissionStarted) {
            throw new IllegalStateException(
                "cannot cancel after indirect submission began"
            );
        }
        this.preparedFrameIndex = -1;
        this.preparedBucketCount = 0;
        this.preparedVisibleCount = 0;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.preparedVisibleCount,
            this.preparedBucketCount,
            this.dispatchedFrames,
            this.uploadedBytes,
            RAM_BYTES,
            VRAM_BYTES,
            this.submissionStarted,
            this.closePrepared,
            this.closed
        );
    }

    /**
     * Queues managed buffers while the encoder is alive. Raw Vulkan objects
     * remain owned until {@link #finishCloseAfterEncoderDrain()}.
     */
    @Override
    public void close() {
        if (this.closed || this.closePrepared) {
            return;
        }
        Throwable viewFailure = this.queueAllBufferViews();
        if (viewFailure != null) {
            Throwable retryFailure = this.queueAllBufferViews();
            if (retryFailure != null) {
                retryFailure.addSuppressed(viewFailure);
                this.inventory.cleanupFailed(
                    ResourceKind.RAW_BUFFER_VIEW
                );
                throwUnchecked(retryFailure);
            }
        }
        Throwable bufferFailure = this.closeAllMappedAndBuffers();
        if (bufferFailure != null) {
            Throwable retryFailure = this.closeAllMappedAndBuffers();
            if (retryFailure != null) {
                retryFailure.addSuppressed(bufferFailure);
                this.inventory.cleanupFailed(
                    ResourceKind.MANAGED_GPU_SCENE_BUFFER
                );
                throwUnchecked(retryFailure);
            }
        }
        this.preparedFrameIndex = -1;
        this.submissionStarted = false;
        this.closePrepared = true;
    }

    private Throwable queueAllBufferViews() {
        Throwable firstFailure = null;
        for (FrameResources frame : this.frames) {
            if (frame != null) {
                try {
                    frame.queueBufferViewsForDestroy();
                } catch (
                    RuntimeException
                        | LinkageError
                        | OutOfMemoryError error
                ) {
                    if (firstFailure == null) {
                        firstFailure = error;
                    } else {
                        firstFailure.addSuppressed(error);
                    }
                }
            }
        }
        return firstFailure;
    }

    private Throwable closeAllMappedAndBuffers() {
        Throwable firstFailure = null;
        for (int index = FRAME_COUNT - 1; index >= 0; index--) {
            FrameResources frame = this.frames[index];
            if (frame != null) {
                try {
                    frame.closeMappedAndBuffers();
                } catch (
                    RuntimeException
                        | LinkageError
                        | OutOfMemoryError error
                ) {
                    if (firstFailure == null) {
                        firstFailure = error;
                    } else {
                        firstFailure.addSuppressed(error);
                    }
                }
            }
        }
        return firstFailure;
    }

    public void finishCloseAfterEncoderDrain() {
        if (this.closed) {
            return;
        }
        if (!this.closePrepared) {
            throw new IllegalStateException(
                "GPU-scene close was not prepared before encoder drain"
            );
        }
        this.destroyRawObjects();
        releaseLease(this.vramLease);
        releaseLease(this.ramLease);
        this.vramLease = 0L;
        this.ramLease = 0L;
        this.closed = true;
    }

    private void reserveBudgets() {
        this.ramLease = this.budgets.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.TERRAIN,
            RAM_BYTES,
            RAM_BYTES,
            null
        );
        if (this.ramLease == 0L) {
            throw new IllegalStateException("RAM-budget-rejected");
        }
        this.vramLease = this.budgets.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.TERRAIN,
            VRAM_BYTES,
            VRAM_BYTES,
            null
        );
        if (this.vramLease == 0L) {
            throw new IllegalStateException("VRAM-budget-rejected");
        }
    }

    private void createComputeObjects() throws IOException {
        ByteBuffer spirv = compileComputeShader();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.callocLong(1);
            VkShaderModuleCreateInfo moduleInfo =
                VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);
            check(
                VK12.vkCreateShaderModule(
                    this.device.vkDevice(),
                    moduleInfo,
                    null,
                    handle
                ),
                "compute shader module"
            );
            this.shaderModule = requireHandle(
                handle.get(0),
                "compute shader module"
            );
            this.inventory.created(ResourceKind.SHADER_MODULE);

            VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(5, stack);
            for (int index = 0; index < 5; index++) {
                bindings.get(index)
                    .binding(index)
                    .descriptorType(
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    )
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo setInfo =
                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);
            handle.put(0, 0L);
            check(
                VK12.vkCreateDescriptorSetLayout(
                    this.device.vkDevice(),
                    setInfo,
                    null,
                    handle
                ),
                "compute descriptor set layout"
            );
            this.descriptorSetLayout = requireHandle(
                handle.get(0),
                "compute descriptor set layout"
            );
            this.inventory.created(
                ResourceKind.DESCRIPTOR_SET_LAYOUT
            );

            VkPushConstantRange.Buffer pushRange =
                VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(Integer.BYTES);
            VkPipelineLayoutCreateInfo layoutInfo =
                VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(this.descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            handle.put(0, 0L);
            check(
                VK12.vkCreatePipelineLayout(
                    this.device.vkDevice(),
                    layoutInfo,
                    null,
                    handle
                ),
                "compute pipeline layout"
            );
            this.pipelineLayout = requireHandle(
                handle.get(0),
                "compute pipeline layout"
            );
            this.inventory.created(ResourceKind.PIPELINE_LAYOUT);

            VkPipelineShaderStageCreateInfo stage =
                VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(this.shaderModule)
                    .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo =
                VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0)
                .sType$Default()
                .stage(stage)
                .layout(this.pipelineLayout);
            handle.put(0, 0L);
            check(
                VK12.vkCreateComputePipelines(
                    this.device.vkDevice(),
                    0L,
                    pipelineInfo,
                    null,
                    handle
                ),
                "compute pipeline"
            );
            this.pipeline = requireHandle(
                handle.get(0),
                "compute pipeline"
            );
            this.inventory.created(ResourceKind.COMPUTE_PIPELINE);

            VkDescriptorPoolSize.Buffer poolSize =
                VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(FRAME_COUNT * 5);
            VkDescriptorPoolCreateInfo poolInfo =
                VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(FRAME_COUNT)
                    .pPoolSizes(poolSize);
            handle.put(0, 0L);
            check(
                VK12.vkCreateDescriptorPool(
                    this.device.vkDevice(),
                    poolInfo,
                    null,
                    handle
                ),
                "compute descriptor pool"
            );
            this.descriptorPool = requireHandle(
                handle.get(0),
                "compute descriptor pool"
            );
            this.inventory.created(ResourceKind.DESCRIPTOR_POOL);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private void allocateAndWriteDescriptorSets() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer layouts = stack.mallocLong(FRAME_COUNT);
            for (int index = 0; index < FRAME_COUNT; index++) {
                layouts.put(index, this.descriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo allocateInfo =
                VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(layouts);
            LongBuffer sets = stack.callocLong(FRAME_COUNT);
            check(
                VK12.vkAllocateDescriptorSets(
                    this.device.vkDevice(),
                    allocateInfo,
                    sets
                ),
                "compute descriptor sets"
            );
            this.inventory.created(
                ResourceKind.DESCRIPTOR_SET,
                FRAME_COUNT
            );
            this.inventory.created(
                ResourceKind.DESCRIPTOR,
                FRAME_COUNT * 5
            );
            this.descriptorSetsInventoried = true;

            for (int frame = 0; frame < FRAME_COUNT; frame++) {
                FrameResources resources = this.frames[frame];
                resources.descriptorSet = requireHandle(
                    sets.get(frame),
                    "compute descriptor set"
                );
                VkDescriptorBufferInfo.Buffer infos =
                    VkDescriptorBufferInfo.calloc(5, stack);
                descriptorInfo(infos.get(0), resources.sceneBuffer);
                descriptorInfo(infos.get(1), resources.visibleBuffer);
                descriptorInfo(infos.get(2), resources.commandBuffer);
                descriptorInfo(infos.get(3), resources.countBuffer);
                descriptorInfo(
                    infos.get(4),
                    resources.visibilityBuffer
                );
                VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(5, stack);
                for (int binding = 0; binding < 5; binding++) {
                    writes.get(binding)
                        .sType$Default()
                        .dstSet(resources.descriptorSet)
                        .dstBinding(binding)
                        .descriptorCount(1)
                        .descriptorType(
                            VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                        )
                        .pBufferInfo(
                            infos.position(binding).limit(binding + 1)
                        );
                    infos.limit(5);
                }
                VK12.vkUpdateDescriptorSets(
                    this.device.vkDevice(),
                    writes,
                    null
                );
            }
        }
    }

    private static void descriptorInfo(
        VkDescriptorBufferInfo info,
        GpuBuffer buffer
    ) {
        info.buffer(((VulkanGpuBuffer)buffer).vkBuffer())
            .offset(0L)
            .range(buffer.size());
    }

    private ByteBuffer compileComputeShader() throws IOException {
        byte[] source;
        try (
            InputStream input =
                OpaqueSolidGpuSceneDeviceResources.class
                    .getResourceAsStream(COMPUTE_SHADER)
        ) {
            if (input == null) {
                throw new IOException(
                    "missing compute shader " + COMPUTE_SHADER
                );
            }
            source = input.readAllBytes();
        }
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == MemoryUtil.NULL) {
            throw new IllegalStateException(
                "shaderc compiler initialization failed"
            );
        }
        long result = MemoryUtil.NULL;
        try {
            String glsl = new String(source, StandardCharsets.UTF_8);
            result = Shaderc.shaderc_compile_into_spv(
                compiler,
                glsl,
                Shaderc.shaderc_compute_shader,
                "opaque_solid_gpu_scene_compact_v1.comp",
                "main",
                MemoryUtil.NULL
            );
            if (result == MemoryUtil.NULL) {
                throw new IllegalStateException(
                    "shaderc returned null result"
                );
            }
            int status =
                Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalStateException(
                    "compute shader compilation failed: "
                        + Shaderc.shaderc_result_get_error_message(result)
                );
            }
            ByteBuffer bytes =
                Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer owned = MemoryUtil.memAlloc(bytes.remaining())
                .order(ByteOrder.nativeOrder());
            owned.put(bytes).flip();
            return owned;
        } finally {
            if (result != MemoryUtil.NULL) {
                Shaderc.shaderc_result_release(result);
            }
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static int currentFrameIndex(VulkanDevice device) {
        long submit =
            ((VulkanCommandEncoderAccessor)(Object)
                    device.createCommandEncoder())
                .blockframe$currentSubmitIndex();
        return (int)(submit & 1L);
    }

    private static void writeFrameUniform(
        ByteBuffer bytes,
        Matrix4fc matrix,
        int textureWidth,
        int textureHeight
    ) {
        bytes.clear();
        bytes.putFloat(matrix.m00()).putFloat(matrix.m01())
            .putFloat(matrix.m02()).putFloat(matrix.m03());
        bytes.putFloat(matrix.m10()).putFloat(matrix.m11())
            .putFloat(matrix.m12()).putFloat(matrix.m13());
        bytes.putFloat(matrix.m20()).putFloat(matrix.m21())
            .putFloat(matrix.m22()).putFloat(matrix.m23());
        bytes.putFloat(matrix.m30()).putFloat(matrix.m31())
            .putFloat(matrix.m32()).putFloat(matrix.m33());
        bytes.putInt(textureWidth).putInt(textureHeight);
        while (bytes.position() < FRAME_UNIFORM_BYTES) {
            bytes.put((byte)0);
        }
    }

    private static void zeroCounts(IntBuffer counts) {
        for (int index = 0; index < BUCKET_CAPACITY; index++) {
            counts.put(index, 0);
        }
    }

    private static void memoryBarrier(
        VkCommandBuffer commandBuffer,
        long sourceStage,
        long sourceAccess,
        long destinationStage,
        long destinationAccess
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier =
                VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                .sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess);
            VkDependencyInfo dependency =
                VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                commandBuffer,
                dependency
            );
        }
    }

    private void rollbackConstruction() {
        for (int index = FRAME_COUNT - 1; index >= 0; index--) {
            FrameResources frame = this.frames[index];
            if (frame != null) {
                frame.closeMappedAndBuffers();
            }
        }
        this.destroyRawObjects();
        retireOrRelease(this.vramLease);
        retireOrRelease(this.ramLease);
        this.vramLease = 0L;
        this.ramLease = 0L;
        this.closed = true;
    }

    private void retireOrRelease(long lease) {
        if (lease == 0L) {
            return;
        }
        if (!this.budgets.retireAfterGpuUse(lease)) {
            this.budgets.release(lease);
        }
    }

    private void releaseLease(long lease) {
        if (lease != 0L && !this.budgets.release(lease)) {
            throw new IllegalStateException(
                "GPU-scene budget release failed"
            );
        }
    }

    private void destroyRawObjects() {
        if (this.descriptorPool != 0L) {
            VK12.vkDestroyDescriptorPool(
                this.device.vkDevice(),
                this.descriptorPool,
                null
            );
            this.descriptorPool = 0L;
            if (this.descriptorSetsInventoried) {
                this.inventory.destroyed(
                    ResourceKind.DESCRIPTOR,
                    FRAME_COUNT * 5
                );
                this.inventory.destroyed(
                    ResourceKind.DESCRIPTOR_SET,
                    FRAME_COUNT
                );
                this.descriptorSetsInventoried = false;
            }
            this.inventory.destroyed(ResourceKind.DESCRIPTOR_POOL);
        }
        if (this.pipeline != 0L) {
            VK12.vkDestroyPipeline(
                this.device.vkDevice(),
                this.pipeline,
                null
            );
            this.pipeline = 0L;
            this.inventory.destroyed(ResourceKind.COMPUTE_PIPELINE);
        }
        if (this.pipelineLayout != 0L) {
            VK12.vkDestroyPipelineLayout(
                this.device.vkDevice(),
                this.pipelineLayout,
                null
            );
            this.pipelineLayout = 0L;
            this.inventory.destroyed(ResourceKind.PIPELINE_LAYOUT);
        }
        if (this.descriptorSetLayout != 0L) {
            VK12.vkDestroyDescriptorSetLayout(
                this.device.vkDevice(),
                this.descriptorSetLayout,
                null
            );
            this.descriptorSetLayout = 0L;
            this.inventory.destroyed(
                ResourceKind.DESCRIPTOR_SET_LAYOUT
            );
        }
        if (this.shaderModule != 0L) {
            VK12.vkDestroyShaderModule(
                this.device.vkDevice(),
                this.shaderModule,
                null
            );
            this.shaderModule = 0L;
            this.inventory.destroyed(ResourceKind.SHADER_MODULE);
        }
        for (FrameResources frame : this.frames) {
            if (frame != null) {
                frame.destroyBufferViews();
            }
        }
    }

    private static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(
                operation + " failed with Vulkan result " + result
            );
        }
    }

    private static long requireHandle(long handle, String operation) {
        if (handle == 0L) {
            throw new IllegalStateException(
                operation + " returned VK_NULL_HANDLE"
            );
        }
        return handle;
    }

    private static void throwUnchecked(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (error instanceof Error fatal) {
            throw fatal;
        }
        throw new IllegalStateException(error);
    }

    public final class FrameResources {
        private GpuBuffer sceneBuffer;
        private GpuBuffer visibleBuffer;
        private GpuBuffer commandBuffer;
        private GpuBuffer countBuffer;
        private GpuBuffer visibilityBuffer;
        private GpuBuffer frameBuffer;
        private GpuBufferSlice.MappedView sceneMapping;
        private GpuBufferSlice.MappedView visibleMapping;
        private GpuBufferSlice.MappedView countMapping;
        private GpuBufferSlice.MappedView frameMapping;
        private IntBuffer sceneWords;
        private IntBuffer visibleWords;
        private IntBuffer countWords;
        private ByteBuffer frameBytes;
        private long sceneBufferView;
        private long visibilityBufferView;
        private long descriptorSet;
        private boolean viewsInventoried;
        private boolean buffersInventoried;
        private int queuedViewCount;
        private int queuedBufferCount;

        private FrameResources(int index) {
            try {
                int hostStorage =
                    GpuBuffer.USAGE_MAP_WRITE | STORAGE_BUFFER_USAGE;
                this.sceneBuffer = device.createBuffer(
                    () -> "BlockFrame opaque-solid scene " + index,
                    hostStorage
                        | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
                    SCENE_BYTES
                );
                this.visibleBuffer = device.createBuffer(
                    () -> "BlockFrame opaque-solid visible " + index,
                    hostStorage,
                    VISIBLE_BYTES
                );
                this.commandBuffer = device.createBuffer(
                    () -> "BlockFrame opaque-solid indirect commands " + index,
                    STORAGE_BUFFER_USAGE
                        | GpuBuffer.USAGE_INDIRECT_PARAMETERS,
                    COMMAND_BYTES
                );
                this.countBuffer = device.createBuffer(
                    () -> "BlockFrame opaque-solid indirect counts " + index,
                    hostStorage | GpuBuffer.USAGE_INDIRECT_PARAMETERS,
                    COUNT_BYTES
                );
                this.visibilityBuffer = device.createBuffer(
                    () -> "BlockFrame opaque-solid draw visibility " + index,
                    STORAGE_BUFFER_USAGE
                        | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
                    VISIBILITY_BYTES
                );
                this.frameBuffer = device.createBuffer(
                    () -> "BlockFrame opaque-solid frame UBO " + index,
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    FRAME_UNIFORM_BYTES
                );
                this.sceneMapping = this.sceneBuffer.map(false, true);
                this.visibleMapping = this.visibleBuffer.map(false, true);
                this.countMapping = this.countBuffer.map(false, true);
                this.frameMapping = this.frameBuffer.map(false, true);
                this.sceneWords = this.sceneMapping.data()
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
                this.visibleWords = this.visibleMapping.data()
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
                this.countWords = this.countMapping.data()
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
                this.frameBytes = this.frameMapping.data()
                    .order(ByteOrder.nativeOrder());
                this.sceneBufferView = createBufferView(
                    this.sceneBuffer,
                    VK12.VK_FORMAT_R32G32B32A32_UINT
                );
                this.visibilityBufferView = createBufferView(
                    this.visibilityBuffer,
                    VK12.VK_FORMAT_R32_UINT
                );
                inventory.created(
                    ResourceKind.MANAGED_GPU_SCENE_BUFFER,
                    6
                );
                this.buffersInventoried = true;
                inventory.created(ResourceKind.RAW_BUFFER_VIEW, 2);
                this.viewsInventoried = true;
            } catch (Throwable error) {
                this.destroyBufferViews();
                this.closeMappedAndBuffers();
                throw error;
            }
        }

        public GpuBuffer sceneBuffer() {
            return this.sceneBuffer;
        }

        public GpuBuffer commandBuffer() {
            return this.commandBuffer;
        }

        public GpuBuffer countBuffer() {
            return this.countBuffer;
        }

        public GpuBuffer visibilityBuffer() {
            return this.visibilityBuffer;
        }

        public GpuBuffer frameBuffer() {
            return this.frameBuffer;
        }

        public long sceneBufferView() {
            return this.sceneBufferView;
        }

        public long visibilityBufferView() {
            return this.visibilityBufferView;
        }

        private long createBufferView(GpuBuffer buffer, int format) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferViewCreateInfo info =
                    VkBufferViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .buffer(((VulkanGpuBuffer)buffer).vkBuffer())
                        .offset(0L)
                        .range(buffer.size())
                        .format(format);
                LongBuffer result = stack.callocLong(1);
                check(
                    VK12.vkCreateBufferView(
                        device.vkDevice(),
                        info,
                        null,
                        result
                    ),
                    "GPU-scene buffer view"
                );
                return requireHandle(
                    result.get(0),
                    "GPU-scene buffer view"
                );
            }
        }

        private void closeMappedAndBuffers() {
            this.frameMapping = closeMapping(this.frameMapping);
            this.countMapping = closeMapping(this.countMapping);
            this.visibleMapping = closeMapping(this.visibleMapping);
            this.sceneMapping = closeMapping(this.sceneMapping);
            if (this.frameBuffer != null) {
                closeBuffer(this.frameBuffer);
                this.frameBuffer = null;
                this.queuedBufferCount++;
            }
            if (this.visibilityBuffer != null) {
                closeBuffer(this.visibilityBuffer);
                this.visibilityBuffer = null;
                this.queuedBufferCount++;
            }
            if (this.countBuffer != null) {
                closeBuffer(this.countBuffer);
                this.countBuffer = null;
                this.queuedBufferCount++;
            }
            if (this.commandBuffer != null) {
                closeBuffer(this.commandBuffer);
                this.commandBuffer = null;
                this.queuedBufferCount++;
            }
            if (this.visibleBuffer != null) {
                closeBuffer(this.visibleBuffer);
                this.visibleBuffer = null;
                this.queuedBufferCount++;
            }
            if (this.sceneBuffer != null) {
                closeBuffer(this.sceneBuffer);
                this.sceneBuffer = null;
                this.queuedBufferCount++;
            }
            if (
                this.buffersInventoried
                    && this.queuedBufferCount == 6
            ) {
                inventory.queuedForRetirement(
                    ResourceKind.MANAGED_GPU_SCENE_BUFFER,
                    this.queuedBufferCount
                );
                this.buffersInventoried = false;
            }
        }

        private void destroyBufferViews() {
            int destroyed = 0;
            if (this.visibilityBufferView != 0L) {
                VK12.vkDestroyBufferView(
                    device.vkDevice(),
                    this.visibilityBufferView,
                    null
                );
                this.visibilityBufferView = 0L;
                destroyed++;
            }
            if (this.sceneBufferView != 0L) {
                VK12.vkDestroyBufferView(
                    device.vkDevice(),
                    this.sceneBufferView,
                    null
                );
                this.sceneBufferView = 0L;
                destroyed++;
            }
            if (this.viewsInventoried && destroyed != 0) {
                inventory.destroyed(
                    ResourceKind.RAW_BUFFER_VIEW,
                    destroyed
                );
                this.viewsInventoried = false;
            }
        }

        private void queueBufferViewsForDestroy() {
            long visibilityView = this.visibilityBufferView;
            if (visibilityView != 0L) {
                device.createCommandEncoder().queueForDestroy(
                    () -> VK12.vkDestroyBufferView(
                        device.vkDevice(),
                        visibilityView,
                        null
                    )
                );
                this.visibilityBufferView = 0L;
                this.queuedViewCount++;
            }
            long sceneView = this.sceneBufferView;
            if (sceneView != 0L) {
                device.createCommandEncoder().queueForDestroy(
                    () -> VK12.vkDestroyBufferView(
                        device.vkDevice(),
                        sceneView,
                        null
                    )
                );
                this.sceneBufferView = 0L;
                this.queuedViewCount++;
            }
            if (
                this.viewsInventoried
                    && this.queuedViewCount == 2
            ) {
                inventory.queuedForRetirement(
                    ResourceKind.RAW_BUFFER_VIEW,
                    this.queuedViewCount
                );
                this.viewsInventoried = false;
            }
        }

        private GpuBufferSlice.MappedView closeMapping(
            GpuBufferSlice.MappedView mapping
        ) {
            if (mapping != null) {
                mapping.close();
            }
            return null;
        }

        private void closeBuffer(GpuBuffer buffer) {
            if (buffer != null && !buffer.isClosed()) {
                buffer.close();
            }
        }
    }

    public record Snapshot(
        int visible,
        int buckets,
        long dispatchedFrames,
        long uploadedBytes,
        long ramBytes,
        long vramBytes,
        boolean submissionStarted,
        boolean closePrepared,
        boolean closed
    ) {
    }
}
