package de.morau.nvidiadlss;

import de.morau.blockframe.core.BlockframeRuntime;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

public final class DlssConfig {
    private static final Path PATH = Path.of("config", "voxellift.properties");
    private static final Path LEGACY_PATH = Path.of("config", "nvidia_dlss.properties");
    private static final DlssMode DEFAULT_MODE = DlssMode.OFF;
    private static final SharpeningMode DEFAULT_SHARPENING =
        SharpeningMode.AUTO;
    private static final int DEFAULT_SHARPENING_AMOUNT = 20;
    private static final EntityMotionHistory.BackendPreference
        DEFAULT_ENTITY_HISTORY_BACKEND =
            EntityMotionHistory.BackendPreference.HEAP;
    private static volatile Snapshot currentSnapshot =
        safeDefaults(ConfigSource.DEFAULTS);

    static { load(); }

    private DlssConfig() {}

    public static Snapshot snapshot() {
        return currentSnapshot;
    }

    public static DlssMode mode() {
        return currentSnapshot.mode();
    }

    public static SharpeningMode sharpening() {
        return currentSnapshot.sharpening();
    }

    public static int sharpeningAmount() {
        return currentSnapshot.sharpeningAmount();
    }

    public static EntityMotionHistory.BackendPreference
        entityHistoryBackend() {
        return currentSnapshot.entityHistoryBackend();
    }

    public static float effectiveSharpness() {
        Snapshot snapshot = currentSnapshot;
        return effectiveSharpness(snapshot.mode(), snapshot);
    }

    public static float effectiveSharpness(DlssMode effectiveMode) {
        return effectiveSharpness(effectiveMode, currentSnapshot);
    }

    private static float effectiveSharpness(
        DlssMode effectiveMode,
        Snapshot snapshot
    ) {
        if (
            snapshot.sharpening() == SharpeningMode.OFF
                || effectiveMode == DlssMode.OFF
        ) {
            return 0.0F;
        }
        if (snapshot.sharpening() == SharpeningMode.MANUAL) {
            return Math.min(
                0.5F,
                snapshot.sharpeningAmount() / 200.0F
            );
        }
        return switch (effectiveMode) {
            case DLAA -> 0.0F;
            case QUALITY -> 0.0F;
            case BALANCED -> 0.05F;
            case PERFORMANCE -> 0.10F;
            case ULTRA_PERFORMANCE -> 0.15F;
            default -> 0.0F;
        };
    }

    public static synchronized void setMode(DlssMode value) {
        Snapshot previous = currentSnapshot;
        currentSnapshot = createSnapshot(
            value == null ? DEFAULT_MODE : value,
            previous.sharpening(),
            previous.sharpeningAmount(),
            previous.entityHistoryBackend(),
            previous.configSource()
        );
        notifyRuntimeConfigurationChanged();
        DlssRenderer.requestReset();
        save();
    }

    public static synchronized void setSharpening(SharpeningMode value) {
        Snapshot previous = currentSnapshot;
        currentSnapshot = createSnapshot(
            previous.mode(),
            value == null ? DEFAULT_SHARPENING : value,
            previous.sharpeningAmount(),
            previous.entityHistoryBackend(),
            previous.configSource()
        );
        notifyRuntimeConfigurationChanged();
        save();
    }

    public static synchronized void setSharpeningAmount(Integer value) {
        Snapshot previous = currentSnapshot;
        currentSnapshot = createSnapshot(
            previous.mode(),
            previous.sharpening(),
            boundedSharpeningAmount(
                value == null ? DEFAULT_SHARPENING_AMOUNT : value
            ),
            previous.entityHistoryBackend(),
            previous.configSource()
        );
        notifyRuntimeConfigurationChanged();
        save();
    }

    private static void notifyRuntimeConfigurationChanged() {
        Snapshot snapshot = currentSnapshot;
        BlockframeRuntime.dlssConfigurationChanged(
            snapshot.mode().id(),
            snapshot.sharpening().id(),
            snapshot.sharpeningAmount(),
            snapshot.entityHistoryBackend().id()
        );
    }

    public static synchronized void load() {
        try {
            currentSnapshot = readSnapshot(PATH, LEGACY_PATH);
        } catch (Exception e) {
            currentSnapshot = safeDefaults(ConfigSource.DEFAULTS);
            NvidiaDlssMod.LOGGER.warn("DLSS-Konfiguration konnte nicht gelesen werden", e);
        }
    }

