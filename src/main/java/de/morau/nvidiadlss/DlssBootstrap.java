package de.morau.nvidiadlss;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import de.morau.blockframe.boot.NativeRuntimeArtifacts;
import de.morau.blockframe.cache.PersistentArtifactCache;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.VulkanRuntimeInfo;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendFoundation;
import de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator;
import de.morau.blockframe.vulkan.VulkanDeviceCapabilityProbe;
import de.morau.blockframe.vulkan.VulkanDeviceFaultHookHealth;
import de.morau.blockframe.vulkan.VulkanDeviceFaultNegotiator;
import de.morau.nvidiadlss.nativebridge.NativeStreamline;
import de.morau.nvidiadlss.nativebridge.StreamlineFeatureRequirements;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

public final class DlssBootstrap {
    private static volatile boolean libraryLoaded;
    private static volatile boolean bootstrapped;
    private static volatile boolean nativeShutdownUncertain;
    private static volatile Path nativeDirectory;
    private static volatile boolean connected;
    private static volatile VulkanDevice vulkanBackend;
    private static final ThreadLocal<Boolean> VULKAN_DEVICE_CREATION =
        ThreadLocal.withInitial(() -> false);
    private static volatile boolean instanceRequirementsReady;
    private static volatile boolean deviceRequirementsReady;
    private static volatile String capabilityReason =
        "Vulkan DLSS capabilities not evaluated";
    private static volatile DlssVulkanCapabilityNegotiator.InstanceSelection
        instanceSelection;
    private static volatile DlssVulkanCapabilityNegotiator.DeviceSelection
        deviceSelection;
    private static volatile StreamlineFeatureRequirements
        streamlineRequirements;

    private DlssBootstrap() {}

    public static void beginVulkanDeviceCreation() {
        VULKAN_DEVICE_CREATION.set(true);
        instanceRequirementsReady = false;
        deviceRequirementsReady = false;
        instanceSelection = null;
        deviceSelection = null;
        if (!bootstrapped) {
            streamlineRequirements = null;
        }
        capabilityReason = "Vulkan DLSS capabilities are being evaluated";
        try {
            NativeTerrainBackendFoundation.beginVulkanDeviceCreation();
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            NvidiaDlssMod.LOGGER.warn(
                "Native terrain device generation was disabled fail-closed",
                error
            );
        }
        boolean requested = BlockframeRuntime.featureEnabled(
            de.morau.blockframe.core.state.FeatureId.DEVICE_FAULT
        );
        try {
            BlockframeRuntime.engine().updateDeviceFaultNegotiation(
                requested,
                false,
                false,
                false,
                requested
                    ? "negotiation-hook-not-reached"
                    : "disabled-by-configuration"
            );
        } catch (Throwable ignored) {
            // Optional diagnostics never control Mojang device creation.
        }
    }

    public static void endVulkanDeviceCreation() {
        VULKAN_DEVICE_CREATION.remove();
    }

    public static boolean isVulkanDeviceCreation() {
        return VULKAN_DEVICE_CREATION.get();
    }

    public static void configureInstanceExtensions(
        Set<String> availableExtensions,
        Set<String> enabledExtensions
    ) {
        if (!isVulkanDeviceCreation()) {
            return;
        }
        if (!BlockframeRuntime.streamlineBootstrapAllowed()) {
            capabilityReason = BlockframeRuntime.safeStartActive()
                ? "DLSS disabled by one-shot Safe Start"
                : "DLSS disabled at process bootstrap: mode=off";
            DlssStatus.unavailable(capabilityReason);
            return;
        }
        if (!ensureNativeLoaded()) {
            return;
        }
        StreamlineFeatureRequirements requirements = streamlineRequirements;
        if (requirements == null) {
            rejectUnconnectedStreamline(
                "DLSS disabled: Streamline requirements snapshot is missing"
            );
            return;
        }
        DlssVulkanCapabilityNegotiator.InstanceSelection selection =
            DlssVulkanCapabilityNegotiator.selectInstanceExtensions(
                availableExtensions,
                requirements.union(),
                DlssVulkanCapabilityNegotiator.VULKAN_API_1_2
            );
        instanceSelection = selection;
        if (!selection.complete()) {
            rejectUnconnectedStreamline(
                "DLSS disabled: missing Vulkan instance extensions "
                    + selection.missingExtensions()
            );
            return;
        }

        enabledExtensions.addAll(selection.enabledExtensions());
        instanceRequirementsReady = true;
    }

