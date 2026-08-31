package de.morau.blockframe.core.diagnostics;

import java.util.function.Supplier;

/**
 * Stable identities for the GPU-producing work that BlockFrame owns today.
 *
 * <p>Each enum value is also the immutable label supplier used by Mojang's
 * Vulkan diagnostics. This keeps Debug Utils and Tracy names identical
 * without constructing a supplier or label in the warmed render path.</p>
 */
public enum GpuPassIdentity implements Supplier<String> {
    FRAME(0, "BlockFrame / Frame"),
    MOTION_COMPUTE(
        GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE,
        "BlockFrame / Motion Compute"
    ),
    DLSS_EVALUATE(
        GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE,
        "BlockFrame / DLSS Evaluate"
    ),
    GRAPHICS_SUBMIT(
        GpuSubmissionBreadcrumbs.PASS_GRAPHICS_SUBMIT,
        "BlockFrame / Graphics Submit"
    );

    private final int breadcrumbId;
    private final String label;

    GpuPassIdentity(int breadcrumbId, String label) {
        this.breadcrumbId = breadcrumbId;
        this.label = label;
    }

    public int breadcrumbId() {
        return this.breadcrumbId;
    }

    public String label() {
        return this.label;
    }

    @Override
    public String get() {
        return this.label;
    }

    public static GpuPassIdentity fromBreadcrumbId(int breadcrumbId) {
        return switch (breadcrumbId) {
            case GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE ->
                MOTION_COMPUTE;
            case GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE ->
                DLSS_EVALUATE;
            case GpuSubmissionBreadcrumbs.PASS_GRAPHICS_SUBMIT ->
                GRAPHICS_SUBMIT;
            default -> throw new IllegalArgumentException(
                "unknown GPU breadcrumb pass " + breadcrumbId
            );
        };
    }
}
