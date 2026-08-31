package de.morau.nvidiadlss;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.memory.ReusableObjectSlab;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Fixed heap-object scratch for DLSS camera and clip transformations.
 *
 * <p>All mutable math objects come from one budgeted object slab. The
 * successful frame path only mutates stable instances. In particular, the
 * committed previous transform history is not changed while preparing a
 * frame; the renderer must explicitly commit it after a successful
 * evaluation.</p>
 */
final class DlssTransformScratch implements AutoCloseable {
    static final int MATRIX_COUNT = 11;
    static final int VECTOR_COUNT = 3;
    static final int QUATERNION_COUNT = 1;
    static final int OBJECT_COUNT =
        MATRIX_COUNT + VECTOR_COUNT + QUATERNION_COUNT;
    static final long REQUESTED_OBJECT_BYTES =
        MATRIX_COUNT * 68L + VECTOR_COUNT * 12L + 16L;
    static final long COMMITTED_OBJECT_BYTES =
        MATRIX_COUNT * 88L + VECTOR_COUNT * 32L + 32L;
    static final ReusableObjectSlab.Layout LAYOUT =
        new ReusableObjectSlab.Layout(
            OBJECT_COUNT,
            REQUESTED_OBJECT_BYTES,
            COMMITTED_OBJECT_BYTES
        );

    private static final ThreadLocal<RetainedFailedCreation>
        RETAINED_FAILED_CREATION =
            ThreadLocal.withInitial(RetainedFailedCreation::new);

    private final Thread ownerThread;
    private ReusableObjectSlab<Object> slab;
    private Matrix4f projection;
    private Matrix4f currentViewRotation;
    private Matrix4f viewProjection;
    private Matrix4f previousViewProjection;
    private Matrix4f previousProjection;
    private Matrix4f previousViewRotation;
    private Matrix4f inverseViewProjection;
    private Matrix4f clipToPrevious;
    private Matrix4f previousToClip;
    private Matrix4f inverseProjection;
    private Matrix4f overlayProjection;
    private Vector3f up;
    private Vector3f right;
    private Vector3f forward;
    private Quaternionf previousOrientation;
    private double currentCameraX;
    private double currentCameraY;
    private double currentCameraZ;
    private double previousCameraX;
    private double previousCameraY;
    private double previousCameraZ;
    private boolean frameBegun;
    private boolean projectionCaptured;
    private boolean previousViewProjectionValid;
    private boolean previousOrientationValid;
    private boolean prepared;
    private boolean effectiveReset;
    private boolean closed;

    private DlssTransformScratch(ReusableObjectSlab<Object> slab) {
        this.ownerThread = Thread.currentThread();
        this.slab = slab;
        this.projection = acquire(slab, Matrix4f.class);
        this.currentViewRotation = acquire(slab, Matrix4f.class);
        this.viewProjection = acquire(slab, Matrix4f.class);
        this.previousViewProjection = acquire(slab, Matrix4f.class);
        this.previousProjection = acquire(slab, Matrix4f.class);
        this.previousViewRotation = acquire(slab, Matrix4f.class);
        this.inverseViewProjection = acquire(slab, Matrix4f.class);
        this.clipToPrevious = acquire(slab, Matrix4f.class);
        this.previousToClip = acquire(slab, Matrix4f.class);
        this.inverseProjection = acquire(slab, Matrix4f.class);
        this.overlayProjection = acquire(slab, Matrix4f.class);
        this.up = acquire(slab, Vector3f.class);
        this.right = acquire(slab, Vector3f.class);
        this.forward = acquire(slab, Vector3f.class);
        this.previousOrientation = acquire(slab, Quaternionf.class);
        if (slab.tryAcquire() != null) {
            throw new IllegalStateException(
                "transform scratch slab has unexpected extra objects"
            );
        }
        slab.reset();
    }

    static DlssTransformScratch tryCreate(MemoryBudgetManager budgets) {
        Objects.requireNonNull(budgets, "budgets");
        closeRetainedFailedCreation();
        RetainedFailedCreation retained =
            RETAINED_FAILED_CREATION.get();
        ReusableObjectSlab<Object> slab = ReusableObjectSlab.tryCreate(
            budgets,
            MemoryCategory.SHADER_RESOURCES,
            LAYOUT,
            DlssTransformScratch::createObject
        );
        if (slab == null) {
            return null;
        }

        retained.record(slab);
        try {
            DlssTransformScratch created =
                new DlssTransformScratch(slab);
            retained.transferOwnership();
            return created;
        } catch (RuntimeException | Error creationFailure) {
            retained.retry(creationFailure);
            throw creationFailure;
        }
    }

