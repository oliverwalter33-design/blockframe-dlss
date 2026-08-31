package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.ActivationAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainDeviceCapabilityNegotiator.Attestation;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One restart-bound backend decision made before terrain world resources.
 *
 * <p>The selector never constructs either renderer. It seals the requested
 * backend for the process, while each world/resource generation receives an
 * independently validated creation permit. No failed construction may begin
 * Mojang until native cleanup is completely attested. Automatic selection is
 * intentionally absent from Foundation V1.</p>
 */
public final class NativeTerrainBackendSelector {
    public static final String CONFIGURATION_KEY = "terrainBackend";
    public static final String MOJANG_CONFIGURATION_VALUE = "mojang";
    public static final String NATIVE_CONFIGURATION_VALUE =
        "native-experimental";

    public enum RequestedBackend {
        MOJANG_REFERENCE,
        BLOCKFRAME_NATIVE_EXPERIMENTAL
    }

    public enum SelectedBackend {
        MOJANG_REFERENCE,
        BLOCKFRAME_NATIVE_EXPERIMENTAL
    }

    public enum RendererApi {
        VULKAN,
        OPENGL,
        UNKNOWN
    }

    public enum Phase {
        UNSELECTED,
        SELECTED,
        WORLD_REVALIDATION_REQUIRED,
        WORLD_RESOURCES_CREATING,
        WORLD_RESOURCES_ACTIVE,
        WORLD_RESOURCES_QUARANTINED,
        QUARANTINED_CLEANUP_REQUIRED,
        QUARANTINED,
        CLOSED
    }

    public enum RejectionReason {
        CONFIGURATION_SELECTS_MOJANG,
        CONFIGURATION_INVALID,
        PREFLIGHT_EVALUATION_FAILED,
        NOT_VULKAN,
        CAPABILITY_REQUEST_NOT_PUBLISHED,
        CAPABILITY_DEVICE_GENERATION_MISMATCH,
        BASELINE_CAPABILITY_UNAVAILABLE,
        TERRAIN_ABI_VERSION_UNSUPPORTED,
        VERTEX_FORMAT_UNSUPPORTED,
        INDEX_FORMAT_UNSUPPORTED,
        MATERIAL_FORMAT_UNSUPPORTED,
        SHADER_ABI_UNSUPPORTED,
        CENSUS_RESOURCE_GENERATION_MISMATCH,
        ASSET_CENSUS_INCOMPLETE,
        ASSET_OR_RENDER_TYPE_UNSUPPORTED,
        RAM_BUDGET_UNAVAILABLE,
        VRAM_BUDGET_UNAVAILABLE,
        EXCLUSIVE_WORLD_FACTORY_UNAVAILABLE,
        FRAME_OUTPUT_ABI_UNAVAILABLE,
        CONTROLLED_FIXTURE_UNAVAILABLE,
        SAFE_START_ACTIVE,
        NATIVE_BACKEND_QUARANTINED,
        NATIVE_CREATION_FAILED
    }

    public enum CreationFailureAction {
        REBUILD_MOJANG_BEFORE_WORLD_ENTRY,
        RETRY_NATIVE_CLEANUP_BEFORE_ANY_BACKEND_CREATION
    }