    public static void configureDeviceCapabilities(
        VulkanPhysicalDevice physicalDevice,
        Set<String> enabledExtensions,
        Set<VulkanFeature> enabledFeatures
    ) {
        if (!isVulkanDeviceCreation()) {
            return;
        }
        if (BlockframeRuntime.safeStartActive()) {
            capabilityReason =
                "DLSS disabled by one-shot Safe Start";
            DlssStatus.unavailable(capabilityReason);
            BlockframeRuntime.engine()
                .updateBufferDeviceAddressState(false, false);
            return;
        }

        NativeTerrainBackendFoundation.configureDeviceCapabilities(
            physicalDevice,
            enabledExtensions,
            enabledFeatures
        );
        StreamlineFeatureRequirements requirements = streamlineRequirements;
        Set<String> requiredDeviceExtensions = requirements == null
            ? Set.of()
            : requirements.union().vulkanDeviceExtensions();
        VulkanDeviceCapabilityProbe.DeviceAvailability availability =
            VulkanDeviceCapabilityProbe.query(
                physicalDevice,
                requiredDeviceExtensions
            );
        if (DeveloperDiagnostics.enabled()) {
            configureOptionalDeviceFault(
                availability,
                enabledExtensions,
                enabledFeatures
            );
        }
        BlockframeRuntime.engine().updateVulkanRuntimeInfo(
            new VulkanRuntimeInfo(
                true,
                availability.apiVersion(),
                availability.vendorId(),
                availability.deviceId(),
                availability.driverId(),
                availability.driverVersion(),
                availability.driverName(),
                availability.driverInfo(),
                availability.extensions(),
                availability.features().descriptorIndexing(),
                availability.features().bufferDeviceAddress(),
                availability.memoryBudgetExtensionAdvertised()
            )
        );
        if (!BlockframeRuntime.streamlineBootstrapAllowed()) {
            BlockframeRuntime.engine()
                .updateBufferDeviceAddressState(
                    availability.features().bufferDeviceAddress(),
                    false
                );
            return;
        }
        if (!instanceRequirementsReady) {
            BlockframeRuntime.engine()
                .updateBufferDeviceAddressState(
                    availability.features().bufferDeviceAddress(),
                    false
                );
            return;
        }
        if (requirements == null) {
            BlockframeRuntime.engine()
                .updateBufferDeviceAddressState(
                    availability.features().bufferDeviceAddress(),
                    false
                );
            rejectUnconnectedStreamline(
                "DLSS disabled: Streamline requirements snapshot is missing"
            );
            return;
        }
        DlssVulkanCapabilityNegotiator.DeviceSelection selection =
            availability.negotiate(
                requirements.union(),
                availability.apiVersion()
            );
        deviceSelection = selection;
        DlssVulkanCapabilityNegotiator.CapabilityReport report =
            DlssVulkanCapabilityNegotiator.report(instanceSelection, selection);
        boolean bdaAvailable =
            availability.features().bufferDeviceAddress();
        if (!report.safeToEnableStaticRequirements()) {
            capabilityReason = report.reason();
            DlssStatus.unavailable(capabilityReason);
            BlockframeRuntime.engine()
                .updateBufferDeviceAddressState(bdaAvailable, false);
            rejectUnconnectedStreamline(capabilityReason);
            return;
        }

        enabledExtensions.addAll(selection.enabledExtensions());
        addVulkan12Feature(
            enabledFeatures,
            selection.enabledFeatures(),
            DlssVulkanCapabilityNegotiator.TIMELINE_SEMAPHORE_FEATURE,
            VkPhysicalDeviceVulkan12Features.TIMELINESEMAPHORE
        );
        addVulkan12Feature(
            enabledFeatures,
            selection.enabledFeatures(),
            DlssVulkanCapabilityNegotiator.DESCRIPTOR_INDEXING_FEATURE,
            VkPhysicalDeviceVulkan12Features.DESCRIPTORINDEXING
        );
        addVulkan12Feature(
            enabledFeatures,
            selection.enabledFeatures(),
            DlssVulkanCapabilityNegotiator.BUFFER_DEVICE_ADDRESS_FEATURE,
            VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS
        );
        addVulkan12Feature(
            enabledFeatures,
            selection.enabledFeatures(),
            DlssVulkanCapabilityNegotiator.SHADER_FLOAT16_FEATURE,
            VkPhysicalDeviceVulkan12Features.SHADERFLOAT16
        );
        deviceRequirementsReady = true;
        capabilityReason = report.reason();
        BlockframeRuntime.engine()
            .updateBufferDeviceAddressState(
                bdaAvailable,
                selection
                    .enabledFeatures()
                    .contains(
                        DlssVulkanCapabilityNegotiator
                            .BUFFER_DEVICE_ADDRESS_FEATURE
                    )
            );
    }

