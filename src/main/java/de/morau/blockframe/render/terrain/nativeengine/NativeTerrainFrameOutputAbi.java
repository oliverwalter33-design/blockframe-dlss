package de.morau.blockframe.render.terrain.nativeengine;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * GPU-independent contract for one complete native terrain frame.
 *
 * <p>The ABI stores one canonical world-space normal. Camera-space normals are
 * an exact logical output derived with the inverse-transpose of the current
 * unjittered view matrix. This avoids committing a second full-resolution
 * attachment while retaining an unambiguous camera-normal semantic.</p>
 */
public final class NativeTerrainFrameOutputAbi implements AutoCloseable {
    public static final int VERSION = 1;
    public static final boolean DEPTH_REVERSED_Z = true;
    public static final float DEPTH_CLEAR_VALUE = 0.0F;
    public static final float MOTION_INVALID_SENTINEL = 65_504.0F;
    public static final int INVALID_SURFACE_ID = 0;
    public static final float CUTOUT_ALPHA_THRESHOLD = 0.5F;
    public static final float CUTOUT_ALPHA_MARKER = 254.0F / 255.0F;
    public static final float NORMAL_BACKGROUND_X = 0.0F;
    public static final float NORMAL_BACKGROUND_Y = 0.0F;
    public static final float NORMAL_BACKGROUND_Z = 0.0F;
    public static final float NORMAL_BACKGROUND_VALIDITY = 0.0F;
    public static final float NORMAL_TERRAIN_VALIDITY = 1.0F;
    public static final boolean MOTION_CURRENT_TO_PREVIOUS = true;
    public static final boolean MOTION_UNJITTERED = true;

    public static final long FRAME_METADATA_VALID_MASK =
        Semantic.EXPOSURE_JITTER_METADATA.bit()
            | Semantic.GENERATION_RESET_METADATA.bit();
    public static final long STORED_TERRAIN_OUTPUT_MASK =
        Semantic.COLOR.bit()
            | Semantic.DEPTH.bit()
            | Semantic.WORLD_NORMAL.bit()
            | Semantic.SURFACE.bit();
    public static final long TERRAIN_VALID_MASK =
        FRAME_METADATA_VALID_MASK
            | STORED_TERRAIN_OUTPUT_MASK
            | Semantic.CAMERA_NORMAL.bit();
    public static final long COMPLETE_VALID_MASK =
        TERRAIN_VALID_MASK | Semantic.MOTION.bit();
    public static final long KNOWN_OUTPUT_MASK = COMPLETE_VALID_MASK;

    public enum OutputFormat {
        RGBA8_UNORM(4, true),
        D32_FLOAT(4, true),
        RG16_FLOAT(4, true),
        RGBA16_SNORM(8, true),
        R32_UINT(4, true),
        DERIVED_CAMERA_NORMAL(0, false),
        FRAME_METADATA(0, false);

        private final int bytesPerPixel;
        private final boolean stored;

        OutputFormat(int bytesPerPixel, boolean stored) {
            this.bytesPerPixel = bytesPerPixel;
            this.stored = stored;
        }

        public int bytesPerPixel() {
            return this.bytesPerPixel;
        }

        public boolean stored() {
            return this.stored;
        }
    }

    /**
     * Color is the post-lighting, fogged LDR target supplied by Minecraft.
     * No numeric exposure is fabricated when that renderer exposes only its
     * automatic tonemapping contract.
     */
    public enum ExposureMode {
        AUTO_TONEMAPPED_LDR
    }

    /**
     * Motion is current-to-previous in output-pixel units with top-left
     * origin. X grows right and Y grows down. Both matrices are unjittered;
     * the current and previous jitter values remain separate metadata.
     */
    public enum MotionConvention {
        CURRENT_TO_PREVIOUS_UNJITTERED_OUTPUT_PIXELS_TOP_LEFT
    }

