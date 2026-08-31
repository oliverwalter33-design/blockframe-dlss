package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.EngineConfig;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Phase;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.SelectedBackend;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.WorldResourceCreationPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainDeviceCapabilityNegotiator.Attestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainDeviceCapabilityNegotiator.GenerationToken;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelManager;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process owner that joins pre-device capability publication to the one
 * restart-bound terrain backend choice.
 *
 * <p>Foundation V1 deliberately supplies an incomplete production census.
 * Consequently {@code native-experimental} fails closed to Mojang until the
 * Minecraft/NeoForge census adapter, native backend factory and GPU owner are
 * connected. Unit tests exercise eligible selections directly on
 * {@link NativeTerrainBackendSelector}; this production coordinator never
 * manufactures eligibility.</p>
 */
public final class NativeTerrainBackendFoundation {
    private static final Logger LOGGER = LoggerFactory.getLogger(
        "blockframe-native-terrain-foundation"
    );
    private static final long FOUNDATION_RAM_ADMISSION_BYTES =
        64L * 1024L * 1024L;
    private static final long FOUNDATION_VRAM_ADMISSION_BYTES =
        256L * 1024L * 1024L;

    private static final NativeTerrainDeviceCapabilityNegotiator
        .GenerationRegistry CAPABILITY_GENERATIONS =
            new NativeTerrainDeviceCapabilityNegotiator
                .GenerationRegistry();
    private static final NativeTerrainBackendSelector SELECTOR =
        new NativeTerrainBackendSelector();

    private static long lastCapabilityGeneration;
    private static GenerationToken capabilityToken;
    private static Attestation capabilityAttestation;
    private static NativeTerrainBackendSelector.Configuration
        restartConfiguration;
    private static long lastResourceGeneration;
    private static MinecraftTerrainAssetCensusAdapter.Report censusReport;
    private static String censusUnavailableReason =
        "model-reload-census-not-captured";
    private static boolean closed;

    private NativeTerrainBackendFoundation() {
    }

    /**
     * Starts a fresh owner generation before any physical-device feature
     * mutation. Default Mojang configuration performs no native probe.
     */
    public static synchronized void beginVulkanDeviceCreation() {
        if (closed) {
            return;
        }
        closeCapabilityGeneration();
        lastCapabilityGeneration = incrementSaturated(
            lastCapabilityGeneration
        );
        boolean requested = restartConfiguration()
                .nativeExperimentalRequested()
            && !BlockframeRuntime.safeStartActive();
        capabilityToken = CAPABILITY_GENERATIONS.begin(
            lastCapabilityGeneration,
            requested,
            true
        );
        capabilityAttestation = CAPABILITY_GENERATIONS.snapshot();
    }

    /**
     * Runs at the existing injection immediately before Mojang's private
     * vkCreateDevice helper. Every error leaves Mojang's normal inputs usable.
     */
    public static synchronized Attestation configureDeviceCapabilities(
        VulkanPhysicalDevice physicalDevice,
        Set<String> enabledExtensions,
        Set<VulkanFeature> enabledFeatures
    ) {
        if (closed || capabilityToken == null) {
            return capabilityAttestation;
        }
        try {
            NativeTerrainDeviceCapabilityNegotiator.Probe probe =
                capabilityAttestation.requested()
                    ? NativeTerrainDeviceCapabilityNegotiator.probe(
                        physicalDevice
                    )
                    : NativeTerrainDeviceCapabilityNegotiator.Probe
                        .unavailable("disabled-by-configuration");
            capabilityAttestation =
                CAPABILITY_GENERATIONS.configure(
                    capabilityToken,
                    probe,
                    enabledExtensions,
                    enabledFeatures
                );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            LOGGER.warn(
                "Native terrain capability negotiation failed closed",
                error
            );
        }
        return capabilityAttestation;
    }