    /**
     * Retries cleanup of an otherwise unreachable partial construction.
     *
     * <p>This is normally a no-op because the private slot factory and
     * constructor have a fixed matching layout. If their defensive
     * consistency checks ever fail and lease release also fails, ownership is
     * retained here rather than silently lost. Creation retries this cleanup
     * first; final renderer shutdown may call it explicitly as well.</p>
     */
    static void closeRetainedFailedCreation() {
        RETAINED_FAILED_CREATION.get().retryOrThrow();
        ReusableObjectSlab.retryPendingCleanup();
    }

    /**
     * Starts transient state for a render frame while retaining temporal
     * history committed by prior successful evaluations.
     */
    void beginFrame() {
        this.requireAccessible();
        this.frameBegun = true;
        this.projectionCaptured = false;
        this.prepared = false;
        this.effectiveReset = false;
    }

    /**
     * Copies the unjittered projection into scratch storage. The supplied
     * projection is never modified.
     */
    void captureUnjitteredProjection(Matrix4f source) {
        this.requireFrameBegun();
        this.projection.set(
            Objects.requireNonNull(source, "source")
        );
        this.projectionCaptured = true;
        this.prepared = false;
    }

    /**
     * Returns a stable copy for overlay rendering, or {@code null} when this
     * frame did not capture an unjittered projection.
     */
    Matrix4f copyProjectionForOverlay() {
        this.requireFrameBegun();
        if (!this.projectionCaptured) {
            return null;
        }
        return this.overlayProjection.set(this.projection);
    }

    /**
     * Reproduces the renderer's camera transform preparation in stable slots.
     *
     * @return whether DLSS must reset for this frame
     */
    boolean prepareCurrentTransforms(
        Matrix4f fallbackProjection,
        Matrix4f viewRotation,
        Quaternionf orientation,
        double cameraX,
        double cameraY,
        double cameraZ,
        boolean resetRequested
    ) {
        this.requireFrameBegun();
        Objects.requireNonNull(fallbackProjection, "fallbackProjection");
        Objects.requireNonNull(viewRotation, "viewRotation");
        Objects.requireNonNull(orientation, "orientation");

        if (!this.projectionCaptured) {
            this.projection.set(fallbackProjection);
        }
        this.currentViewRotation.set(viewRotation);
        this.currentCameraX = cameraX;
        this.currentCameraY = cameraY;
        this.currentCameraZ = cameraZ;
        this.viewProjection
            .set(this.projection)
            .mul(this.currentViewRotation)
            .translate(
                (float)-cameraX,
                (float)-cameraY,
                (float)-cameraZ
            );
        this.inverseViewProjection
            .set(this.viewProjection)
            .invert();
        Matrix4f previousProjectionForFrame =
            this.previousViewProjectionValid
                ? this.previousProjection
                : this.projection;
        Matrix4f previousViewRotationForFrame =
            this.previousViewProjectionValid
                ? this.previousViewRotation
                : this.currentViewRotation;
        setCameraRelativeClipTransforms(
            this.clipToPrevious,
            this.previousToClip,
            previousProjectionForFrame,
            previousViewRotationForFrame,
            this.previousViewProjectionValid
                ? this.previousCameraX
                : cameraX,
            this.previousViewProjectionValid
                ? this.previousCameraY
                : cameraY,
            this.previousViewProjectionValid
                ? this.previousCameraZ
                : cameraZ,
            this.projection,
            this.currentViewRotation,
            cameraX,
            cameraY,
            cameraZ
        );
        this.inverseProjection
            .set(this.projection)
            .invert();
        this.up.set(0.0F, 1.0F, 0.0F).rotate(orientation);
        this.right.set(1.0F, 0.0F, 0.0F).rotate(orientation);
        this.forward.set(0.0F, 0.0F, -1.0F).rotate(orientation);
        this.effectiveReset =
            resetRequested || !this.previousViewProjectionValid;
        this.prepared = true;
        return this.effectiveReset;
    }

