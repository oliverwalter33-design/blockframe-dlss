package de.morau.blockframe.vulkan;

/**
 * Fail-closed health marker for the optional fatal device-loss hook.
 *
 * <p>The marker is published by the Mixin config plugin only after it has
 * inspected the transformed Mojang method. Device-fault negotiation must not
 * enable the extension merely because the optional mixin class was selected.
 */
public final class VulkanDeviceFaultHookHealth {
    private static final String VULKAN_UTILS_CLASS =
        "com.mojang.blaze3d.vulkan.VulkanUtils";

    private static volatile boolean fatalHookApplied;

    private VulkanDeviceFaultHookHealth() {
    }

    /**
     * Forces transformation of the fatal Mojang utility before negotiation.
     * A missing class, transform conflict or linkage problem is simply an
     * unavailable optional diagnostic.
     */
    public static boolean ensureFatalHookReady() {
        try {
            Class.forName(
                VULKAN_UTILS_CLASS,
                false,
                VulkanDeviceFaultHookHealth.class.getClassLoader()
            );
        } catch (
            ClassNotFoundException
                | RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            return false;
        }
        return fatalHookApplied;
    }

    public static void publishFatalHookApplied(boolean applied) {
        fatalHookApplied = applied;
    }
}