    /**
     * Stored normals are normalized world-space XYZ. W is exactly one for a
     * valid terrain pixel and zero for cleared background.
     */
    public enum NormalConvention {
        NORMALIZED_WORLD_XYZ_VALIDITY_W
    }

    public static final MotionConvention MOTION_CONVENTION =
        MotionConvention
            .CURRENT_TO_PREVIOUS_UNJITTERED_OUTPUT_PIXELS_TOP_LEFT;
    public static final NormalConvention NORMAL_CONVENTION =
        NormalConvention.NORMALIZED_WORLD_XYZ_VALIDITY_W;

    public enum Semantic {
        COLOR(
            TerrainMeshProducerABI.OUTPUT_COLOR,
            OutputFormat.RGBA8_UNORM
        ),
        DEPTH(
            TerrainMeshProducerABI.OUTPUT_DEPTH,
            OutputFormat.D32_FLOAT
        ),
        MOTION(
            TerrainMeshProducerABI.OUTPUT_MOTION,
            OutputFormat.RG16_FLOAT
        ),
        WORLD_NORMAL(
            TerrainMeshProducerABI.OUTPUT_NORMAL,
            OutputFormat.RGBA16_SNORM
        ),
        SURFACE(
            TerrainMeshProducerABI.OUTPUT_MATERIAL,
            OutputFormat.R32_UINT
        ),
        EXPOSURE_JITTER_METADATA(
            TerrainMeshProducerABI.OUTPUT_EXPOSURE_JITTER,
            OutputFormat.FRAME_METADATA
        ),
        GENERATION_RESET_METADATA(
            TerrainMeshProducerABI.OUTPUT_GENERATION_RESET,
            OutputFormat.FRAME_METADATA
        ),
        CAMERA_NORMAL(
            1L << 7,
            OutputFormat.DERIVED_CAMERA_NORMAL
        );

        private final long bit;
        private final OutputFormat format;

        Semantic(long bit, OutputFormat format) {
            this.bit = bit;
            this.format = format;
        }

        public long bit() {
            return this.bit;
        }

        public OutputFormat format() {
            return this.format;
        }
    }

    public enum Phase {
        IDLE,
        BEGUN,
        TERRAIN_DRAWN,
        MOTION_RESOLVED,
        PUBLISHED,
        RETIRED,
        CLOSED
    }

    public enum ResetReason {
        FIRST_FRAME,
        TELEPORT,
        RESIZE,
        RESOURCE_RELOAD,
        WORLD_CHANGE,
        RENDERER_CHANGE,
        DEVICE_CHANGE,
        SCENE_GENERATION_CHANGE,
        HISTORY_INVALIDATED
    }

    public record ResourceGenerations(
        long device,
        long renderer,
        long world,
        long resources,
        long scene
    ) {
        public ResourceGenerations {
            requirePositive(device, "deviceGeneration");
            requirePositive(renderer, "rendererGeneration");
            requirePositive(world, "worldGeneration");
            requirePositive(resources, "resourceGeneration");
            requirePositive(scene, "sceneGeneration");
        }
    }

    public record Generations(
        long frame,
        long device,
        long renderer,
        long world,
        long resources,
        long scene
    ) {
        public Generations {
            requirePositive(frame, "frameGeneration");
            requirePositive(device, "deviceGeneration");
            requirePositive(renderer, "rendererGeneration");
            requirePositive(world, "worldGeneration");
            requirePositive(resources, "resourceGeneration");
            requirePositive(scene, "sceneGeneration");
        }

        public ResourceGenerations resourcesOnly() {
            return new ResourceGenerations(
                this.device,
                this.renderer,
                this.world,
                this.resources,
                this.scene
            );
        }
    }