    private static void addVulkan12Feature(
        Set<VulkanFeature> enabledFeatures,
        Set<String> selectedFeatures,
        String name,
        int offset
    ) {
        if (!selectedFeatures.contains(name)) {
            return;
        }
        enabledFeatures.add(
            new VulkanFeature(
                VulkanBackend.VK12_FEATURES_STRUCT,
                name,
                offset
            )
        );
    }

    private static void rejectUnconnectedStreamline(String reason) {
        capabilityReason = reason;
        DlssStatus.unavailable(reason);
        if (!shutdownUnconnectedBootstrapAndReport()) {
            capabilityReason =
                reason + "; native Streamline cleanup was not confirmed";
            DlssStatus.unavailable(capabilityReason);
        }
    }

    private static void configureOptionalDeviceFault(
        VulkanDeviceCapabilityProbe.DeviceAvailability availability,
        Set<String> enabledExtensions,
        Set<VulkanFeature> enabledFeatures
    ) {
        boolean extensionPresentBefore = true;
        boolean featurePresentBefore = true;
        boolean ownershipKnown = false;
        VulkanDeviceFaultNegotiator.Selection faultSelection;
        try {
            extensionPresentBefore = enabledExtensions.contains(
                VulkanDeviceFaultNegotiator.DEVICE_FAULT_EXTENSION
            );
            featurePresentBefore = enabledFeatures.contains(
                VulkanDeviceFaultNegotiator.DEVICE_FAULT_FEATURE
            );
            ownershipKnown = true;
            boolean faultRequested = BlockframeRuntime.featureEnabled(
                de.morau.blockframe.core.state.FeatureId.DEVICE_FAULT
            );
            var faultAvailability = availability.deviceFaultAvailability();
            boolean captureHookReady =
                !faultRequested
                    || !faultAvailability.extensionSupported()
                    || !faultAvailability.deviceFault()
                    || VulkanDeviceFaultHookHealth.ensureFatalHookReady();
            faultSelection = VulkanDeviceFaultNegotiator.configure(
                faultRequested,
                captureHookReady,
                faultAvailability,
                enabledExtensions,
                enabledFeatures
            );
            BlockframeRuntime.engine().updateDeviceFaultNegotiation(
                faultSelection.requested(),
                faultSelection.extensionSupported(),
                faultSelection.featureSupported(),
                faultSelection.enabled(),
                faultSelection.unavailableReason()
            );
        } catch (Throwable error) {
            if (ownershipKnown) {
                rollbackOptionalDeviceFault(
                    enabledFeatures,
                    VulkanDeviceFaultNegotiator.DEVICE_FAULT_FEATURE,
                    featurePresentBefore
                );
                rollbackOptionalDeviceFault(
                    enabledExtensions,
                    VulkanDeviceFaultNegotiator.DEVICE_FAULT_EXTENSION,
                    extensionPresentBefore
                );
            }
            try {
                BlockframeRuntime.engine().updateDeviceFaultNegotiation(
                    true,
                    false,
                    false,
                    false,
                    "optional-negotiation-failed:"
                        + error.getClass().getSimpleName()
                );
            } catch (Throwable ignored) {
                // The existing Mojang path remains authoritative.
            }
            try {
                NvidiaDlssMod.LOGGER.warn(
                    "Optionale Vulkan-Device-Fault-Aktivierung wurde "
                        + "fail-open deaktiviert",
                    error
                );
            } catch (Throwable ignored) {
                // Logging cannot become a device-creation requirement.
            }
            return;
        }
        try {
            NvidiaDlssMod.LOGGER.info(
                "Vulkan Device Fault: requested={} extension-supported={} "
                    + "feature-supported={} enabled={} capture-hook={} "
                    + "unavailable-reason={}",
                faultSelection.requested(),
                faultSelection.extensionSupported(),
                faultSelection.featureSupported(),
                faultSelection.enabled(),
                faultSelection.captureHookReady(),
                faultSelection.unavailableReason().isBlank()
                    ? "none"
                    : faultSelection.unavailableReason()
            );
        } catch (Throwable ignored) {
            // Optional logging never changes the negotiated device inputs.
        }
    }

