package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory
    .ResourceKind;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.BufferKind;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.Completion;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.ResourcePublication;
import de.morau.nvidiadlss.mixin.VulkanCommandEncoderAccessor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
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
 * Vulkan compute objects composed by {@link NativeTerrainGpuScene}.
 *
 * <p>All buffers remain allocations of the existing geometry/upload owner.
 * This class owns only its descriptor/pipeline objects and one texel view.
 * It records into Mojang's current encoder and never creates a queue,
 * command pool, command buffer or submission.</p>
 */
public final class NativeTerrainGpuSceneVulkanResources
    implements AutoCloseable {
    private static final String COMPUTE_SHADER =
        "/assets/voxellift/shaders/core/"
            + "native_terrain_frustum_indirect_v1.comp";

    public record Metrics(
        long dispatches,
        long zeroCountFills,
        long barriers,
        long lastRecordNanos,
        boolean prepared,
        boolean closing,
        boolean closed
    ) {
    }

    private final VulkanDevice device;
    private final NativeTerrainGeometryOwner geometryOwner;
    private final NativeTerrainGpuScene scene;
    private final ShaderResourceInventory inventory;
    private final GpuBufferSlice sceneSlice;
    private final GpuBufferSlice commandSlice;
    private final GpuBufferSlice countSlice;
    private long shaderModule;
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private long descriptorSet;
    private long sceneBufferView;
    private boolean descriptorsInventoried;
    private boolean prepared;
    private boolean submissionStarted;
    private Completion closeCompletion;
    private long dispatches;
    private long zeroCountFills;
    private long barriers;
    private long lastRecordNanos;
    private boolean closing;
    private boolean closed;

    public NativeTerrainGpuSceneVulkanResources(
        VulkanDevice device,
        NativeTerrainGeometryOwner geometryOwner,
        NativeTerrainGpuScene scene
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.geometryOwner = Objects.requireNonNull(
            geometryOwner,
            "geometryOwner"
        );
        this.scene = Objects.requireNonNull(scene, "scene");
        this.inventory = BlockframeRuntime.shaderResources();
        ResourcePublication resources = scene.resources();
        this.sceneSlice = geometryOwner.requireVulkanSlice(
            resources.require(BufferKind.STORAGE_SCENE)
        );
        this.commandSlice = geometryOwner.requireVulkanSlice(
            resources.require(BufferKind.INDIRECT_COMMAND)
        );
        this.countSlice = geometryOwner.requireVulkanSlice(
            resources.require(BufferKind.INDIRECT_COUNT)
        );
        if (
            this.sceneSlice.offset() != 0L
                || this.device.vkDevice().getCapabilities()
                    .vkCmdDrawIndexedIndirectCount == 0L
        ) {
            throw new IllegalStateException(
                "native scene Vulkan binding preflight failed"
            );
        }
        try {
            createComputeObjects();
            allocateDescriptorSet();
            createSceneBufferView();
        } catch (Throwable error) {
            destroyRawObjects();
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException(
                "native terrain GPU-scene Vulkan creation failed",
                error
            );
        }
    }

    /**
     * Records count reset, conservative frustum compute and exact barriers
     * into Mojang's current command stream.
     */
    public void recordCulling(NativeTerrainFrustum frustum) {
        Objects.requireNonNull(frustum, "frustum");
        requireUsable();
        if (this.prepared) {
            throw new IllegalStateException(
                "native terrain frame is already prepared"
            );
        }
        long started = System.nanoTime();
        VkCommandBuffer commandBuffer =
            ((VulkanCommandEncoderAccessor)(Object)
                    this.device.createCommandEncoder())
                .nvidiaDlss$commandBuffer();
        VK12.vkCmdFillBuffer(
            commandBuffer,
            vkBuffer(this.countSlice),
            this.countSlice.offset(),
            this.countSlice.length(),
            0
        );
        this.zeroCountFills++;
        barrier(
            commandBuffer,
            KHRSynchronization2
                .VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
            KHRSynchronization2
                .VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
            KHRSynchronization2
                .VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,
            KHRSynchronization2
                .VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR
                | KHRSynchronization2
                    .VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR
        );
        this.barriers++;

        int sceneCount = frustum.sceneCount();
        if (sceneCount != 0) {
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
                    stack.longs(this.descriptorSet),
                    null
                );
                ByteBuffer constants = stack.malloc(
                    NativeTerrainFrustum.PUSH_CONSTANT_BYTES
                );
                frustum.writePushConstants(constants);
                constants.flip();
                VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.pipelineLayout,
                    VK12.VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    constants
                );
            }
            VK12.vkCmdDispatch(
                commandBuffer,
                (sceneCount + 63) / 64,
                1,
                1
            );
            this.dispatches++;
            NativeTerrainOwnershipEvidence
                .blockFrameComputeCullEncoded(
                    this.scene.evidenceToken()
                );
            barrier(
                commandBuffer,
                KHRSynchronization2
                    .VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,
                KHRSynchronization2
                    .VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR,
                KHRSynchronization2
                    .VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT_KHR
                    | KHRSynchronization2
                        .VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR,
                KHRSynchronization2
                    .VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR
                    | KHRSynchronization2
                        .VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
            );
        } else {
            barrier(
                commandBuffer,
                KHRSynchronization2
                    .VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                KHRSynchronization2
                    .VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                KHRSynchronization2
                    .VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT_KHR,
                KHRSynchronization2
                    .VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR
            );
        }
        this.barriers++;
        this.prepared = true;
        this.submissionStarted = false;
        this.lastRecordNanos = System.nanoTime() - started;
    }

    public boolean beginSubmission() {
        requireUsable();
        if (!this.prepared || this.submissionStarted) {
            return false;
        }
        this.submissionStarted = true;
        return true;
    }

    public void finishSubmission() {
        requireUsable();
        if (!this.submissionStarted) {
            throw new IllegalStateException(
                "native terrain submission did not start"
            );
        }
        this.prepared = false;
        this.submissionStarted = false;
    }

    public void cancelBeforeSubmission() {
        requireUsable();
        if (this.submissionStarted) {
            throw new IllegalStateException(
                "native terrain cannot replay after submission"
            );
        }
        this.prepared = false;
    }

    public GpuBufferSlice commandSlice() {
        requireUsable();
        return this.commandSlice;
    }

    public GpuBufferSlice countSlice() {
        requireUsable();
        return this.countSlice;
    }

    public long sceneBufferView() {
        requireUsable();
        return this.sceneBufferView;
    }

    public Metrics metrics() {
        return new Metrics(
            this.dispatches,
            this.zeroCountFills,
            this.barriers,
            this.lastRecordNanos,
            this.prepared,
            this.closing,
            this.closed
        );
    }

    public void beginClose(Completion lastUseCompletion) {
        if (this.closed || this.closing) {
            throw new IllegalStateException(
                "native terrain Vulkan resources already closing"
            );
        }
        if (this.prepared || this.submissionStarted) {
            throw new IllegalStateException(
                "native terrain frame must finish before close"
            );
        }
        this.closeCompletion = Objects.requireNonNull(
            lastUseCompletion,
            "lastUseCompletion"
        );
        this.closing = true;
    }

    public boolean pollClose() {
        if (this.closed) {
            return true;
        }
        if (!this.closing) {
            throw new IllegalStateException(
                "native terrain close was not begun"
            );
        }
        if (!this.closeCompletion.completed()) {
            return false;
        }
        destroyRawObjects();
        this.closeCompletion.close();
        this.closeCompletion = null;
        this.closed = true;
        this.closing = false;
        return true;
    }

    @Override
    public void close() {
        if (!this.closed) {
            throw new IllegalStateException(
                "use completion-driven beginClose/pollClose"
            );
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
                "native terrain compute shader module"
            );
            this.shaderModule = requireHandle(
                handle.get(0),
                "native terrain compute shader module"
            );
            this.inventory.created(ResourceKind.SHADER_MODULE);

            VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int index = 0; index < 3; index++) {
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
                "native terrain compute descriptor layout"
            );
            this.descriptorSetLayout = requireHandle(
                handle.get(0),
                "native terrain compute descriptor layout"
            );
            this.inventory.created(
                ResourceKind.DESCRIPTOR_SET_LAYOUT
            );

            VkPushConstantRange.Buffer pushRange =
                VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(
                        NativeTerrainFrustum.PUSH_CONSTANT_BYTES
                    );
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
                "native terrain compute pipeline layout"
            );
            this.pipelineLayout = requireHandle(
                handle.get(0),
                "native terrain compute pipeline layout"
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
                "native terrain compute pipeline"
            );
            this.pipeline = requireHandle(
                handle.get(0),
                "native terrain compute pipeline"
            );
            this.inventory.created(ResourceKind.COMPUTE_PIPELINE);

            VkDescriptorPoolSize.Buffer poolSize =
                VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(3);
            VkDescriptorPoolCreateInfo poolInfo =
                VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(1)
                    .pPoolSizes(poolSize);
            handle.put(0, 0L);
            check(
                VK12.vkCreateDescriptorPool(
                    this.device.vkDevice(),
                    poolInfo,
                    null,
                    handle
                ),
                "native terrain compute descriptor pool"
            );
            this.descriptorPool = requireHandle(
                handle.get(0),
                "native terrain compute descriptor pool"
            );
            this.inventory.created(ResourceKind.DESCRIPTOR_POOL);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private void allocateDescriptorSet() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocateInfo =
                VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(
                        stack.longs(this.descriptorSetLayout)
                    );
            LongBuffer result = stack.callocLong(1);
            check(
                VK12.vkAllocateDescriptorSets(
                    this.device.vkDevice(),
                    allocateInfo,
                    result
                ),
                "native terrain compute descriptor set"
            );
            this.descriptorSet = requireHandle(
                result.get(0),
                "native terrain compute descriptor set"
            );
            this.inventory.created(ResourceKind.DESCRIPTOR_SET);
            this.inventory.created(ResourceKind.DESCRIPTOR, 3);
            this.descriptorsInventoried = true;

            VkDescriptorBufferInfo.Buffer infos =
                VkDescriptorBufferInfo.calloc(3, stack);
            descriptorInfo(infos.get(0), this.sceneSlice);
            descriptorInfo(infos.get(1), this.commandSlice);
            descriptorInfo(infos.get(2), this.countSlice);
            VkWriteDescriptorSet.Buffer writes =
                VkWriteDescriptorSet.calloc(3, stack);
            for (int binding = 0; binding < 3; binding++) {
                writes.get(binding)
                    .sType$Default()
                    .dstSet(this.descriptorSet)
                    .dstBinding(binding)
                    .descriptorCount(1)
                    .descriptorType(
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    )
                    .pBufferInfo(
                        infos.position(binding).limit(binding + 1)
                    );
                infos.limit(3);
            }
            VK12.vkUpdateDescriptorSets(
                this.device.vkDevice(),
                writes,
                null
            );
        }
    }

    private void createSceneBufferView() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferViewCreateInfo info =
                VkBufferViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .buffer(vkBuffer(this.sceneSlice))
                    .offset(this.sceneSlice.offset())
                    .range(this.sceneSlice.length())
                    .format(VK12.VK_FORMAT_R32G32B32A32_UINT);
            LongBuffer result = stack.callocLong(1);
            check(
                VK12.vkCreateBufferView(
                    this.device.vkDevice(),
                    info,
                    null,
                    result
                ),
                "native terrain scene texel view"
            );
            this.sceneBufferView = requireHandle(
                result.get(0),
                "native terrain scene texel view"
            );
            this.inventory.created(ResourceKind.RAW_BUFFER_VIEW);
        }
    }

    private ByteBuffer compileComputeShader() throws IOException {
        byte[] source;
        try (
            InputStream input =
                NativeTerrainGpuSceneVulkanResources.class
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
            result = Shaderc.shaderc_compile_into_spv(
                compiler,
                new String(source, StandardCharsets.UTF_8),
                Shaderc.shaderc_compute_shader,
                "native_terrain_frustum_indirect_v1.comp",
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
            if (
                status
                    != Shaderc.shaderc_compilation_status_success
            ) {
                throw new IllegalStateException(
                    "native terrain compute shader failed: "
                        + Shaderc
                            .shaderc_result_get_error_message(result)
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

    private static void descriptorInfo(
        VkDescriptorBufferInfo info,
        GpuBufferSlice slice
    ) {
        info.buffer(vkBuffer(slice))
            .offset(slice.offset())
            .range(slice.length());
    }

    private static long vkBuffer(GpuBufferSlice slice) {
        return ((VulkanGpuBuffer)slice.buffer()).vkBuffer();
    }

    private static void barrier(
        VkCommandBuffer commandBuffer,
        long sourceStage,
        long sourceAccess,
        long destinationStage,
        long destinationAccess
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer memory =
                VkMemoryBarrier2.calloc(1, stack);
            memory.get(0)
                .sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess);
            VkDependencyInfo dependency =
                VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(memory);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                commandBuffer,
                dependency
            );
        }
    }

    private void destroyRawObjects() {
        if (this.sceneBufferView != 0L) {
            VK12.vkDestroyBufferView(
                this.device.vkDevice(),
                this.sceneBufferView,
                null
            );
            this.sceneBufferView = 0L;
            this.inventory.destroyed(ResourceKind.RAW_BUFFER_VIEW);
        }
        if (this.descriptorPool != 0L) {
            VK12.vkDestroyDescriptorPool(
                this.device.vkDevice(),
                this.descriptorPool,
                null
            );
            this.descriptorPool = 0L;
            if (this.descriptorsInventoried) {
                this.inventory.destroyed(ResourceKind.DESCRIPTOR, 3);
                this.inventory.destroyed(ResourceKind.DESCRIPTOR_SET);
                this.descriptorsInventoried = false;
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
    }

    private void requireUsable() {
        if (this.closed || this.closing) {
            throw new IllegalStateException(
                "native terrain Vulkan resources are unavailable"
            );
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
}