    public record OutputExtent(int width, int height) {
        public OutputExtent {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                    "output extent must be positive"
                );
            }
        }
    }

    /**
     * Exact Minecraft Globals decomposition used by native terrain shaders.
     * {@code block*} is {@code floor(cameraPosition)} and {@code offset*} is
     * {@code floor(cameraPosition) - cameraPosition}, matching
     * {@code GlobalSettingsUniform.CameraOffset}. Keeping both values in
     * current and previous state makes camera-block origin crossings
     * reconstructable without losing float precision.
     */
    public record CameraOrigin(
        int blockX,
        int blockY,
        int blockZ,
        float offsetX,
        float offsetY,
        float offsetZ
    ) {
        public CameraOrigin {
            if (
                !Float.isFinite(offsetX)
                    || !Float.isFinite(offsetY)
                    || !Float.isFinite(offsetZ)
                    || !isMojangCameraOffset(offsetX)
                    || !isMojangCameraOffset(offsetY)
                    || !isMojangCameraOffset(offsetZ)
            ) {
                throw new IllegalArgumentException(
                    "camera offset must match floor(camera)-camera"
                );
            }
        }

        private static boolean isMojangCameraOffset(float value) {
            return value >= -1.0F && value <= 0.0F;
        }
    }

    public record Jitter(float xPixels, float yPixels) {
        public static final Jitter NONE = new Jitter(0.0F, 0.0F);

        public Jitter {
            if (!Float.isFinite(xPixels) || !Float.isFinite(yPixels)) {
                throw new IllegalArgumentException(
                    "frame jitter must be finite"
                );
            }
        }
    }

    public record Exposure(
        ExposureMode mode
    ) {
        public Exposure {
            Objects.requireNonNull(mode, "exposureMode");
        }

        public static Exposure autoTonemappedLdr() {
            return new Exposure(
                ExposureMode.AUTO_TONEMAPPED_LDR
            );
        }
    }

    public record ResetEpoch(
        long value,
        Set<ResetReason> reasons
    ) {
        public ResetEpoch {
            requirePositive(value, "resetEpoch");
            Objects.requireNonNull(reasons, "resetReasons");
            EnumSet<ResetReason> copy = reasons.isEmpty()
                ? EnumSet.noneOf(ResetReason.class)
                : EnumSet.copyOf(reasons);
            reasons = Collections.unmodifiableSet(copy);
        }

        public static ResetEpoch initial(long value) {
            return new ResetEpoch(
                value,
                EnumSet.of(ResetReason.FIRST_FRAME)
            );
        }

        public static ResetEpoch unchanged(long value) {
            return new ResetEpoch(
                value,
                EnumSet.noneOf(ResetReason.class)
            );
        }

        public boolean requested() {
            return !this.reasons.isEmpty();
        }
    }

    /**
     * Immutable copy of a matrix supplied by the renderer.
     */
    public static final class MatrixSnapshot {
        private final Matrix4f value;

        private MatrixSnapshot(Matrix4fc source, String name) {
            Objects.requireNonNull(source, name);
            requireFinite(source, name);
            this.value = new Matrix4f(source);
        }

        public Matrix4f copy() {
            return new Matrix4f(this.value);
        }

        private Matrix4fc value() {
            return this.value;
        }
    }

    /**
     * Current or previously published unjittered camera state.
     */
    public static final class CameraState {
        private final MatrixSnapshot unjitteredProjection;
        private final MatrixSnapshot unjitteredView;
        private final MatrixSnapshot unjitteredViewProjection;
        private final MatrixSnapshot inverseUnjitteredViewProjection;
        private final Matrix3f worldToCameraNormal;
        private final Jitter jitter;
        private final OutputExtent outputExtent;
        private final CameraOrigin origin;

        private CameraState(
            MatrixSnapshot unjitteredProjection,
            MatrixSnapshot unjitteredView,
            MatrixSnapshot unjitteredViewProjection,
            MatrixSnapshot inverseUnjitteredViewProjection,
            Matrix3f worldToCameraNormal,
            Jitter jitter,
            OutputExtent outputExtent,
            CameraOrigin origin
        ) {
            this.unjitteredProjection = unjitteredProjection;
            this.unjitteredView = unjitteredView;
            this.unjitteredViewProjection =
                unjitteredViewProjection;
            this.inverseUnjitteredViewProjection =
                inverseUnjitteredViewProjection;
            this.worldToCameraNormal =
                new Matrix3f(worldToCameraNormal);
            this.jitter = jitter;
            this.outputExtent = outputExtent;
            this.origin = origin;
        }

        public static CameraState capture(
            Matrix4fc unjitteredProjection,
            Matrix4fc unjitteredView,
            Jitter jitter,
            OutputExtent outputExtent,
            CameraOrigin origin
        ) {
            Objects.requireNonNull(jitter, "jitter");
            Objects.requireNonNull(outputExtent, "outputExtent");
            Objects.requireNonNull(origin, "cameraOrigin");
            MatrixSnapshot projection = new MatrixSnapshot(
                unjitteredProjection,
                "unjitteredProjection"
            );
            MatrixSnapshot view = new MatrixSnapshot(
                unjitteredView,
                "unjitteredView"
            );
            Matrix4f viewProjection =
                new Matrix4f(projection.value())
                    .mul(view.value());
            float viewProjectionDeterminant =
                viewProjection.determinant();
            if (
                !Float.isFinite(viewProjectionDeterminant)
                    || Math.abs(viewProjectionDeterminant) < 1.0E-20F
            ) {
                throw new IllegalArgumentException(
                    "unjittered view-projection is singular"
                );
            }
            Matrix4f inverseViewProjection =
                new Matrix4f(viewProjection).invert();
            requireFinite(
                inverseViewProjection,
                "inverseUnjitteredViewProjection"
            );

            Matrix3f normalTransform =
                new Matrix3f(view.value());
            float normalDeterminant = normalTransform.determinant();
            if (
                !Float.isFinite(normalDeterminant)
                    || Math.abs(normalDeterminant) < 1.0E-20F
            ) {
                throw new IllegalArgumentException(
                    "unjittered view has no normal transform"
                );
            }
            normalTransform.invert().transpose();
            requireFinite(
                normalTransform,
                "worldToCameraNormal"
            );
            return new CameraState(
                projection,
                view,
                new MatrixSnapshot(
                    viewProjection,
                    "unjitteredViewProjection"
                ),
                new MatrixSnapshot(
                    inverseViewProjection,
                    "inverseUnjitteredViewProjection"
                ),
                normalTransform,
                jitter,
                outputExtent,
                origin
            );
        }

        public MatrixSnapshot unjitteredProjection() {
            return this.unjitteredProjection;
        }

        public MatrixSnapshot unjitteredView() {
            return this.unjitteredView;
        }

        public MatrixSnapshot unjitteredViewProjection() {
            return this.unjitteredViewProjection;
        }

        public MatrixSnapshot inverseUnjitteredViewProjection() {
            return this.inverseUnjitteredViewProjection;
        }

        public Jitter jitter() {
            return this.jitter;
        }

        public OutputExtent outputExtent() {
            return this.outputExtent;
        }

        public CameraOrigin origin() {
            return this.origin;
        }

        public Vector3f deriveCameraNormal(
            Vector3fc worldNormal,
            Vector3f destination
        ) {
            Objects.requireNonNull(worldNormal, "worldNormal");
            Objects.requireNonNull(destination, "destination");
            float x = worldNormal.x();
            float y = worldNormal.y();
            float z = worldNormal.z();
            if (
                !Float.isFinite(x)
                    || !Float.isFinite(y)
                    || !Float.isFinite(z)
            ) {
                throw new IllegalArgumentException(
                    "world normal must be finite"
                );
            }
            double inputLength = Math.sqrt(
                (double)x * x + (double)y * y + (double)z * z
            );
            if (!Double.isFinite(inputLength) || inputLength == 0.0D) {
                throw new IllegalArgumentException(
                    "world normal must be nonzero"
                );
            }
            float inverseInputLength =
                (float)(1.0D / inputLength);
            x *= inverseInputLength;
            y *= inverseInputLength;
            z *= inverseInputLength;
            float cameraX =
                this.worldToCameraNormal.m00() * x
                    + this.worldToCameraNormal.m10() * y
                    + this.worldToCameraNormal.m20() * z;
            float cameraY =
                this.worldToCameraNormal.m01() * x
                    + this.worldToCameraNormal.m11() * y
                    + this.worldToCameraNormal.m21() * z;
            float cameraZ =
                this.worldToCameraNormal.m02() * x
                    + this.worldToCameraNormal.m12() * y
                    + this.worldToCameraNormal.m22() * z;
            double outputLength = Math.sqrt(
                (double)cameraX * cameraX
                    + (double)cameraY * cameraY
                    + (double)cameraZ * cameraZ
            );
            if (
                !Double.isFinite(outputLength)
                    || outputLength == 0.0D
            ) {
                throw new IllegalArgumentException(
                    "camera normal transform is degenerate"
                );
            }
            float inverseOutputLength =
                (float)(1.0D / outputLength);
            return destination.set(
                cameraX * inverseOutputLength,
                cameraY * inverseOutputLength,
                cameraZ * inverseOutputLength
            );
        }
    }

    public static final class FrameToken {
        private final Generations generations;

        private FrameToken(Generations generations) {
            this.generations = generations;
        }

        public Generations generations() {
            return this.generations;
        }
    }

    public record FrameView(
        Generations generations,
        CameraState current,
        CameraState previous,
        boolean previousPublished,
        boolean historyUsable,
        Exposure exposure,
        ResetEpoch reset,
        long validMask,
        Phase phase
    ) {
        public FrameView {
            Objects.requireNonNull(generations, "generations");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(exposure, "exposure");
            Objects.requireNonNull(reset, "reset");
            Objects.requireNonNull(phase, "phase");
            requireKnownMask(validMask);
        }
    }

    public record Retirement(
        Generations generations,
        Phase terminalPhase,
        boolean published,
        long validMask
    ) {
        public Retirement {
            Objects.requireNonNull(generations, "generations");
            Objects.requireNonNull(terminalPhase, "terminalPhase");
            requireKnownMask(validMask);
        }
    }

    private record ActiveFrame(
        Generations generations,
        CameraState current,
        CameraState previous,
        boolean previousPublished,
        boolean historyUsable,
        Exposure exposure,
        ResetEpoch reset
    ) {
    }

    private final ResourceGenerations ownerGenerations;
    private Phase phase = Phase.IDLE;
    private long lastFrameGeneration;
    private long highestResetEpoch;
    private long committedResetEpoch;
    private CameraState committedCamera;
    private FrameToken activeToken;
    private ActiveFrame activeFrame;
    private long validMask;

    public NativeTerrainFrameOutputAbi(
        ResourceGenerations ownerGenerations
    ) {
        this.ownerGenerations = Objects.requireNonNull(
            ownerGenerations,
            "ownerGenerations"
        );
    }

    public synchronized Phase phase() {
        return this.phase;
    }

    public ResourceGenerations ownerGenerations() {
        return this.ownerGenerations;
    }

    public synchronized FrameToken beginFrame(
        Generations generations,
        CameraState current,
        Exposure exposure,
        ResetEpoch reset
    ) {
        requireOpen();
        if (this.phase != Phase.IDLE && this.phase != Phase.RETIRED) {
            throw new IllegalStateException(
                "previous native terrain frame is not retired"
            );
        }
        Objects.requireNonNull(generations, "generations");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(exposure, "exposure");
        Objects.requireNonNull(reset, "reset");
        if (
            !generations.resourcesOnly().equals(
                this.ownerGenerations
            )
        ) {
            throw new IllegalArgumentException(
                "frame generations are stale for output owner"
            );
        }
        if (generations.frame() <= this.lastFrameGeneration) {
            throw new IllegalArgumentException(
                "frame generation must increase"
            );
        }
        validateReset(reset);
        if (
            this.committedCamera != null
                && !current.outputExtent().equals(
                    this.committedCamera.outputExtent()
                )
                && !reset.reasons().contains(ResetReason.RESIZE)
        ) {
            throw new IllegalArgumentException(
                "output extent change requires a resize reset"
            );
        }

        CameraState previous = this.committedCamera == null
            ? current
            : this.committedCamera;
        boolean previousPublished = this.committedCamera != null;
        boolean historyUsable =
            previousPublished
                && !reset.requested()
                && reset.value() == this.committedResetEpoch;
        this.lastFrameGeneration = generations.frame();
        this.highestResetEpoch = Math.max(
            this.highestResetEpoch,
            reset.value()
        );
        this.activeToken = new FrameToken(generations);
        this.activeFrame = new ActiveFrame(
            generations,
            current,
            previous,
            previousPublished,
            historyUsable,
            exposure,
            reset
        );
        this.validMask = FRAME_METADATA_VALID_MASK;
        this.phase = Phase.BEGUN;
        return this.activeToken;
    }

    public synchronized FrameView frame(FrameToken token) {
        requireToken(token);
        return view();
    }

    public synchronized FrameView markTerrainDrawn(
        FrameToken token,
        long storedOutputMask
    ) {
        requirePhase(token, Phase.BEGUN);
        if (storedOutputMask != STORED_TERRAIN_OUTPUT_MASK) {
            throw new IllegalArgumentException(
                "stored terrain output mask is incomplete or unknown"
            );
        }
        this.validMask |=
            STORED_TERRAIN_OUTPUT_MASK
                | Semantic.CAMERA_NORMAL.bit();
        this.phase = Phase.TERRAIN_DRAWN;
        return view();
    }

    public synchronized FrameView markMotionResolved(
        FrameToken token
    ) {
        requirePhase(token, Phase.TERRAIN_DRAWN);
        this.validMask |= Semantic.MOTION.bit();
        this.phase = Phase.MOTION_RESOLVED;
        return view();
    }

    /**
     * Atomically publishes the frame and only then commits temporal history.
     */
    public synchronized FrameView publish(FrameToken token) {
        requirePhase(token, Phase.MOTION_RESOLVED);
        if (this.validMask != COMPLETE_VALID_MASK) {
            throw new IllegalStateException(
                "native terrain outputs are incomplete"
            );
        }
        this.committedCamera = this.activeFrame.current();
        this.committedResetEpoch =
            this.activeFrame.reset().value();
        this.phase = Phase.PUBLISHED;
        return view();
    }

    /**
     * Retires either a published frame or an unpublished failed frame.
     */
    public synchronized Retirement retire(FrameToken token) {
        requireToken(token);
        if (
            this.phase != Phase.BEGUN
                && this.phase != Phase.TERRAIN_DRAWN
                && this.phase != Phase.MOTION_RESOLVED
                && this.phase != Phase.PUBLISHED
        ) {
            throw new IllegalStateException(
                "native terrain frame cannot be retired"
            );
        }
        boolean published = this.phase == Phase.PUBLISHED;
        Retirement result = new Retirement(
            this.activeFrame.generations(),
            Phase.RETIRED,
            published,
            this.validMask
        );
        this.activeToken = null;
        this.activeFrame = null;
        this.validMask = 0L;
        this.phase = Phase.RETIRED;
        return result;
    }

    @Override
    public synchronized void close() {
        if (this.phase == Phase.CLOSED) {
            return;
        }
        if (this.phase != Phase.IDLE && this.phase != Phase.RETIRED) {
            throw new IllegalStateException(
                "active frame must retire before output ABI closes"
            );
        }
        this.activeToken = null;
        this.activeFrame = null;
        this.committedCamera = null;
        this.validMask = 0L;
        this.phase = Phase.CLOSED;
    }

    private void validateReset(ResetEpoch reset) {
        if (reset.value() < this.highestResetEpoch) {
            throw new IllegalArgumentException(
                "reset epoch is stale"
            );
        }
        if (this.committedCamera == null) {
            if (!reset.requested()) {
                throw new IllegalArgumentException(
                    "first published history requires a reset reason"
                );
            }
            return;
        }
        if (reset.value() < this.committedResetEpoch) {
            throw new IllegalArgumentException(
                "reset epoch predates published history"
            );
        }
        if (
            reset.value() == this.committedResetEpoch
                && reset.requested()
        ) {
            throw new IllegalArgumentException(
                "unchanged reset epoch cannot add reset reasons"
            );
        }
        if (
            reset.value() > this.committedResetEpoch
                && !reset.requested()
        ) {
            throw new IllegalArgumentException(
                "advanced reset epoch requires a typed reason"
            );
        }
    }

    private FrameView view() {
        return new FrameView(
            this.activeFrame.generations(),
            this.activeFrame.current(),
            this.activeFrame.previous(),
            this.activeFrame.previousPublished(),
            this.activeFrame.historyUsable(),
            this.activeFrame.exposure(),
            this.activeFrame.reset(),
            this.validMask,
            this.phase
        );
    }

    private void requirePhase(FrameToken token, Phase expected) {
        requireToken(token);
        if (this.phase != expected) {
            throw new IllegalStateException(
                "expected frame phase "
                    + expected
                    + " but was "
                    + this.phase
            );
        }
    }

    private void requireToken(FrameToken token) {
        requireOpen();
        if (token == null || token != this.activeToken) {
            throw new IllegalArgumentException(
                "frame token is stale or belongs to another owner"
            );
        }
    }

    private void requireOpen() {
        if (this.phase == Phase.CLOSED) {
            throw new IllegalStateException(
                "native terrain frame output ABI is closed"
            );
        }
    }

    private static void requireKnownMask(long mask) {
        if ((mask & ~KNOWN_OUTPUT_MASK) != 0L) {
            throw new IllegalArgumentException(
                "frame output mask has unknown bits"
            );
        }
    }

    private static void requireFinite(
        Matrix4fc matrix,
        String name
    ) {
        if (
            !Float.isFinite(matrix.m00())
                || !Float.isFinite(matrix.m01())
                || !Float.isFinite(matrix.m02())
                || !Float.isFinite(matrix.m03())
                || !Float.isFinite(matrix.m10())
                || !Float.isFinite(matrix.m11())
                || !Float.isFinite(matrix.m12())
                || !Float.isFinite(matrix.m13())
                || !Float.isFinite(matrix.m20())
                || !Float.isFinite(matrix.m21())
                || !Float.isFinite(matrix.m22())
                || !Float.isFinite(matrix.m23())
                || !Float.isFinite(matrix.m30())
                || !Float.isFinite(matrix.m31())
                || !Float.isFinite(matrix.m32())
                || !Float.isFinite(matrix.m33())
        ) {
            throw new IllegalArgumentException(
                name + " must be finite"
            );
        }
    }

    private static void requireFinite(
        Matrix3f matrix,
        String name
    ) {
        if (
            !Float.isFinite(matrix.m00())
                || !Float.isFinite(matrix.m01())
                || !Float.isFinite(matrix.m02())
                || !Float.isFinite(matrix.m10())
                || !Float.isFinite(matrix.m11())
                || !Float.isFinite(matrix.m12())
                || !Float.isFinite(matrix.m20())
                || !Float.isFinite(matrix.m21())
                || !Float.isFinite(matrix.m22())
        ) {
            throw new IllegalArgumentException(
                name + " must be finite"
            );
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(
                name + " must be positive"
            );
        }
    }
}