    private static void rollbackOptionalDeviceFault(
        Set<?> values,
        Object value,
        boolean presentBefore
    ) {
        if (presentBefore) {
            return;
        }
        try {
            values.remove(value);
        } catch (Throwable ignored) {
            // The negotiator already attempted its own transactional rollback.
        }
    }

    public static synchronized boolean ensureNativeLoaded() {
        if (!BlockframeRuntime.streamlineBootstrapAllowed()) {
            capabilityReason = BlockframeRuntime.safeStartActive()
                ? "DLSS disabled by one-shot Safe Start"
                : "DLSS bootstrap requires a process restart";
            DlssStatus.unavailable(capabilityReason);
            return false;
        }
        if (bootstrapped) {
            if (streamlineRequirements != null) {
                return true;
            }
            capabilityReason =
                "DLSS disabled: bootstrapped Streamline has no requirements snapshot";
            DlssStatus.unavailable(capabilityReason);
            return false;
        }
        if (nativeShutdownUncertain) {
            capabilityReason =
                "DLSS disabled: prior native Streamline shutdown was unconfirmed";
            DlssStatus.unavailable(capabilityReason);
            return false;
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            DlssStatus.unavailable("nur Windows x64 wird unterstützt");
            return false;
        }
        String architecture = System.getProperty("os.arch", "")
            .toLowerCase(java.util.Locale.ROOT);
        if (!architecture.equals("amd64") && !architecture.equals("x86_64")) {
            DlssStatus.unavailable(
                "DLSS native runtime supports Windows x64 only, not "
                    + architecture
            );
            return false;
        }
        boolean nativeStarted = false;
        try {
            Path directory = nativeDirectory;
            if (!libraryLoaded) {
                PersistentArtifactCache.Result cacheResult =
                    NativeRuntimeArtifacts.materialize();
                directory = cacheResult.path();
                System.load(
                    directory
                        .resolve("nvidia_dlss_bridge.dll")
                        .toAbsolutePath()
                        .toString()
                );
                nativeDirectory = directory;
                libraryLoaded = true;
                NvidiaDlssMod.LOGGER.info(
                    "BlockFrame Native-Cache {}: persistent={}, artifacts={} bytes, lookup={} ms, write={} ms, path={}",
                    cacheResult.status(),
                    cacheResult.persistent(),
                    cacheResult.artifactBytes(),
                    nanosToMillis(cacheResult.lookupNanos()),
                    nanosToMillis(cacheResult.writeNanos()),
                    directory
                );
            }
            if (directory == null) {
                throw new IllegalStateException(
                    "Native Streamline directory was not retained"
                );
            }
            Path logDirectory = streamlineLogDirectory();
            int result = NativeStreamline.bootstrap(
                directory.resolve("sl.interposer.dll").toAbsolutePath().toString(),
                directory.toAbsolutePath().toString(),
                logDirectory.toAbsolutePath().toString()
            );
            if (result != 0) {
                if (result == NativeStreamline.CLEANUP_UNCONFIRMED) {
                    nativeShutdownUncertain = true;
                }
                throw new IllegalStateException(
                    NativeStreamline.lastMessage() + " [" + result + "]"
                );
            }
            nativeStarted = true;
            StreamlineFeatureRequirements requirements =
                StreamlineFeatureRequirements.decode(
                    NativeStreamline.featureRequirements()
                );
            StreamlineFeatureRequirements.Validation validation =
                requirements.validatePinnedDlssNis();
            if (!validation.compatible()) {
                throw new IllegalStateException(validation.reason());
            }
            streamlineRequirements = requirements;
            bootstrapped = true;
            NvidiaDlssMod.LOGGER.info(
                "NVIDIA Streamline Runtime initialisiert: {}; {}",
                directory,
                requirements.deterministicSummary()
            );
            return true;
        } catch (Throwable error) {
            bootstrapped = false;
            streamlineRequirements = null;
            String failure = error.getMessage() == null
                ? error.toString()
                : error.getMessage();
            if (nativeStarted) {
                failure += rollbackRequirementsBootstrap();
            }
            capabilityReason = "DLSS disabled: " + failure;
            DlssStatus.error(capabilityReason);
            NvidiaDlssMod.LOGGER.error("NVIDIA Streamline konnte nicht geladen werden", error);
            return false;
        }
    }

