package de.morau.nvidiadlss;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Owns the single reusable pose stack used by the native block-outline pass.
 *
 * <p>The owner is deliberately small and render-thread confined. It does not
 * own GPU state and is not a general pool. Minecraft 26.2 pushes the supplied
 * stack exactly once in {@code LevelRenderer.submitBlockOutline}; construction
 * prewarms that one retained pose so the normal outlined-frame path does not
 * grow the stack.</p>
 */
public final class NativeBlockOutlinePoseStackScratch {
    public static final int STATUS_ACTIVE =
        RenderThreadPoseStackScratch.STATUS_ACTIVE;
    public static final int STATUS_DISABLED =
        RenderThreadPoseStackScratch.STATUS_DISABLED;
    public static final int STATUS_CLEARED =
        RenderThreadPoseStackScratch.STATUS_CLEARED;

    private static final RenderThreadPoseStackScratch.Access<PoseStack>
        ACCESS = new PoseStackAccess();

    private final RenderThreadPoseStackScratch<PoseStack> state;

    private NativeBlockOutlinePoseStackScratch(
        RenderThreadPoseStackScratch<PoseStack> state
    ) {
        this.state = state;
    }

    /**
     * Lazily creates and prewarms the render-thread owner without letting an
     * optional scratch failure prevent the former fresh-stack path.
     */
    public static NativeBlockOutlinePoseStackScratch createForCurrentThread() {
        return new NativeBlockOutlinePoseStackScratch(
            RenderThreadPoseStackScratch.createForCurrentThread(ACCESS)
        );
    }

    /**
     * Acquires the stable stack or the exact former {@code new PoseStack()}
     * fallback. Invariant failures are handled before the Minecraft outline
     * submission begins, so this fallback is safe in the same frame.
     */
    public PoseStack beginUse() {
        return this.state.beginUse();
    }

    /**
     * Ends one use without ever masking an exception thrown by Minecraft.
     *
     * <p>Once the outline submission has been entered it is never replayed:
     * doing so could duplicate nodes already appended before an exception.
     * A thrown or unbalanced submission permanently discards this owner until
     * the renderer's reset/resize lifecycle rearms it.</p>
     */
    public void endUse(PoseStack used, boolean submissionCompleted) {
        this.state.endUse(used, submissionCompleted);
    }

    /** Permanently discards the current owner without touching GPU state. */
    public void clear() {
        this.state.clear();
    }

    public int status() {
        return this.state.status();
    }

    public long reuseUses() {
        return this.state.reuseUses();
    }

    public long freshFallbacks() {
        return this.state.freshFallbacks();
    }

    public long disableCount() {
        return this.state.disableCount();
    }

    public long reentrantFallbacks() {
        return this.state.reentrantFallbacks();
    }

    public long wrongThreadFallbacks() {
        return this.state.wrongThreadFallbacks();
    }

    public long abortedUses() {
        return this.state.abortedUses();
    }

    public long imbalanceDisables() {
        return this.state.imbalanceDisables();
    }

    public long unwoundPoses() {
        return this.state.unwoundPoses();
    }

    private static final class PoseStackAccess
        implements RenderThreadPoseStackScratch.Access<PoseStack> {
        @Override
        public PoseStack createFresh() {
            return new PoseStack();
        }

        @Override
        public void setIdentity(PoseStack stack) {
            stack.setIdentity();
        }

        @Override
        public void push(PoseStack stack) {
            stack.pushPose();
        }

        @Override
        public void pop(PoseStack stack) {
            stack.popPose();
        }

        @Override
        public boolean isEmpty(PoseStack stack) {
            return stack.isEmpty();
        }

        @Override
        public boolean isIdentity(PoseStack stack) {
            PoseStack.Pose last = stack.last();
            return NativeBlockOutlinePoseStackScratch.isIdentity(last.pose())
                && NativeBlockOutlinePoseStackScratch.isIdentity(
                    last.normal()
                );
        }
    }

    private static boolean isIdentity(Matrix4f matrix) {
        return matrix.m00() == 1.0F
            && matrix.m01() == 0.0F
            && matrix.m02() == 0.0F
            && matrix.m03() == 0.0F
            && matrix.m10() == 0.0F
            && matrix.m11() == 1.0F
            && matrix.m12() == 0.0F
            && matrix.m13() == 0.0F
            && matrix.m20() == 0.0F
            && matrix.m21() == 0.0F
            && matrix.m22() == 1.0F
            && matrix.m23() == 0.0F
            && matrix.m30() == 0.0F
            && matrix.m31() == 0.0F
            && matrix.m32() == 0.0F
            && matrix.m33() == 1.0F;
    }

    private static boolean isIdentity(Matrix3f matrix) {
        return matrix.m00() == 1.0F
            && matrix.m01() == 0.0F
            && matrix.m02() == 0.0F
            && matrix.m10() == 0.0F
            && matrix.m11() == 1.0F
            && matrix.m12() == 0.0F
            && matrix.m20() == 0.0F
            && matrix.m21() == 0.0F
            && matrix.m22() == 1.0F;
    }
}
