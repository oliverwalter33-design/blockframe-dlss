package de.morau.nvidiadlss.mixin;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import de.morau.blockframe.vulkan.VulkanDeviceFaultHookHealth;
import de.morau.nvidiadlss.BricksCompatibility;
import de.morau.nvidiadlss.DeveloperDiagnostics;
import de.morau.nvidiadlss.SodiumCompatibility;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class DlssMixinPlugin implements IMixinConfigPlugin {
    static final String NATIVE_TERRAIN_EVIDENCE_PROPERTY =
        "blockframe.nativeTerrain.exclusiveFixtureEvidence";

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (BricksCompatibility.isBricksMixin(mixinClassName)) {
            return BricksCompatibility.isExpectedMixinTarget(
                    mixinClassName,
                    targetClassName
                )
                && BricksCompatibility.mixinAllowed();
        }
        return shouldApplyMixinForEnvironment(
            mixinClassName,
            sodiumPresent()
        );
    }

    static boolean shouldApplyMixinForEnvironment(
        String mixinClassName,
        boolean sodiumPresent
    ) {
        return shouldApplyMixinForEnvironment(
            mixinClassName,
            sodiumPresent,
            DeveloperDiagnostics.enabled()
        );
    }

    static boolean shouldApplyMixinForEnvironment(
        String mixinClassName,
        boolean sodiumPresent,
        boolean developerDiagnostics
    ) {
        if (BricksCompatibility.isBricksMixin(mixinClassName)) {
            return BricksCompatibility.mixinAllowed();
        }
        if (isDeveloperDiagnosticsMixin(mixinClassName)) {
            return developerDiagnostics;
        }
        if (isSodiumOptionsMixin(mixinClassName)) {
            return sodiumPresent;
        }
        if (
            !nativeTerrainEvidenceMixinEnabled(mixinClassName)
        ) {
            return false;
        }
        return SodiumCompatibility.mixinAllowed(
            mixinClassName,
            sodiumPresent
        );
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override
    public void postApply(
        String targetClassName,
        ClassNode targetClass,
        String mixinClassName,
        IMixinInfo mixinInfo
    ) {
        if (
            !"com.mojang.blaze3d.vulkan.VulkanUtils".equals(
                targetClassName
            )
                || !mixinClassName.endsWith(
                    ".VulkanUtilsDeviceFaultMixin"
                )
        ) {
            return;
        }
        VulkanDeviceFaultHookHealth.publishFatalHookApplied(
            hasDeviceFaultCall(targetClass)
        );
    }

    private static boolean hasDeviceFaultCall(ClassNode targetClass) {
        Set<String> captureHandlers = new HashSet<>();
        for (MethodNode method : targetClass.methods) {
            for (
                AbstractInsnNode instruction =
                    method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()
            ) {
                if (
                    instruction instanceof MethodInsnNode call
                        && "de/morau/blockframe/core/BlockframeRuntime"
                            .equals(call.owner)
                        && "recordVulkanDeviceLost".equals(call.name)
                ) {
                    captureHandlers.add(method.name + method.desc);
                }
            }
        }
        if (captureHandlers.isEmpty()) {
            return false;
        }
        for (MethodNode method : targetClass.methods) {
            if (
                !"crashIfFailure".equals(method.name)
                    || !(
                        "(Lcom/mojang/blaze3d/vulkan/VulkanDevice;"
                            + "ILjava/lang/String;)V"
                    ).equals(method.desc)
            ) {
                continue;
            }
            for (
                AbstractInsnNode instruction =
                    method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()
            ) {
                if (
                    instruction instanceof MethodInsnNode call
                        && targetClass.name.equals(call.owner)
                        && captureHandlers.contains(
                            call.name + call.desc
                        )
                ) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean sodiumPresent() {
        return detectedByLoadingMetadata()
            || SodiumCompatibility.detected(DlssMixinPlugin.class.getClassLoader());
    }

    static boolean isFastStartTelemetryMixin(String mixinClassName) {
        return mixinClassName != null
            && (
                mixinClassName.endsWith(
                    ".FastStartResourceReloadMixin"
                )
                    || mixinClassName.endsWith(
                        ".FastStartLevelLoadingScreenMixin"
                    )
            );
    }

    static boolean isDeveloperDiagnosticsMixin(String mixinClassName) {
        return isFastStartTelemetryMixin(mixinClassName)
            || endsWithAny(
                mixinClassName,
                ".LevelExtractorTelemetryMixin",
                ".RenderPassTelemetryMixin",
                ".StagingBufferUploaderTelemetryMixin",
                ".StagingBufferInvoker",
                ".VulkanUtilsDeviceFaultMixin",
                ".GameRendererDiagnosticsMixin",
                ".LevelRendererDiagnosticsMixin",
                ".VulkanCommandEncoderDiagnosticsMixin",
                ".VulkanDeviceDiagnosticsMixin",
                ".GlRenderPassMixin",
                ".MipmapGeneratorMixin",
                ".SpriteContentsMixin"
            );
    }

    static boolean isSodiumOptionsMixin(String mixinClassName) {
        return mixinClassName != null
            && (
                mixinClassName.endsWith(
                    ".sodium.OptionBuilderAccessor"
                )
                    || mixinClassName.endsWith(
                        ".sodium.SodiumConfigBuilderMixin"
                    )
            );
    }

    private static boolean endsWithAny(
        String value,
        String... suffixes
    ) {
        if (value == null) {
            return false;
        }
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    static boolean isNativeTerrainEvidenceMixin(String mixinClassName) {
        return mixinClassName != null
            && (
                mixinClassName.endsWith(
                    ".NativeTerrainDispatcherEvidenceMixin"
                )
                    || mixinClassName.endsWith(
                        ".NativeTerrainOpaqueSubmissionEvidenceMixin"
                    )
                    || mixinClassName.endsWith(
                        ".NativeTerrainSectionCompilerEvidenceMixin"
                    )
                    || mixinClassName.endsWith(
                        ".NativeTerrainUberHeapEvidenceMixin"
                    )
            );
    }

    static boolean nativeTerrainEvidenceMixinEnabled(
        String mixinClassName
    ) {
        return !isNativeTerrainEvidenceMixin(mixinClassName)
            || Boolean.getBoolean(
                NATIVE_TERRAIN_EVIDENCE_PROPERTY
            );
    }

    private static boolean detectedByLoadingMetadata() {
        try {
            FMLLoader loader = FMLLoader.getCurrentOrNull();
            return loader != null
                && loader.getLoadingModList() != null
                && loader.getLoadingModList().getModFileById("sodium") != null;
        } catch (IllegalStateException | LinkageError ignored) {
            return false;
        }
    }
}