    /**
     * Seals the restart-bound backend at the first real terrain world-resource
     * boundary. This is deliberately later than {@code LevelRenderer}
     * construction: the constructor creates no section resources, while the
     * post-reload registry/model census is not yet available during Minecraft
     * construction.
     */
    public static synchronized NativeTerrainBackendSelector.Selection
    selectAtFirstWorldResourceBoundary() {
        requireOpen();
        if (SELECTOR.phase() != Phase.UNSELECTED) {
            return SELECTOR.selection();
        }
        NativeTerrainBackendSelector.Configuration configuration =
            restartConfiguration();
        NativeTerrainBackendSelector.Preflight preflight =
            configuration.nativeExperimentalRequested()
                ? incompleteProductionPreflight()
                : null;
        NativeTerrainBackendSelector.Selection selection =
            SELECTOR.selectBeforeWorldResources(
                configuration,
                preflight
            );
        LOGGER.info(
            "Native terrain backend selection: requested={} selected={} "
                + "rejections={}",
            configuration.suppliedValue(),
            selection.backend(),
            selection.rejectionReasons()
        );
        return selection;
    }

    /**
     * Enters Mojang's reference-only factory. A native selection is never
     * demoted here and can never reach Mojang's constructor through this
     * method. The complete native owner must use
     * {@link ExclusiveNativeWorldResourceFactory} after all typed preflight
     * gates are true.
     */
    public static synchronized WorldResourceCreationPermit
    beginReferenceWorldResourceCreation() {
        requireOpen();
        if (SELECTOR.phase() == Phase.UNSELECTED) {
            try {
                selectAtFirstWorldResourceBoundary();
            } catch (
                RuntimeException
                    | LinkageError
                    | OutOfMemoryError error
            ) {
                if (SELECTOR.phase() == Phase.UNSELECTED) {
                    NativeTerrainBackendSelector.Configuration
                        failedConfiguration =
                            restartConfiguration == null
                                ? NativeTerrainBackendSelector
                                    .Configuration.parse(
                                        NativeTerrainBackendSelector
                                            .MOJANG_CONFIGURATION_VALUE
                                    )
                                : restartConfiguration;
                    SELECTOR.failClosedBeforeWorldResources(
                        failedConfiguration,
                        "preflight-evaluation-failed:"
                            + error.getClass().getSimpleName()
                    );
                    LOGGER.warn(
                        "Native terrain preflight failed before ownership; "
                            + "Mojang remains the only world backend",
                        error
                    );
                } else if (
                    SELECTOR.selection().nativeBackendSelected()
                ) {
                    if (error instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw (Error)error;
                }
            }
        }
        if (SELECTOR.phase() == Phase.SELECTED) {
            if (SELECTOR.selection().nativeBackendSelected()) {
                throw new IllegalStateException(
                    "native world ownership requires the exclusive "
                        + "native factory; Mojang construction is forbidden"
                );
            }
            WorldResourceCreationPermit permit =
                SELECTOR.beginWorldResourceCreation();
            if (permit.backend() != SelectedBackend.MOJANG_REFERENCE) {
                throw new IllegalStateException(
                    "reference factory received a native permit"
                );
            }
            return permit;
        }
        if (SELECTOR.phase() == Phase.WORLD_RESOURCES_ACTIVE) {
            if (SELECTOR.selection().nativeBackendSelected()) {
                throw new IllegalStateException(
                    "active native world reconfiguration requires its "
                        + "exclusive owner; Mojang rebuild is forbidden"
                );
            }
            return null;
        }
        throw new IllegalStateException(
            "terrain backend creation is unavailable in "
                + SELECTOR.phase()
        );
    }

    public static synchronized void completeWorldResourceCreation(
        WorldResourceCreationPermit permit
    ) {
        if (permit != null) {
            SELECTOR.completeWorldResourceCreation(permit);
        }
    }

    public static synchronized void abortReferenceWorldResourceCreation(
        WorldResourceCreationPermit permit
    ) {
        if (permit != null) {
            SELECTOR.abortReferenceWorldResourceCreation(permit);
        }
    }

    /**
     * Called only after LevelRenderer has returned from its own close.
     */
    public static synchronized void worldRendererClosed() {
        if (
            !closed
                && SELECTOR.phase()
                    == Phase.WORLD_RESOURCES_ACTIVE
                && !SELECTOR.selection().nativeBackendSelected()
        ) {
            SELECTOR.completeWorldResourceRetirement();
        }
    }

    public static synchronized void deviceClosing() {
        if (!closed) {
            closeCapabilityGeneration();
        }
    }

    /**
     * Invalidates all model/material-derived native contracts before a reload
     * can replace them. The native renderer is not active in Foundation B, so
     * no Mojang resource is affected.
     */
    public static synchronized void resourceReloadBeginning() {
        if (closed || !censusRequested()) {
            return;
        }
        censusReport = null;
        censusUnavailableReason = "resource-reload-in-progress";
    }

    /**
     * Captures the real registry/model census after ModelManager.apply and
     * NeoForge's ModelEvent.BakingCompleted hook have both returned.
     */
    public static synchronized void modelsReloaded(
        ModelManager modelManager
    ) {
        if (
            closed
                || !censusRequested()
                || BlockframeRuntime.safeStartActive()
        ) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            censusReport = null;
            censusUnavailableReason =
                "model-reload-completed-off-client-thread";
            LOGGER.warn(
                "Native terrain model census rejected: {}",
                censusUnavailableReason
            );
            return;
        }
        long generation = incrementSaturated(lastResourceGeneration);
        try {
            MinecraftTerrainAssetCensusAdapter.Report captured =
                MinecraftTerrainAssetCensusAdapter.capture(
                    Objects.requireNonNull(modelManager, "modelManager"),
                    minecraft.getBlockColors(),
                    generation
                );
            lastResourceGeneration = generation;
            censusReport = captured;
            censusUnavailableReason = "";
            LOGGER.info(
                "Native terrain census generation={} captureNanos={} "
                    + "blockStates={} fluids={} observations={} reasons={}",
                generation,
                captured.captureNanos(),
                captured.blockStateCount(),
                captured.fluidStateCount(),
                captured.observations().size(),
                captured.byReason()
            );
            NativeTerrainFoundationBSmoke.modelsReady(
                modelManager,
                captured
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            censusReport = null;
            censusUnavailableReason =
                "census-failed:" + error.getClass().getSimpleName();
            LOGGER.warn(
                "Native terrain registry/model census failed closed",
                error
            );
        }
    }

    public static synchronized Optional<
        MinecraftTerrainAssetCensusAdapter.Report
    > censusReport() {
        return Optional.ofNullable(censusReport);
    }

    public static synchronized String censusUnavailableReason() {
        return censusUnavailableReason;
    }

    public static synchronized void vulkanDeviceConnected(
        VulkanDevice device
    ) {
        if (!closed) {
            NativeTerrainFoundationBSmoke.deviceConnected(device);
        }
    }

    public static synchronized void frameEnded() {
        if (!closed) {
            NativeTerrainFoundationBSmoke.frameEnded();
        }
    }

    public static synchronized boolean deviceClosing(
        VulkanDevice device
    ) {
        if (!closed) {
            closeCapabilityGeneration();
        }
        return NativeTerrainFoundationBSmoke.deviceClosing(device);
    }

    /**
     * Final client close. A still-active native backend is never silently
     * declared clean.
     */
    public static synchronized boolean closeClient() {
        if (closed) {
            return true;
        }
        closeCapabilityGeneration();
        if (
            SELECTOR.phase() == Phase.WORLD_RESOURCES_ACTIVE
                && SELECTOR.selection().nativeBackendSelected()
        ) {
            return false;
        }
        if (SELECTOR.phase() == Phase.WORLD_RESOURCES_ACTIVE) {
            SELECTOR.completeWorldResourceRetirement();
        }
        try {
            SELECTOR.close();
            CAPABILITY_GENERATIONS.close();
            closed = true;
            return true;
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            LOGGER.warn(
                "Native terrain foundation could not close cleanly",
                error
            );
            return false;
        }
    }

    public static synchronized Attestation capabilityAttestation() {
        return capabilityAttestation;
    }

    public static synchronized NativeTerrainBackendSelector.Selection
    selection() {
        return SELECTOR.selection();
    }

    private static NativeTerrainBackendSelector.Preflight
    incompleteProductionPreflight() {
        Attestation capability = capabilityAttestation;
        long deviceGeneration = capability == null
            ? Math.max(1L, lastCapabilityGeneration)
            : capability.deviceGeneration();
        if (capability == null) {
            capability =
                NativeTerrainDeviceCapabilityNegotiator.select(
                    deviceGeneration,
                    true,
                    false,
                    NativeTerrainDeviceCapabilityNegotiator.Probe
                        .unavailable("vulkan-negotiation-not-published")
                );
        }
        MinecraftTerrainAssetCensusAdapter.Report report =
            censusReport;
        long resourceGeneration = report == null
            ? Math.max(1L, lastResourceGeneration)
            : report.resourceGeneration();
        var census = report == null
            ? NativeTerrainAssetCensus.capture(
                resourceGeneration,
                false,
                List.of()
            )
            : report.census();
        var censusAttestation = census.attest(
            EnumSet.of(Category.SOLID, Category.CUTOUT)
        );
        var budgets = BlockframeRuntime.memoryBudgets();
        return new NativeTerrainBackendSelector.Preflight(
            new GenerationStamp(
                deviceGeneration,
                1L,
                1L,
                resourceGeneration,
                1L,
                1L
            ),
            capability.vulkan()
                ? NativeTerrainBackendSelector.RendererApi.VULKAN
                : NativeTerrainBackendSelector.RendererApi.UNKNOWN,
            capability,
            censusAttestation,
            new NativeTerrainBackendSelector.FormatAttestation(
                TerrainMeshProducerABI.VERSION,
                true,
                true,
                true,
                false,
                "minecraft-shader-and-material-adapter-not-connected"
            ),
            new NativeTerrainBackendSelector.BudgetAttestation(
                FOUNDATION_RAM_ADMISSION_BYTES,
                budgets.availableBytes(MemoryKind.RAM),
                FOUNDATION_VRAM_ADMISSION_BYTES,
                budgets.availableBytes(MemoryKind.VRAM),
                true
            ),
            new NativeTerrainBackendSelector
                .ExclusiveWorldFactoryAttestation(
                    false,
                    false,
                    false,
                    false,
                    false,
                    "exclusive-world-routing-not-connected"
                ),
            new NativeTerrainBackendSelector.FrameOutputAttestation(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                "native-mrt-motion-normal-surface-history-and-typed-"
                    + "reference-capture-not-connected"
            ),
            new NativeTerrainBackendSelector
                .ControlledFixtureAttestation(
                    false,
                    false,
                    false,
                    false,
                    "controlled-native-fixture-not-connected"
                ),
            BlockframeRuntime.safeStartActive(),
            false,
            ""
        );
    }

    private static boolean nativeRequested() {
        return restartConfiguration().nativeExperimentalRequested();
    }

    private static boolean censusRequested() {
        Attestation capability = capabilityAttestation;
        return (
            nativeRequested()
                || NativeTerrainFoundationBSmoke.enabled()
        )
            && capability != null
            && capability.vulkan()
            && !capability.closed();
    }

    private static void closeCapabilityGeneration() {
        GenerationToken token = capabilityToken;
        if (token == null) {
            return;
        }
        try {
            capabilityAttestation =
                CAPABILITY_GENERATIONS.closeGeneration(token);
        } finally {
            capabilityToken = null;
        }
    }

    private static void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                "native terrain foundation is closed"
            );
        }
    }

    private static NativeTerrainBackendSelector.Configuration
    restartConfiguration() {
        if (restartConfiguration == null) {
            EngineConfig.Settings settings =
                BlockframeRuntime.engine().config().settings();
            restartConfiguration =
                NativeTerrainBackendSelector.Configuration.parse(
                    settings.terrainBackend()
                );
        }
        return restartConfiguration;
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }
}