    /**
     * Builds the static-world clip reprojection without subtracting absolute
     * camera translations after they have already been rounded to float.
     *
     * <p>The budgeted transform path and its allocation fallback deliberately
     * share this helper. Camera displacement is formed in double precision and
     * only the small relative value is converted to float for</p>
     *
     * <pre>
     * Pprev * Rprev * T(Ccurrent - Cprevious)
     *     * inverse(Rcurrent) * inverse(Pcurrent)
     * </pre>
     */
    static void setCameraRelativeClipTransforms(
        Matrix4f clipToPrevious,
        Matrix4f previousToClip,
        Matrix4f previousProjection,
        Matrix4f previousViewRotation,
        double previousCameraX,
        double previousCameraY,
        double previousCameraZ,
        Matrix4f currentProjection,
        Matrix4f currentViewRotation,
        double currentCameraX,
        double currentCameraY,
        double currentCameraZ
    ) {
        Objects.requireNonNull(clipToPrevious, "clipToPrevious");
        Objects.requireNonNull(previousToClip, "previousToClip");
        Objects.requireNonNull(previousProjection, "previousProjection");
        Objects.requireNonNull(
            previousViewRotation,
            "previousViewRotation"
        );
        Objects.requireNonNull(currentProjection, "currentProjection");
        Objects.requireNonNull(currentViewRotation, "currentViewRotation");

        double cameraDeltaX = currentCameraX - previousCameraX;
        double cameraDeltaY = currentCameraY - previousCameraY;
        double cameraDeltaZ = currentCameraZ - previousCameraZ;
        previousToClip
            .set(currentProjection)
            .mul(currentViewRotation)
            .invert();
        clipToPrevious
            .set(previousProjection)
            .mul(previousViewRotation)
            .translate(
                (float)cameraDeltaX,
                (float)cameraDeltaY,
                (float)cameraDeltaZ
            )
            .mul(previousToClip);
        previousToClip.set(clipToPrevious).invert();
    }

    Matrix4f projection() {
        this.requirePrepared();
        return this.projection;
    }

    Matrix4f viewProjection() {
        this.requirePrepared();
        return this.viewProjection;
    }

    Matrix4f previousViewProjectionForFrame() {
        this.requirePrepared();
        return this.previousViewProjectionValid
            ? this.previousViewProjection
            : this.viewProjection;
    }

    Matrix4f inverseViewProjection() {
        this.requirePrepared();
        return this.inverseViewProjection;
    }

    Matrix4f clipToPrevious() {
        this.requirePrepared();
        return this.clipToPrevious;
    }

    Matrix4f previousToClip() {
        this.requirePrepared();
        return this.previousToClip;
    }

    Matrix4f inverseProjection() {
        this.requirePrepared();
        return this.inverseProjection;
    }

    Vector3f up() {
        this.requirePrepared();
        return this.up;
    }

    Vector3f right() {
        this.requirePrepared();
        return this.right;
    }

    Vector3f forward() {
        this.requirePrepared();
        return this.forward;
    }

    boolean effectiveReset() {
        this.requirePrepared();
        return this.effectiveReset;
    }

    boolean hasPreviousViewProjection() {
        this.requireAccessible();
        return this.previousViewProjectionValid;
    }

    /**
     * Publishes all prepared temporal transform history only after the
     * caller's evaluate operation has succeeded.
     */
    void commitPreviousViewProjection() {
        this.requirePrepared();
        this.previousViewProjection.set(this.viewProjection);
        this.previousProjection.set(this.projection);
        this.previousViewRotation.set(this.currentViewRotation);
        this.previousCameraX = this.currentCameraX;
        this.previousCameraY = this.currentCameraY;
        this.previousCameraZ = this.currentCameraZ;
        this.previousViewProjectionValid = true;
    }

    void resetPreviousViewProjection() {
        this.requireAccessible();
        this.previousViewProjection.identity();
        this.previousProjection.identity();
        this.previousViewRotation.identity();
        this.previousCameraX = 0.0D;
        this.previousCameraY = 0.0D;
        this.previousCameraZ = 0.0D;
        this.previousViewProjectionValid = false;
        this.prepared = false;
        this.effectiveReset = false;
    }

    boolean hasPreviousOrientation() {
        this.requireAccessible();
        return this.previousOrientationValid;
    }

    float previousOrientationDot(Quaternionf orientation) {
        this.requireAccessible();
        if (!this.previousOrientationValid) {
            throw new IllegalStateException(
                "previous orientation is not valid"
            );
        }
        return this.previousOrientation.dot(
            Objects.requireNonNull(orientation, "orientation")
        );
    }

    void rememberOrientation(Quaternionf orientation) {
        this.requireAccessible();
        this.previousOrientation.set(
            Objects.requireNonNull(orientation, "orientation")
        );
        this.previousOrientationValid = true;
    }

    /** Clears all transient and device-relative temporal state. */
    void clearDeviceState() {
        this.requireAccessible();
        this.projection.identity();
        this.currentViewRotation.identity();
        this.viewProjection.identity();
        this.previousViewProjection.identity();
        this.previousProjection.identity();
        this.previousViewRotation.identity();
        this.inverseViewProjection.identity();
        this.clipToPrevious.identity();
        this.previousToClip.identity();
        this.inverseProjection.identity();
        this.overlayProjection.identity();
        this.up.zero();
        this.right.zero();
        this.forward.zero();
        this.previousOrientation.identity();
        this.currentCameraX = 0.0D;
        this.currentCameraY = 0.0D;
        this.currentCameraZ = 0.0D;
        this.previousCameraX = 0.0D;
        this.previousCameraY = 0.0D;
        this.previousCameraZ = 0.0D;
        this.frameBegun = false;
        this.projectionCaptured = false;
        this.previousViewProjectionValid = false;
        this.previousOrientationValid = false;
        this.prepared = false;
        this.effectiveReset = false;
    }

