package de.morau.nvidiadlss.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.vulkan.VulkanDeviceFaultHookHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class VulkanDeviceFaultHookHealthTest {
    private static final String TARGET =
        "com/mojang/blaze3d/vulkan/VulkanUtils";
    private static final String MIXIN =
        "de.morau.nvidiadlss.mixin.VulkanUtilsDeviceFaultMixin";

    @AfterEach
    void resetMarker() {
        VulkanDeviceFaultHookHealth.publishFatalHookApplied(false);
    }

    @Test
    void markerRequiresTheFatalMethodToInvokeTheMergedCaptureHandler() {
        ClassNode target = transformedClass(true);
        new DlssMixinPlugin().postApply(
            TARGET.replace('/', '.'),
            target,
            MIXIN,
            null
        );

        assertTrue(
            VulkanDeviceFaultHookHealth.ensureFatalHookReady()
        );
    }

    @Test
    void aMergedButUnwiredHandlerFailsClosed() {
        ClassNode target = transformedClass(false);
        new DlssMixinPlugin().postApply(
            TARGET.replace('/', '.'),
            target,
            MIXIN,
            null
        );

        assertFalse(
            VulkanDeviceFaultHookHealth.ensureFatalHookReady()
        );
    }

    private static ClassNode transformedClass(boolean wireHandler) {
        ClassNode target = new ClassNode();
        target.name = TARGET;
        MethodNode handler = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "handler$phase1a11$blockframe$captureDeviceFault",
            "(Lcom/mojang/blaze3d/vulkan/VulkanDevice;"
                + "ILjava/lang/String;"
                + "Lorg/spongepowered/asm/mixin/injection/callback/"
                + "CallbackInfo;)V",
            null,
            null
        );
        handler.instructions.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "de/morau/blockframe/core/BlockframeRuntime",
                "recordVulkanDeviceLost",
                "(Lcom/mojang/blaze3d/vulkan/VulkanDevice;"
                    + "ILjava/lang/String;)"
                    + "Lde/morau/blockframe/core/diagnostics/"
                    + "DeviceFaultDiagnostics$Snapshot;",
                false
            )
        );
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        target.methods.add(handler);

        MethodNode crash = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "crashIfFailure",
            "(Lcom/mojang/blaze3d/vulkan/VulkanDevice;"
                + "ILjava/lang/String;)V",
            null,
            null
        );
        if (wireHandler) {
            crash.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    TARGET,
                    handler.name,
                    handler.desc,
                    false
                )
            );
        }
        crash.instructions.add(new InsnNode(Opcodes.RETURN));
        target.methods.add(crash);
        return target;
    }
}