    /**
     * Missing configuration is the production Mojang default. Unknown text
     * is preserved as invalid evidence but also resolves fail-closed to
     * Mojang.
     */
    public record Configuration(
        RequestedBackend requestedBackend,
        String suppliedValue,
        boolean recognized,
        boolean restartRequired
    ) {
        public Configuration {
            Objects.requireNonNull(
                requestedBackend,
                "requestedBackend"
            );
            suppliedValue = Objects.requireNonNull(
                suppliedValue,
                "suppliedValue"
            );
            if (!restartRequired) {
                throw new IllegalArgumentException(
                    "terrain backend selection must be restart-bound"
                );
            }
            if (recognized) {
                String expected = requestedBackend
                        == RequestedBackend.MOJANG_REFERENCE
                    ? MOJANG_CONFIGURATION_VALUE
                    : NATIVE_CONFIGURATION_VALUE;
                if (!expected.equals(suppliedValue)) {
                    throw new IllegalArgumentException(
                        "recognized backend configuration is inconsistent"
                    );
                }
            } else if (
                requestedBackend
                    != RequestedBackend.MOJANG_REFERENCE
                    || MOJANG_CONFIGURATION_VALUE.equals(suppliedValue)
                    || NATIVE_CONFIGURATION_VALUE.equals(suppliedValue)
            ) {
                throw new IllegalArgumentException(
                    "invalid configuration must fail closed to Mojang"
                );
            }
        }

        public static Configuration parse(String suppliedValue) {
            if (
                suppliedValue == null
                    || suppliedValue.isBlank()
                    || MOJANG_CONFIGURATION_VALUE.equals(suppliedValue)
            ) {
                return new Configuration(
                    RequestedBackend.MOJANG_REFERENCE,
                    suppliedValue == null || suppliedValue.isBlank()
                        ? MOJANG_CONFIGURATION_VALUE
                        : suppliedValue,
                    true,
                    true
                );
            }
            if (NATIVE_CONFIGURATION_VALUE.equals(suppliedValue)) {
                return new Configuration(
                    RequestedBackend
                        .BLOCKFRAME_NATIVE_EXPERIMENTAL,
                    suppliedValue,
                    true,
                    true
                );
            }
            return new Configuration(
                RequestedBackend.MOJANG_REFERENCE,
                suppliedValue,
                false,
                true
            );
        }

        public boolean nativeExperimentalRequested() {
            return this.recognized
                && this.requestedBackend
                    == RequestedBackend
                        .BLOCKFRAME_NATIVE_EXPERIMENTAL;
        }
    }

    /**
     * Exact renderer ABI families required by the first native backend.
     * Detailed per-asset compatibility remains owned by the census.
     */
    public record FormatAttestation(
        int terrainAbiVersion,
        boolean vertexFormatSupported,
        boolean indexFormatSupported,
        boolean materialFormatSupported,
        boolean shaderAbiSupported,
        String unavailableReason
    ) {
        public FormatAttestation {
            if (terrainAbiVersion <= 0) {
                throw new IllegalArgumentException(
                    "terrainAbiVersion must be positive"
                );
            }
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
            boolean ready = terrainAbiVersion
                    == TerrainMeshProducerABI.VERSION
                && vertexFormatSupported
                && indexFormatSupported
                && materialFormatSupported
                && shaderAbiSupported;
            if (ready != unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                    "format readiness and reason disagree"
                );
            }
        }

