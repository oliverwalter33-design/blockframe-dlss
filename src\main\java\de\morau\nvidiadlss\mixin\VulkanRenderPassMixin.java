package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import de.morau.blockframe.render.terrain.gpuscene.OpaqueSolidIndirectRenderPass;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainIndirectRenderPass;
import de.morau.nvidiadlss.DeveloperDiagnostics;
import de.morau.nvidiadlss.DlssSamplerPolicy;
import de.morau.nvidiadlss.DlssTerrainSamplerScope;
import de.morau.nvidiadlss.FoliageAudit;
import de.morau.nvidiadlss.NvidiaDlssMod;
import java.util.HashMap;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin
    implements
        OpaqueSolidIndirectRenderPass,
        NativeTerrainIndirectRenderPass {
    @Shadow @Final private VulkanDevice device;
    @Shadow protected @Nullable VulkanRenderPipeline pipeline;
    @Shadow protected abstract VkCommandBuffer commandBuffer();
    @Shadow @Final protected HashMap<String, GpuBufferSlice> uniforms;
    @Shadow @Final protected HashMap<String, Object> textures;
    @Shadow private boolean anyDescriptorDirty;
    @Unique private @Nullable String nvidiaDlss$blockAtlasBinding;
    @Unique private @Nullable GpuTextureView nvidiaDlss$blockAtlasView;
    @Unique private static boolean nvidiaDlss$descriptorFallbackLogged;

    @Override
    public void blockframe$prepareOpaqueSolidDescriptors(
        long sceneBufferView,
        long visibilityBufferView
    ) {
        if (sceneBufferView == 0L || visibilityBufferView == 0L) {
            throw new IllegalArgumentException(
                "invalid opaque-solid cached buffer views"
            );
        }
        this.blockframe$pushCachedGpuSceneDescriptors(
            sceneBufferView,
            visibilityBufferView,
            0L
        );
    }

    @Override
    public void blockframe$prepareNativeTerrainDescriptors(
        long sceneBufferView
    ) {
        if (sceneBufferView == 0L) {
            throw new IllegalArgumentException(
                "invalid native terrain scene buffer view"
            );
        }
        this.blockframe$pushCachedGpuSceneDescriptors(
            0L,
            0L,
            sceneBufferView
        );
    }

    @Override
    public void blockframe$drawIndexedIndirectCount(
        GpuBuffer commands,
        long commandOffset,
        GpuBuffer counts,
        long countOffset,
        int maximumDrawCount,
        int commandStride
    ) {
        VulkanRenderPipeline activePipeline = this.pipeline;
        if (activePipeline == null || !activePipeline.isValid()) {
            throw new IllegalStateException(
                "opaque-solid indirect pipeline is missing or invalid"
            );
        }
        if (
            commands == null
                || counts == null
                || commands.isClosed()
                || counts.isClosed()
                || maximumDrawCount <= 0
                || commandStride
                    != org.lwjgl.vulkan
                        .VkDrawIndexedIndirectCommand.SIZEOF
        ) {
            throw new IllegalArgumentException(
                "invalid opaque-solid indirect buffers"
            );
        }
        if (this.anyDescriptorDirty) {
            throw new IllegalStateException(
                "opaque-solid descriptors were not prepared before "
                    + "the no-replay boundary"
            );
        }
        VK12.vkCmdDrawIndexedIndirectCount(
            this.commandBuffer(),
            ((VulkanGpuBuffer)commands).vkBuffer(),
            commandOffset,
            ((VulkanGpuBuffer)counts).vkBuffer(),
            countOffset,
            maximumDrawCount,
            commandStride
        );
    }

    @Override
    public void blockframe$drawNativeTerrainIndirectCount(
        GpuBufferSlice commands,
        GpuBufferSlice counts,
        int maximumDrawCount,
        int commandStride
    ) {
        VulkanRenderPipeline activePipeline = this.pipeline;
        if (activePipeline == null || !activePipeline.isValid()) {
            throw new IllegalStateException(
                "native terrain indirect pipeline is missing or invalid"
            );
        }
        if (
            commands == null
                || counts == null
                || commands.buffer().isClosed()
                || counts.buffer().isClosed()
                || maximumDrawCount <= 0
                || commandStride
                    != org.lwjgl.vulkan
                        .VkDrawIndexedIndirectCommand.SIZEOF
        ) {
            throw new IllegalArgumentException(
                "invalid native terrain indirect buffers"
            );
        }
        if (this.anyDescriptorDirty) {
            throw new IllegalStateException(
                "native terrain descriptors were not prepared before "
                    + "the no-replay boundary"
            );
        }
        VK12.vkCmdDrawIndexedIndirectCount(
            this.commandBuffer(),
            ((VulkanGpuBuffer)commands.buffer()).vkBuffer(),
            commands.offset(),
            ((VulkanGpuBuffer)counts.buffer()).vkBuffer(),
            counts.offset(),
            maximumDrawCount,
            commandStride
        );
    }

    /**
     * Pushes the exact Mojang graphics layout while reusing the two
     * generation-bound texel-buffer views owned by the GPU-scene frame.
     * Mojang's generic path creates and retires a VkBufferView for every
     * dirty push; this Vulkan-only path must have no warm object churn.
     */
    @Unique
    private void blockframe$pushCachedGpuSceneDescriptors(
        long sceneBufferView,
        long visibilityBufferView,
        long nativeTerrainSceneView
    ) {
        if (!this.anyDescriptorDirty) {
            return;
        }
        VulkanRenderPipeline activePipeline = this.pipeline;
        if (activePipeline == null || !activePipeline.isValid()) {
            throw new IllegalStateException(
                "opaque-solid descriptor pipeline is missing or invalid"
            );
        }
        VulkanBindGroupLayout layout = activePipeline.layout();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes =
                VkWriteDescriptorSet.calloc(
                    layout.entries().size(),
                    stack
                );
            for (int index = 0; index < layout.entries().size(); index++) {
                VulkanBindGroupLayout.Entry entry =
                    layout.entries().get(index);
                VkWriteDescriptorSet set = writes.get()
                    .sType$Default()
                    .dstBinding(index)
                    .dstArrayElement(0)
                    .descriptorCount(1);
                switch (entry.type()) {
                    case UNIFORM_BUFFER -> {
                        GpuBufferSlice buffer =
                            this.uniforms.get(entry.name());
                        if (
                            buffer == null
                                || buffer.buffer().isClosed()
                        ) {
                            throw new IllegalStateException(
                                "Missing or closed uniform "
                                    + entry.name()
                            );
                        }
                        VkDescriptorBufferInfo.Buffer bufferInfo =
                            VkDescriptorBufferInfo.calloc(1, stack)
                                .buffer(
                                    ((VulkanGpuBuffer)buffer.buffer())
                                        .vkBuffer()
                                )
                                .offset(buffer.offset())
                                .range(buffer.length());
                        set.descriptorType(
                            VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                        );
                        set.pBufferInfo(bufferInfo);
                    }
                    case SAMPLED_IMAGE -> {
                        Object raw = this.textures.get(entry.name());
                        if (
                            !(raw
                                instanceof
                                VulkanTextureViewAndSamplerAccessor value)
                        ) {
                            throw new IllegalStateException(
                                "Missing sampler " + entry.name()
                            );
                        }
                        VulkanGpuSampler selected =
                            this.nvidiaDlss$selectMaterialSamplerAtDescriptor(
                                value.blockframe$sampler(),
                                entry
                            );
                        VkDescriptorImageInfo.Buffer imageInfo =
                            VkDescriptorImageInfo.calloc(1, stack)
                                .sampler(selected.vkSampler())
                                .imageView(
                                    value.blockframe$view().vkImageView()
                                )
                                .imageLayout(
                                    VK12.VK_IMAGE_LAYOUT_GENERAL
                                );
                        set.descriptorType(
                            VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                        );
                        set.pImageInfo(imageInfo);
                    }
                    case TEXEL_BUFFER -> {
                        long view;
                        if (
                            "OpaqueSolidScene".equals(entry.name())
                        ) {
                            view = sceneBufferView;
                        } else if (
                            "OpaqueSolidVisibility".equals(entry.name())
                        ) {
                            view = visibilityBufferView;
                        } else if (
                            "NativeTerrainScene".equals(entry.name())
                        ) {
                            view = nativeTerrainSceneView;
                        } else {
                            throw new IllegalStateException(
                                "Unknown GPU-scene texel buffer "
                                    + entry.name()
                            );
                        }
                        if (view == 0L) {
                            throw new IllegalStateException(
                                "Missing GPU-scene texel buffer "
                                    + entry.name()
                            );
                        }
                        set.descriptorType(
                            VK12.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER
                        );
                        set.pTexelBufferView(stack.longs(view));
                    }
                }
            }
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(
                this.commandBuffer(),
                VK12.VK_PIPELINE_BIND_POINT_GRAPHICS,
                activePipeline.pipelineLayout(),
                0,
                writes.flip()
            );
        }
        this.anyDescriptorDirty = false;
    }

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void nvidiaDlss$trackAndAuditAtlasSampler(String name, GpuTextureView textureView,
        GpuSampler sampler, CallbackInfo ci) {
        if (textureView == null || sampler == null) {
            if (
                textureView == null
                    && sampler == null
                    && name != null
                    && name.equals(this.nvidiaDlss$blockAtlasBinding)
            ) {
                this.nvidiaDlss$blockAtlasBinding = null;
                this.nvidiaDlss$blockAtlasView = null;
            }
            return;
        }
        VulkanRenderPipeline activePipeline = this.pipeline;
        String textureLabel = textureView.texture().getLabel();
        boolean blockAtlas =
            DlssTerrainSamplerScope.isBlockAtlas(textureLabel);
        if (blockAtlas) {
            this.nvidiaDlss$blockAtlasBinding = name;
            this.nvidiaDlss$blockAtlasView = textureView;
        } else if (
            name != null
                && name.equals(this.nvidiaDlss$blockAtlasBinding)
        ) {
            this.nvidiaDlss$blockAtlasBinding = null;
            this.nvidiaDlss$blockAtlasView = null;
        }
        if (
            DeveloperDiagnostics.ENABLED
                && FoliageAudit.enabled()
                && !blockAtlas
        ) {
            String pipelineName = activePipeline == null
                ? "unbound"
                : activePipeline.info().getLocation().toString();
            FoliageAudit.recordSampler(
                "Vulkan",
                pipelineName,
                name,
                textureView,
                sampler
            );
        }
    }

    /**
     * Minecraft binds the block atlas before it assigns the first pipeline to
     * a render pass. Select at the descriptor handoff, where the exact
     * pipeline is known, without replacing Mojang's retained texture record.
     */
    @ModifyExpressionValue(
        method = "pushDescriptors",
        at = @At(
            value = "FIELD",
            target =
                "Lcom/mojang/blaze3d/vulkan/"
                    + "VulkanRenderPass$TextureViewAndSampler;"
                    + "sampler:Lcom/mojang/blaze3d/vulkan/"
                    + "VulkanGpuSampler;"
        )
    )
    private VulkanGpuSampler nvidiaDlss$selectMaterialSamplerAtDescriptor(
        VulkanGpuSampler original,
        @Local(name = "entry") VulkanBindGroupLayout.Entry entry
    ) {
        String blockAtlasBinding = this.nvidiaDlss$blockAtlasBinding;
        if (
            blockAtlasBinding == null
                || entry == null
                || !blockAtlasBinding.equals(entry.name())
        ) {
            return original;
        }
        try {
            VulkanRenderPipeline activePipeline = this.pipeline;
            if (activePipeline == null) {
                return original;
            }
            Identifier pipelineId =
                activePipeline.info().getLocation();
            if (
                !DlssTerrainSamplerScope.eligible(
                    pipelineId,
                    nvidiaDlss$hasBlend(activePipeline)
                )
            ) {
                return original;
            }
            GpuSampler selected = DlssSamplerPolicy.materialSampler(
                activePipeline.device(),
                original,
                DlssTerrainSamplerScope.isCutout(pipelineId)
            );
            VulkanGpuSampler selectedVulkan =
                selected instanceof VulkanGpuSampler vulkanSampler
                    ? vulkanSampler
                    : original;
            if (
                DeveloperDiagnostics.ENABLED
                    && FoliageAudit.enabled()
            ) {
                FoliageAudit.recordSampler(
                    "Vulkan",
                    activePipeline.info().getLocation().toString(),
                    blockAtlasBinding,
                    this.nvidiaDlss$blockAtlasView,
                    selectedVulkan
                );
            }
            return selectedVulkan;
        } catch (Throwable error) {
            if (!this.nvidiaDlss$descriptorFallbackLogged) {
                this.nvidiaDlss$descriptorFallbackLogged = true;
                try {
                    NvidiaDlssMod.LOGGER.warn(
                        "DLSS-Materialsampler-Deskriptor fiel auf "
                            + "den Originalsampler zurück",
                        error
                    );
                } catch (Throwable ignored) {
                    // Same-frame fallback is already the original sampler.
                }
            }
            return original;
        }
    }

    @Unique
    private static boolean nvidiaDlss$hasBlend(
        VulkanRenderPipeline pipeline
    ) {
        ColorTargetState[] colorTargets =
            pipeline.info().getColorTargetStates();
        if (colorTargets == null || colorTargets.length == 0) {
            return true;
        }
        for (ColorTargetState colorTarget : colorTargets) {
            if (
                colorTarget != null
                    && colorTarget.blendFunction().isPresent()
            ) {
                return true;
            }
        }
        return false;
    }
}