    @Override
    public void close() {
        this.requireOwnerThread();
        if (this.closed) {
            return;
        }

        this.slab.close();
        this.slab = null;
        this.projection = null;
        this.currentViewRotation = null;
        this.viewProjection = null;
        this.previousViewProjection = null;
        this.previousProjection = null;
        this.previousViewRotation = null;
        this.inverseViewProjection = null;
        this.clipToPrevious = null;
        this.previousToClip = null;
        this.inverseProjection = null;
        this.overlayProjection = null;
        this.up = null;
        this.right = null;
        this.forward = null;
        this.previousOrientation = null;
        this.currentCameraX = 0.0D;
        this.currentCameraY = 0.0D;
        this.currentCameraZ = 0.0D;
        this.previousCameraX = 0.0D;
        this.previousCameraY = 0.0D;
        this.previousCameraZ = 0.0D;
        this.frameBegun = false;
        this.projectionCaptured = false;
        this.previousViewProjectionValid = false;
        this.previousOrientationValid = false;
        this.prepared = false;
        this.effectiveReset = false;
        this.closed = true;
    }

    private void requirePrepared() {
        this.requireFrameBegun();
        if (!this.prepared) {
            throw new IllegalStateException(
                "transform scratch has not prepared a frame"
            );
        }
    }

    private void requireFrameBegun() {
        this.requireAccessible();
        if (!this.frameBegun) {
            throw new IllegalStateException(
                "transform scratch frame has not begun"
            );
        }
    }

    private void requireAccessible() {
        this.requireOwnerThread();
        if (this.closed) {
            throw new IllegalStateException(
                "transform scratch is closed"
            );
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException(
                "transform scratch accessed from a non-owner thread"
            );
        }
    }

    private static Object createObject(int index) {
        if (index < MATRIX_COUNT) {
            return new Matrix4f();
        }
        if (index < MATRIX_COUNT + VECTOR_COUNT) {
            return new Vector3f();
        }
        if (index == OBJECT_COUNT - 1) {
            return new Quaternionf();
        }
        throw new IllegalArgumentException(
            "unsupported transform scratch slot " + index
        );
    }

    private static <T> T acquire(
        ReusableObjectSlab<Object> slab,
        Class<T> type
    ) {
        return type.cast(
            Objects.requireNonNull(
                slab.tryAcquire(),
                "transform scratch slab exhausted during construction"
            )
        );
    }

    /**
     * Constructor-failure owner scoped to the render thread. The holder is
     * initialized before the slab is created so retaining ownership cannot
     * itself allocate after the budget reservation succeeds.
     */
    private static final class RetainedFailedCreation {
        private final Thread ownerThread = Thread.currentThread();
        private ReusableObjectSlab<Object> slab;

        private void record(ReusableObjectSlab<Object> createdSlab) {
            this.requireOwnerThread();
            if (this.slab != null) {
                throw new IllegalStateException(
                    "transform scratch cleanup owner is already active"
                );
            }
            this.slab = Objects.requireNonNull(
                createdSlab,
                "createdSlab"
            );
        }

        private void transferOwnership() {
            this.requireOwnerThread();
            if (this.slab == null) {
                throw new IllegalStateException(
                    "transform scratch cleanup owner is empty"
                );
            }
            this.slab = null;
        }

        private void retryOrThrow() {
            this.requireOwnerThread();
            if (this.slab == null) {
                return;
            }
            IllegalStateException cleanupFailure =
                new IllegalStateException(
                    "pending transform scratch creation cleanup failed"
                );
            if (!this.retry(cleanupFailure)) {
                throw cleanupFailure;
            }
        }

        private boolean retry(Throwable failure) {
            this.requireOwnerThread();
            if (this.slab == null) {
                return true;
            }
            try {
                this.slab.close();
            } catch (Throwable closeFailure) {
                if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                return false;
            }
            this.slab = null;
            return true;
        }

        private void requireOwnerThread() {
            if (Thread.currentThread() != this.ownerThread) {
                throw new IllegalStateException(
                    "transform scratch cleanup retried from"
                        + " a non-owner thread"
                );
            }
        }
    }
}