    public static synchronized void save() {
        Snapshot snapshot = currentSnapshot;
        Properties properties = new Properties();
        properties.setProperty("mode", snapshot.mode().id());
        properties.setProperty(
            "sharpening",
            snapshot.sharpening().id()
        );
        properties.setProperty(
            "sharpeningAmount",
            Integer.toString(snapshot.sharpeningAmount())
        );
        properties.setProperty(
            "entityHistoryBackend",
            snapshot.entityHistoryBackend().id()
        );
        try {
            Files.createDirectories(PATH.getParent());
            Path temporary = PATH.resolveSibling(PATH.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "BlockFrame DLSS for Minecraft 26.2");
            }
            Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
            currentSnapshot = createSnapshot(
                snapshot.mode(),
                snapshot.sharpening(),
                snapshot.sharpeningAmount(),
                snapshot.entityHistoryBackend(),
                ConfigSource.CURRENT
            );
        } catch (Exception e) {
            NvidiaDlssMod.LOGGER.warn("DLSS-Konfiguration konnte nicht gespeichert werden", e);
        }
    }

    static synchronized Snapshot load(Path currentPath, Path legacyPath) {
        try {
            currentSnapshot = readSnapshot(currentPath, legacyPath);
        } catch (Exception ignored) {
            currentSnapshot = safeDefaults(ConfigSource.DEFAULTS);
        }
        return currentSnapshot;
    }

    static Snapshot readSnapshot(Path currentPath, Path legacyPath)
        throws Exception {
        Objects.requireNonNull(currentPath, "currentPath");
        Objects.requireNonNull(legacyPath, "legacyPath");

        Path sourcePath;
        ConfigSource configSource;
        if (Files.isRegularFile(currentPath)) {
            sourcePath = currentPath;
            configSource = ConfigSource.CURRENT;
        } else if (Files.isRegularFile(legacyPath)) {
            sourcePath = legacyPath;
            configSource = ConfigSource.LEGACY;
        } else {
            return safeDefaults(ConfigSource.DEFAULTS);
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(sourcePath)) {
            properties.load(input);
        }
        return decode(properties, configSource);
    }

    static Snapshot decode(
        Properties properties,
        ConfigSource configSource
    ) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(configSource, "configSource");
        return createSnapshot(
            DlssMode.byId(
                normalized(
                    properties.getProperty("mode"),
                    DEFAULT_MODE.id()
                )
            ),
            SharpeningMode.byId(
                normalized(
                    properties.getProperty("sharpening"),
                    DEFAULT_SHARPENING.id()
                )
            ),
            parseSharpeningAmount(
                properties.getProperty("sharpeningAmount")
            ),
            EntityMotionHistory.BackendPreference.byId(
                normalized(
                    properties.getProperty("entityHistoryBackend"),
                    DEFAULT_ENTITY_HISTORY_BACKEND.id()
                )
            ),
            configSource
        );
    }

    private static Snapshot safeDefaults(ConfigSource configSource) {
        return createSnapshot(
            DEFAULT_MODE,
            DEFAULT_SHARPENING,
            DEFAULT_SHARPENING_AMOUNT,
            DEFAULT_ENTITY_HISTORY_BACKEND,
            configSource
        );
    }

    private static Snapshot createSnapshot(
        DlssMode mode,
        SharpeningMode sharpening,
        int sharpeningAmount,
        EntityMotionHistory.BackendPreference entityHistoryBackend,
        ConfigSource configSource
    ) {
        return new Snapshot(
            mode,
            sharpening,
            sharpeningAmount,
            entityHistoryBackend,
            configSource,
            canonicalFingerprintMaterial(
                mode,
                sharpening,
                sharpeningAmount,
                entityHistoryBackend
            )
        );
    }

    private static int parseSharpeningAmount(String value) {
        if (value == null) {
            return DEFAULT_SHARPENING_AMOUNT;
        }
        try {
            return boundedSharpeningAmount(
                Integer.parseInt(value.trim())
            );
        } catch (NumberFormatException ignored) {
            return DEFAULT_SHARPENING_AMOUNT;
        }
    }

    private static int boundedSharpeningAmount(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String normalized(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? defaultValue : normalized;
    }

    private static String canonicalFingerprintMaterial(
        DlssMode mode,
        SharpeningMode sharpening,
        int sharpeningAmount,
        EntityMotionHistory.BackendPreference entityHistoryBackend
    ) {
        return "blockframe-dlss-config-v1\n"
            + "mode=" + mode.id() + '\n'
            + "sharpening=" + sharpening.id() + '\n'
            + "sharpeningAmount=" + sharpeningAmount + '\n'
            + "entityHistoryBackend=" + entityHistoryBackend.id() + '\n';
    }

    public enum ConfigSource {
        DEFAULTS("defaults"),
        CURRENT("current"),
        LEGACY("legacy");

        private final String id;

        ConfigSource(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }
    }

    public record Snapshot(
        DlssMode mode,
        SharpeningMode sharpening,
        int sharpeningAmount,
        EntityMotionHistory.BackendPreference entityHistoryBackend,
        ConfigSource configSource,
        String fingerprintMaterial
    ) {
        public Snapshot(
            DlssMode mode,
            SharpeningMode sharpening,
            int sharpeningAmount,
            EntityMotionHistory.BackendPreference entityHistoryBackend,
            ConfigSource configSource
        ) {
            this(
                mode,
                sharpening,
                sharpeningAmount,
                entityHistoryBackend,
                configSource,
                canonicalFingerprintMaterial(
                    mode,
                    sharpening,
                    sharpeningAmount,
                    entityHistoryBackend
                )
            );
        }

        public Snapshot {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(sharpening, "sharpening");
            Objects.requireNonNull(
                entityHistoryBackend,
                "entityHistoryBackend"
            );
            Objects.requireNonNull(configSource, "configSource");
            Objects.requireNonNull(
                fingerprintMaterial,
                "fingerprintMaterial"
            );
            if (sharpeningAmount < 0 || sharpeningAmount > 100) {
                throw new IllegalArgumentException(
                    "sharpeningAmount must be within [0, 100]"
                );
            }
            String expectedFingerprintMaterial =
                canonicalFingerprintMaterial(
                    mode,
                    sharpening,
                    sharpeningAmount,
                    entityHistoryBackend
                );
            if (!expectedFingerprintMaterial.equals(fingerprintMaterial)) {
                throw new IllegalArgumentException(
                    "fingerprintMaterial is not canonical"
                );
            }
        }
    }
}