        public boolean ready() {
            return this.terrainAbiVersion == TerrainMeshProducerABI.VERSION
                && this.vertexFormatSupported
                && this.indexFormatSupported
                && this.materialFormatSupported
                && this.shaderAbiSupported;
        }
    }

    /**
     * Admission budget, not a lease. Actual CPU/GPU owners acquire their
     * bounded allocations only after a native creation permit exists.
     */
    public record BudgetAttestation(
        long requiredRamBytes,
        long availableRamBytes,
        long requiredVramBytes,
        long availableVramBytes,
        boolean accountingReliable
    ) {
        public BudgetAttestation {
            requirePositive(requiredRamBytes, "requiredRamBytes");
            requireNonNegative(availableRamBytes, "availableRamBytes");
            requirePositive(requiredVramBytes, "requiredVramBytes");
            requireNonNegative(availableVramBytes, "availableVramBytes");
        }

        public boolean ramReady() {
            return this.accountingReliable
                && this.availableRamBytes >= this.requiredRamBytes;
        }

        public boolean vramReady() {
            return this.accountingReliable
                && this.availableVramBytes >= this.requiredVramBytes;
        }
    }

    public record ExclusiveWorldFactoryAttestation(
        boolean ownerConstructorReady,
        boolean initialPublishBarrierReady,
        boolean levelRendererExclusiveRoutingReady,
        boolean levelExtractorExclusiveRoutingReady,
        boolean postPublishPauseReady,
        String unavailableReason
    ) {
        public ExclusiveWorldFactoryAttestation {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
            boolean ready = ownerConstructorReady
                && initialPublishBarrierReady
                && levelRendererExclusiveRoutingReady
                && levelExtractorExclusiveRoutingReady
                && postPublishPauseReady;
            if (ready != unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                    "exclusive world factory readiness and reason disagree"
                );
            }
        }

        public boolean ready() {
            return this.ownerConstructorReady
                && this.initialPublishBarrierReady
                && this.levelRendererExclusiveRoutingReady
                && this.levelExtractorExclusiveRoutingReady
                && this.postPublishPauseReady;
        }
    }

    public record FrameOutputAttestation(
        boolean storedColorReady,
        boolean storedDepthReady,
        boolean storedMotionReady,
        boolean storedNormalReady,
        boolean storedSurfaceReady,
        boolean historyContractReady,
        boolean resetContractReady,
        boolean nativeTypedCaptureReady,
        boolean mojangTypedCaptureReady,
        String unavailableReason
    ) {
        public FrameOutputAttestation {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
            boolean ready = storedColorReady
                && storedDepthReady
                && storedMotionReady
                && storedNormalReady
                && storedSurfaceReady
                && historyContractReady
                && resetContractReady
                && nativeTypedCaptureReady
                && mojangTypedCaptureReady;
            if (ready != unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                    "frame output readiness and reason disagree"
                );
            }
        }

        public boolean ready() {
            return this.storedColorReady
                && this.storedDepthReady
                && this.storedMotionReady
                && this.storedNormalReady
                && this.storedSurfaceReady
                && this.historyContractReady
                && this.resetContractReady
                && this.nativeTypedCaptureReady
                && this.mojangTypedCaptureReady;
        }
    }

    public record ControlledFixtureAttestation(
        boolean deterministicFixtureReady,
        boolean nativePathReady,
        boolean mojangReferenceReady,
        boolean lifecycleReady,
        String unavailableReason
    ) {
        public ControlledFixtureAttestation {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
            boolean ready = deterministicFixtureReady
                && nativePathReady
                && mojangReferenceReady
                && lifecycleReady;
            if (ready != unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                    "controlled fixture readiness and reason disagree"
                );
            }
        }

        public boolean ready() {
            return this.deterministicFixtureReady
                && this.nativePathReady
                && this.mojangReferenceReady
                && this.lifecycleReady;
        }
    }

    /**
     * Immutable inputs captured before WorldRenderer, compiler or terrain GPU
     * owners exist.
     */
    public record Preflight(
        GenerationStamp plannedGenerations,
        RendererApi rendererApi,
        Attestation capabilities,
        ActivationAttestation assetCensus,
        FormatAttestation formats,
        BudgetAttestation budget,
        ExclusiveWorldFactoryAttestation exclusiveWorldFactory,
        FrameOutputAttestation frameOutput,
        ControlledFixtureAttestation controlledFixture,
        boolean safeStart,
        boolean quarantined,
        String quarantineReason
    ) {
        public Preflight {
            plannedGenerations = Objects.requireNonNull(
                plannedGenerations,
                "plannedGenerations"
            );
            rendererApi = Objects.requireNonNull(
                rendererApi,
                "rendererApi"
            );
            capabilities = Objects.requireNonNull(
                capabilities,
                "capabilities"
            );
            assetCensus = Objects.requireNonNull(
                assetCensus,
                "assetCensus"
            );
            formats = Objects.requireNonNull(formats, "formats");
            budget = Objects.requireNonNull(budget, "budget");
            exclusiveWorldFactory = Objects.requireNonNull(
                exclusiveWorldFactory,
                "exclusiveWorldFactory"
            );
            frameOutput = Objects.requireNonNull(
                frameOutput,
                "frameOutput"
            );
            controlledFixture = Objects.requireNonNull(
                controlledFixture,
                "controlledFixture"
            );
            quarantineReason = Objects.requireNonNull(
                quarantineReason,
                "quarantineReason"
            );
            if (quarantined == quarantineReason.isEmpty()) {
                throw new IllegalArgumentException(
                    "quarantine state and reason disagree"
                );
            }
        }
    }

    public record Selection(
        Configuration configuration,
        SelectedBackend backend,
        GenerationStamp plannedGenerations,
        boolean preflightEvaluated,
        List<RejectionReason> rejectionReasons
    ) {
        public Selection {
            configuration = Objects.requireNonNull(
                configuration,
                "configuration"
            );
            backend = Objects.requireNonNull(backend, "backend");
            rejectionReasons = List.copyOf(
                Objects.requireNonNull(
                    rejectionReasons,
                    "rejectionReasons"
                )
            );
            if (
                backend
                    == SelectedBackend
                        .BLOCKFRAME_NATIVE_EXPERIMENTAL
                    && (
                        !configuration.nativeExperimentalRequested()
                            || !preflightEvaluated
                            || plannedGenerations == null
                            || !rejectionReasons.isEmpty()
                    )
            ) {
                throw new IllegalArgumentException(
                    "native selection lacks a complete clean preflight"
                );
            }
            if (
                backend == SelectedBackend.MOJANG_REFERENCE
                    && rejectionReasons.isEmpty()
            ) {
                throw new IllegalArgumentException(
                    "Mojang selection must retain its typed reason"
                );
            }
        }

        public boolean nativeBackendSelected() {
            return this.backend
                == SelectedBackend.BLOCKFRAME_NATIVE_EXPERIMENTAL;
        }
    }

    /**
     * Proof required before a failed native creation may select Mojang. Counts
     * cover only objects created under the corresponding creation permit.
     */
    public record CreationCleanupAttestation(
        long deviceGeneration,
        long outstandingCpuBytes,
        long outstandingGpuBytes,
        int outstandingResources,
        boolean complete
    ) {
        public CreationCleanupAttestation {
            requirePositive(deviceGeneration, "deviceGeneration");
            requireNonNegative(
                outstandingCpuBytes,
                "outstandingCpuBytes"
            );
            requireNonNegative(
                outstandingGpuBytes,
                "outstandingGpuBytes"
            );
            if (outstandingResources < 0) {
                throw new IllegalArgumentException(
                    "outstandingResources must not be negative"
                );
            }
            boolean empty = outstandingCpuBytes == 0L
                && outstandingGpuBytes == 0L
                && outstandingResources == 0;
            if (complete != empty) {
                throw new IllegalArgumentException(
                    "cleanup completion and outstanding ownership disagree"
                );
            }
        }
    }

    public static final class WorldResourceCreationPermit {
        private final NativeTerrainBackendSelector owner;
        private final long epoch;
        private final Selection selection;

        private WorldResourceCreationPermit(
            NativeTerrainBackendSelector owner,
            long epoch,
            Selection selection
        ) {
            this.owner = owner;
            this.epoch = epoch;
            this.selection = selection;
        }

        public SelectedBackend backend() {
            return this.selection.backend();
        }

        public GenerationStamp plannedGenerations() {
            return this.selection.plannedGenerations();
        }
    }

    private Phase phase = Phase.UNSELECTED;
    private Configuration configuration;
    private Selection selection;
    private long epoch = 1L;
    private String quarantineReason = "";

    public synchronized Phase phase() {
        return this.phase;
    }

    public synchronized Selection selection() {
        if (this.selection == null) {
            throw new IllegalStateException(
                "terrain backend has not been selected"
            );
        }
        return this.selection;
    }

    public synchronized String quarantineReason() {
        return this.quarantineReason;
    }

    /**
     * Seals the one process-lifetime choice. A Mojang configuration does not
     * require or evaluate a native preflight.
     */
    public synchronized Selection selectBeforeWorldResources(
        Configuration configuration,
        Preflight nativePreflight
    ) {
        requirePhase(Phase.UNSELECTED);
        this.configuration = Objects.requireNonNull(
            configuration,
            "configuration"
        );
        this.selection = selectForPreflight(
            this.configuration,
            nativePreflight
        );
        this.phase = Phase.SELECTED;
        return this.selection;
    }

    /**
     * Seals Mojang when native preflight evaluation itself failed before any
     * creation permit or owner existed.
     */
    public synchronized Selection failClosedBeforeWorldResources(
        Configuration configuration,
        String reason
    ) {
        requirePhase(Phase.UNSELECTED);
        String suppliedReason = Objects.requireNonNull(reason, "reason");
        if (suppliedReason.isBlank()) {
            throw new IllegalArgumentException(
                "preflight failure requires a reason"
            );
        }
        this.configuration = Objects.requireNonNull(
            configuration,
            "configuration"
        );
        this.selection = mojangSelection(
            this.configuration,
            null,
            false,
            List.of(RejectionReason.PREFLIGHT_EVALUATION_FAILED)
        );
        this.phase = Phase.SELECTED;
        return this.selection;
    }

    /**
     * Re-evaluates only world-lifetime evidence. The restart-bound requested
     * backend remains immutable.
     */
    public synchronized Selection revalidateBeforeWorldResources(
        Preflight nativePreflight
    ) {
        requirePhase(Phase.WORLD_REVALIDATION_REQUIRED);
        if (
            this.configuration.nativeExperimentalRequested()
                && this.selection.plannedGenerations().equals(
                    Objects.requireNonNull(
                        nativePreflight,
                        "nativePreflight"
                    ).plannedGenerations()
                )
        ) {
            throw new IllegalArgumentException(
                "world revalidation requires fresh generations"
            );
        }
        Selection revalidated = selectForPreflight(
            this.configuration,
            nativePreflight
        );
        this.epoch = Math.addExact(this.epoch, 1L);
        this.selection = revalidated;
        this.phase = Phase.SELECTED;
        return this.selection;
    }

    public synchronized WorldResourceCreationPermit
    beginWorldResourceCreation() {
        requirePhase(Phase.SELECTED);
        this.phase = Phase.WORLD_RESOURCES_CREATING;
        return new WorldResourceCreationPermit(
            this,
            this.epoch,
            this.selection
        );
    }

    public synchronized void completeWorldResourceCreation(
        WorldResourceCreationPermit permit
    ) {
        requirePhase(Phase.WORLD_RESOURCES_CREATING);
        requireCreationPermit(permit);
        this.phase = Phase.WORLD_RESOURCES_ACTIVE;
    }

    /**
     * Returns to the sealed choice when Mojang's own construction throws.
     * This is bookkeeping only and never reclassifies a Mojang failure as a
     * native failure or creates another backend.
     */
    public synchronized void abortReferenceWorldResourceCreation(
        WorldResourceCreationPermit permit
    ) {
        requirePhase(Phase.WORLD_RESOURCES_CREATING);
        requireCreationPermit(permit);
        if (permit.backend() != SelectedBackend.MOJANG_REFERENCE) {
            throw new IllegalStateException(
                "native creation requires a cleanup attestation"
            );
        }
        this.phase = Phase.SELECTED;
    }

    /**
     * Confirms that the selected backend's world resources are fully retired.
     * The restart-bound backend choice remains sealed for a later world or
     * reload; this method cannot promote a rejected native backend.
     */
    public synchronized void completeWorldResourceRetirement() {
        requirePhase(Phase.WORLD_RESOURCES_ACTIVE);
        this.phase = Phase.SELECTED;
        this.epoch = Math.addExact(this.epoch, 1L);
    }

    /**
     * Retires one owner and requires a fresh world/resource preflight before
     * another creation permit can be issued.
     */
    public synchronized void
    completeWorldResourceRetirementForRevalidation(
        WorldResourceCreationPermit permit
    ) {
        requirePhase(Phase.WORLD_RESOURCES_ACTIVE);
        requireCreationPermit(permit);
        if (
            permit.backend()
                != SelectedBackend
                    .BLOCKFRAME_NATIVE_EXPERIMENTAL
        ) {
            throw new IllegalStateException(
                "only native retirement may request revalidation"
            );
        }
        this.phase = Phase.WORLD_REVALIDATION_REQUIRED;
        this.epoch = Math.addExact(this.epoch, 1L);
    }

    /**
     * A failed native construction may choose Mojang only after every partial
     * native object is gone. Otherwise rendering remains stopped for cleanup.
     */
    public synchronized CreationFailureAction abortWorldResourceCreation(
        WorldResourceCreationPermit permit,
        CreationCleanupAttestation cleanup
    ) {
        requirePhase(Phase.WORLD_RESOURCES_CREATING);
        requireCreationPermit(permit);
        validateNativeCleanup(permit, cleanup);
        if (cleanup.complete()) {
            demoteNativeCreationToMojang();
            return CreationFailureAction
                .REBUILD_MOJANG_BEFORE_WORLD_ENTRY;
        }
        this.phase = Phase.QUARANTINED_CLEANUP_REQUIRED;
        return CreationFailureAction
            .RETRY_NATIVE_CLEANUP_BEFORE_ANY_BACKEND_CREATION;
    }

    /**
     * Completes cleanup that was still outstanding before publication. Only a
     * complete attestation may authorize construction of the Mojang backend.
     */
    public synchronized CreationFailureAction
    retryWorldResourceCreationCleanup(
        WorldResourceCreationPermit permit,
        CreationCleanupAttestation cleanup
    ) {
        requirePhase(Phase.QUARANTINED_CLEANUP_REQUIRED);
        requireCreationPermit(permit);
        validateNativeCleanup(permit, cleanup);
        if (!cleanup.complete()) {
            return CreationFailureAction
                .RETRY_NATIVE_CLEANUP_BEFORE_ANY_BACKEND_CREATION;
        }
        demoteNativeCreationToMojang();
        return CreationFailureAction
            .REBUILD_MOJANG_BEFORE_WORLD_ENTRY;
    }

    /**
     * Makes a post-publication failure visible to every selector consumer.
     * It never authorizes an in-process fallback.
     */
    public synchronized void quarantinePublishedWorldResources(
        WorldResourceCreationPermit permit,
        String reason
    ) {
        if (
            this.phase != Phase.WORLD_RESOURCES_CREATING
                && this.phase != Phase.WORLD_RESOURCES_ACTIVE
        ) {
            throw new IllegalStateException(
                "published quarantine is unavailable in " + this.phase
            );
        }
        requireCreationPermit(permit);
        if (
            permit.backend()
                != SelectedBackend
                    .BLOCKFRAME_NATIVE_EXPERIMENTAL
        ) {
            throw new IllegalStateException(
                "Mojang resources cannot enter native quarantine"
            );
        }
        String suppliedReason = Objects.requireNonNull(reason, "reason");
        if (suppliedReason.isBlank()) {
            throw new IllegalArgumentException(
                "published quarantine requires a reason"
            );
        }
        this.quarantineReason = suppliedReason;
        this.phase = Phase.WORLD_RESOURCES_QUARANTINED;
    }

    /**
     * Confirms physical cleanup after a post-publication failure. Native
     * remains quarantined for the rest of this selector lifetime.
     */
    public synchronized void
    completeQuarantinedWorldResourceRetirement(
        WorldResourceCreationPermit permit,
        CreationCleanupAttestation cleanup
    ) {
        requirePhase(Phase.WORLD_RESOURCES_QUARANTINED);
        requireCreationPermit(permit);
        validateNativeCleanup(permit, cleanup);
        if (!cleanup.complete()) {
            throw new IllegalStateException(
                "quarantined resources still require cleanup"
            );
        }
        this.phase = Phase.QUARANTINED;
        this.epoch = Math.addExact(this.epoch, 1L);
    }

    public synchronized void close() {
        if (
            this.phase == Phase.WORLD_RESOURCES_CREATING
                || this.phase == Phase.WORLD_RESOURCES_ACTIVE
                || this.phase
                    == Phase.WORLD_RESOURCES_QUARANTINED
                || this.phase
                    == Phase.QUARANTINED_CLEANUP_REQUIRED
        ) {
            throw new IllegalStateException(
                "backend resources require owner cleanup"
            );
        }
        if (this.phase == Phase.CLOSED) {
            return;
        }
        this.phase = Phase.CLOSED;
        this.epoch = Math.addExact(this.epoch, 1L);
    }

    private Selection selectForPreflight(
        Configuration selectedConfiguration,
        Preflight nativePreflight
    ) {
        if (!selectedConfiguration.recognized()) {
            return mojangSelection(
                selectedConfiguration,
                null,
                false,
                List.of(RejectionReason.CONFIGURATION_INVALID)
            );
        }
        if (!selectedConfiguration.nativeExperimentalRequested()) {
            return mojangSelection(
                selectedConfiguration,
                null,
                false,
                List.of(
                    RejectionReason.CONFIGURATION_SELECTS_MOJANG
                )
            );
        }
        Preflight preflight = Objects.requireNonNull(
            nativePreflight,
            "nativePreflight"
        );
        List<RejectionReason> rejected = evaluate(preflight);
        return rejected.isEmpty()
            ? new Selection(
                selectedConfiguration,
                SelectedBackend.BLOCKFRAME_NATIVE_EXPERIMENTAL,
                preflight.plannedGenerations(),
                true,
                List.of()
            )
            : mojangSelection(
                selectedConfiguration,
                preflight.plannedGenerations(),
                true,
                rejected
            );
    }

    private void validateNativeCleanup(
        WorldResourceCreationPermit permit,
        CreationCleanupAttestation cleanup
    ) {
        Objects.requireNonNull(cleanup, "cleanup");
        if (
            permit.backend()
                != SelectedBackend
                    .BLOCKFRAME_NATIVE_EXPERIMENTAL
        ) {
            throw new IllegalStateException(
                "Mojang construction failure is not a native fallback"
            );
        }
        if (
            cleanup.deviceGeneration()
                != permit.plannedGenerations().device()
        ) {
            throw new IllegalArgumentException(
                "cleanup proof belongs to another device generation"
            );
        }
    }

    private void demoteNativeCreationToMojang() {
        this.selection = mojangSelection(
            this.selection.configuration(),
            this.selection.plannedGenerations(),
            true,
            List.of(RejectionReason.NATIVE_CREATION_FAILED)
        );
        this.epoch = Math.addExact(this.epoch, 1L);
        this.phase = Phase.SELECTED;
    }

    private static Selection mojangSelection(
        Configuration configuration,
        GenerationStamp plannedGenerations,
        boolean preflightEvaluated,
        List<RejectionReason> reasons
    ) {
        return new Selection(
            configuration,
            SelectedBackend.MOJANG_REFERENCE,
            plannedGenerations,
            preflightEvaluated,
            reasons
        );
    }

    private static List<RejectionReason> evaluate(
        Preflight preflight
    ) {
        List<RejectionReason> rejected = new ArrayList<>();
        GenerationStamp generations =
            preflight.plannedGenerations();
        Attestation capabilities = preflight.capabilities();
        ActivationAttestation census = preflight.assetCensus();
        FormatAttestation formats = preflight.formats();

        if (
            preflight.rendererApi() != RendererApi.VULKAN
                || !capabilities.vulkan()
        ) {
            rejected.add(RejectionReason.NOT_VULKAN);
        }
        if (
            !capabilities.requested()
                || !capabilities.requirementsPublished()
        ) {
            rejected.add(
                RejectionReason.CAPABILITY_REQUEST_NOT_PUBLISHED
            );
        }
        if (
            capabilities.deviceGeneration()
                != generations.device()
        ) {
            rejected.add(
                RejectionReason
                    .CAPABILITY_DEVICE_GENERATION_MISMATCH
            );
        }
        if (!capabilities.baselineReady()) {
            rejected.add(
                RejectionReason.BASELINE_CAPABILITY_UNAVAILABLE
            );
        }
        if (
            formats.terrainAbiVersion()
                != TerrainMeshProducerABI.VERSION
        ) {
            rejected.add(
                RejectionReason.TERRAIN_ABI_VERSION_UNSUPPORTED
            );
        }
        if (!formats.vertexFormatSupported()) {
            rejected.add(
                RejectionReason.VERTEX_FORMAT_UNSUPPORTED
            );
        }
        if (!formats.indexFormatSupported()) {
            rejected.add(
                RejectionReason.INDEX_FORMAT_UNSUPPORTED
            );
        }
        if (!formats.materialFormatSupported()) {
            rejected.add(
                RejectionReason.MATERIAL_FORMAT_UNSUPPORTED
            );
        }
        if (!formats.shaderAbiSupported()) {
            rejected.add(
                RejectionReason.SHADER_ABI_UNSUPPORTED
            );
        }
        if (
            census.resourceGeneration()
                != generations.resources()
        ) {
            rejected.add(
                RejectionReason
                    .CENSUS_RESOURCE_GENERATION_MISMATCH
            );
        }
        if (!census.censusComplete()) {
            rejected.add(
                RejectionReason.ASSET_CENSUS_INCOMPLETE
            );
        }
        if (!census.nativeBackendEligible()) {
            rejected.add(
                RejectionReason
                    .ASSET_OR_RENDER_TYPE_UNSUPPORTED
            );
        }
        if (!preflight.budget().ramReady()) {
            rejected.add(
                RejectionReason.RAM_BUDGET_UNAVAILABLE
            );
        }
        if (!preflight.budget().vramReady()) {
            rejected.add(
                RejectionReason.VRAM_BUDGET_UNAVAILABLE
            );
        }
        if (!preflight.exclusiveWorldFactory().ready()) {
            rejected.add(
                RejectionReason.EXCLUSIVE_WORLD_FACTORY_UNAVAILABLE
            );
        }
        if (!preflight.frameOutput().ready()) {
            rejected.add(
                RejectionReason.FRAME_OUTPUT_ABI_UNAVAILABLE
            );
        }
        if (!preflight.controlledFixture().ready()) {
            rejected.add(
                RejectionReason.CONTROLLED_FIXTURE_UNAVAILABLE
            );
        }
        if (preflight.safeStart()) {
            rejected.add(RejectionReason.SAFE_START_ACTIVE);
        }
        if (preflight.quarantined()) {
            rejected.add(
                RejectionReason.NATIVE_BACKEND_QUARANTINED
            );
        }
        return List.copyOf(rejected);
    }

    private void requireCreationPermit(
        WorldResourceCreationPermit permit
    ) {
        Objects.requireNonNull(permit, "permit");
        if (
            permit.owner != this
                || permit.epoch != this.epoch
                || permit.selection != this.selection
        ) {
            throw new IllegalArgumentException(
                "stale or foreign backend creation permit"
            );
        }
    }

    private void requirePhase(Phase expected) {
        if (this.phase != expected) {
            throw new IllegalStateException(
                "expected " + expected + " but was " + this.phase
            );
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                name + " must not be negative"
            );
        }
    }
}