    private static String rollbackRequirementsBootstrap() {
        try {
            int result = NativeStreamline.shutdown();
            if (result == 0) {
                return "";
            }
            nativeShutdownUncertain = true;
            String message;
            try {
                message = NativeStreamline.lastMessage();
            } catch (Throwable ignored) {
                message = "native shutdown returned " + result;
            }
            return "; native Streamline cleanup was not confirmed: "
                + message
                + " ["
                + result
                + "]";
        } catch (Throwable cleanupError) {
            nativeShutdownUncertain = true;
            return "; native Streamline cleanup threw "
                + cleanupError.getClass().getSimpleName();
        }
    }

    private static Path streamlineLogDirectory() throws Exception {
        Path preferred = Path.of("logs", "blockframe-streamline")
            .toAbsolutePath();
        try {
            Files.createDirectories(preferred);
            return preferred;
        } catch (Exception | LinkageError error) {
            Path fallback = Files.createTempDirectory(
                "blockframe-streamline-logs-"
            );
            fallback.toFile().deleteOnExit();
            NvidiaDlssMod.LOGGER.warn(
                "Streamline-Logverzeichnis ist nicht verfügbar; verwende temporären Fallback {}",
                fallback,
                error
            );
            return fallback;
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    public static synchronized void connectDevice(VulkanDevice backend) {
        if (!BlockframeRuntime.streamlineBootstrapAllowed()) {
            capabilityReason = BlockframeRuntime.safeStartActive()
                ? "DLSS disabled by one-shot Safe Start"
                : "DLSS bootstrap requires a process restart";
            DlssStatus.unavailable(capabilityReason);
            return;
        }
        if (connected) {
            if (vulkanBackend != backend) {
                capabilityReason =
                    "DLSS disabled: Streamline is still connected to a different Vulkan device";
                DlssStatus.unavailable(capabilityReason);
                NvidiaDlssMod.LOGGER.error(capabilityReason);
            }
            return;
        }
        if (!deviceRequirementsReady) {
            DlssStatus.unavailable(capabilityReason);
            shutdownUnconnectedBootstrapAndReport();
            return;
        }
        if (!ensureNativeLoaded()) {
            return;
        }
        try {
            long instance = backend.instance().vkInstance().address();
            long physical = backend.vkDevice().getPhysicalDevice().address();
            long device = backend.vkDevice().address();
            int graphicsFamily = backend.graphicsQueue().queueFamilyIndex();
            int computeFamily = backend.computeQueue().queueFamilyIndex();
            int computeIndex = backend.computeQueue() == backend.graphicsQueue() ? 0
                : graphicsFamily == computeFamily ? 1 : 0;
            int result = NativeStreamline.setVulkanInfo(instance, physical, device,
                graphicsFamily, 0, computeFamily, computeIndex);
            if (result != 0) {
                String failureMessage = NativeStreamline.lastMessage();
                DlssStatus.unavailable(failureMessage);
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS-Hardwareprüfung: {}",
                    failureMessage
                );
                shutdownUnconnectedBootstrapAndReport();
                return;
            }
            connected = true;
            vulkanBackend = backend;
            DlssStatus.ready(backend.getDeviceInfo().vendorName() + " " + backend.getDeviceInfo().name());
            NvidiaDlssMod.LOGGER.info("[DLSS self-test] PASS: {}", NativeStreamline.lastMessage());
        } catch (Throwable error) {
            DlssStatus.error(error.getMessage() == null ? error.toString() : error.getMessage());
            NvidiaDlssMod.LOGGER.error("Vulkan-Gerät konnte nicht mit Streamline verbunden werden", error);
            shutdownUnconnectedBootstrapAndReport();
        }
    }

    public static boolean connected() { return connected; }
    public static VulkanDevice vulkanBackend() { return vulkanBackend; }
    public static boolean connectedTo(VulkanDevice backend) {
        return connected && vulkanBackend == backend;
    }
    public static boolean handlesPresentQueue(long queueAddress) {
        VulkanDevice backend = vulkanBackend;
        if (!connected || backend == null) {
            return false;
        }
        try {
            return backend.graphicsQueue().vkQueue().address() == queueAddress;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
    public static synchronized void shutdownConnection() {
        shutdownConnectionAndReport();
    }

    /**
     * Clears the Java-side connection state and reports whether the optional
     * native Streamline shutdown completed. The public compatibility wrapper
     * remains fail-open, while the device lifecycle consumes this result as
     * part of its normal-shutdown proof.
     */
    public static synchronized boolean shutdownConnectionAndReport() {
        connected = false;
        vulkanBackend = null;
        instanceRequirementsReady = false;
        deviceRequirementsReady = false;
        instanceSelection = null;
        deviceSelection = null;
        streamlineRequirements = null;
        capabilityReason = "Vulkan DLSS capabilities not evaluated";
        if (!bootstrapped) {
            return !nativeShutdownUncertain;
        }
        boolean completed;
        try {
            completed = runNativeShutdownAndReport(
                () -> {
                    int result = NativeStreamline.shutdown();
                    if (result != 0) {
                        throw new IllegalStateException(
                            NativeStreamline.lastMessage()
                                + " ["
                                + result
                                + "]"
                        );
                    }
                }
            );
        } finally {
            bootstrapped = false;
        }
        if (!completed) {
            nativeShutdownUncertain = true;
        }
        return completed && !nativeShutdownUncertain;
    }

    /**
     * Final guard for a runtime bootstrapped before any device connection.
     * A live connection remains exclusively owned by the device-close path.
     */
    public static synchronized boolean
        shutdownUnconnectedBootstrapAndReport() {
        if (connected) {
            return true;
        }
        String retainedReason = capabilityReason;
        boolean completed = shutdownConnectionAndReport();
        capabilityReason = retainedReason;
        return completed;
    }

    static boolean runNativeShutdownAndReport(Runnable shutdown) {
        try {
            shutdown.run();
            return true;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            NvidiaDlssMod.LOGGER.warn(
                "NVIDIA Streamline Runtime konnte nicht sauber beendet werden",
                error
            );
            return false;
        }
    }
    public static boolean deviceRequirementsReady() {
        return deviceRequirementsReady;
    }
    public static String capabilityReason() {
        return capabilityReason;
    }
}
