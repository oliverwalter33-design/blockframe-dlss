package de.morau.blockframe.core.budget;

import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;

/**
 * Immutable, bounded settings for logical BlockFrame-owned memory.
 *
 * <p>These are safety ceilings, not claims about total JVM or driver usage.
 * Category ceilings may intentionally overlap; the global usable limit still
 * applies to their sum.</p>
 */
public final class MemoryBudgetSettings {
    public static final String RAM_MAX_BYTES_KEY = "memory.ram.maxBytes";
    public static final String VRAM_MAX_BYTES_KEY = "memory.vram.maxBytes";
    public static final String RAM_SAFETY_BYTES_KEY = "memory.ram.safetyBytes";
    public static final String VRAM_SAFETY_BYTES_KEY = "memory.vram.safetyBytes";

    public static final long DEFAULT_RAM_MAX_BYTES = 2L * 1024L * 1024L * 1024L;
    public static final long DEFAULT_VRAM_MAX_BYTES = 4L * 1024L * 1024L * 1024L;
    public static final long DEFAULT_RAM_SAFETY_BYTES = 256L * 1024L * 1024L;
    public static final long DEFAULT_VRAM_SAFETY_BYTES = 512L * 1024L * 1024L;

    private static final long MIB = 1024L * 1024L;
    private static final String[] CATEGORY_NAMES = {
        "terrain",
        "entities",
        "particles",
        "shaderResources",
        "caches",
        "staging",
        "diagnostics"
    };
    private static final long[] DEFAULT_RAM_CATEGORY_BYTES = {
        1024L * MIB,
        384L * MIB,
        256L * MIB,
        256L * MIB,
        768L * MIB,
        256L * MIB,
        64L * MIB
    };
    private static final long[] DEFAULT_VRAM_CATEGORY_BYTES = {
        2048L * MIB,
        768L * MIB,
        512L * MIB,
        1024L * MIB,
        512L * MIB,
        512L * MIB,
        128L * MIB
    };

    private final long ramMaxBytes;
    private final long vramMaxBytes;
    private final long ramSafetyBytes;
    private final long vramSafetyBytes;
    private final long[] ramCategoryBytes;
    private final long[] vramCategoryBytes;

    public MemoryBudgetSettings(
        long ramMaxBytes,
        long vramMaxBytes,
        long ramSafetyBytes,
        long vramSafetyBytes,
        long[] ramCategoryBytes,
        long[] vramCategoryBytes
    ) {
        this.ramMaxBytes = positive(ramMaxBytes, "ramMaxBytes");
        this.vramMaxBytes = positive(vramMaxBytes, "vramMaxBytes");
        this.ramSafetyBytes = safety(ramSafetyBytes, ramMaxBytes, "ramSafetyBytes");
        this.vramSafetyBytes = safety(vramSafetyBytes, vramMaxBytes, "vramSafetyBytes");
        this.ramCategoryBytes = categories(ramCategoryBytes, "ramCategoryBytes");
        this.vramCategoryBytes = categories(vramCategoryBytes, "vramCategoryBytes");
    }

    public static MemoryBudgetSettings defaults() {
        return new MemoryBudgetSettings(
            DEFAULT_RAM_MAX_BYTES,
            DEFAULT_VRAM_MAX_BYTES,
            DEFAULT_RAM_SAFETY_BYTES,
            DEFAULT_VRAM_SAFETY_BYTES,
            DEFAULT_RAM_CATEGORY_BYTES,
            DEFAULT_VRAM_CATEGORY_BYTES
        );
    }

    public static MemoryBudgetSettings from(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        MemoryBudgetSettings defaults = defaults();
        long ramMax = parsePositive(
            properties.getProperty(RAM_MAX_BYTES_KEY),
            defaults.ramMaxBytes
        );
        long vramMax = parsePositive(
            properties.getProperty(VRAM_MAX_BYTES_KEY),
            defaults.vramMaxBytes
        );
        long ramSafety = parseSafety(
            properties.getProperty(RAM_SAFETY_BYTES_KEY),
            defaults.ramSafetyBytes,
            ramMax
        );
        long vramSafety = parseSafety(
            properties.getProperty(VRAM_SAFETY_BYTES_KEY),
            defaults.vramSafetyBytes,
            vramMax
        );
        long[] ramCategories = parseCategories(
            properties,
            MemoryKind.RAM,
            DEFAULT_RAM_CATEGORY_BYTES
        );
        long[] vramCategories = parseCategories(
            properties,
            MemoryKind.VRAM,
            DEFAULT_VRAM_CATEGORY_BYTES
        );
        return new MemoryBudgetSettings(
            ramMax,
            vramMax,
            ramSafety,
            vramSafety,
            ramCategories,
            vramCategories
        );
    }

    public void writeTo(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        properties.setProperty(RAM_MAX_BYTES_KEY, Long.toString(this.ramMaxBytes));
        properties.setProperty(VRAM_MAX_BYTES_KEY, Long.toString(this.vramMaxBytes));
        properties.setProperty(RAM_SAFETY_BYTES_KEY, Long.toString(this.ramSafetyBytes));
        properties.setProperty(VRAM_SAFETY_BYTES_KEY, Long.toString(this.vramSafetyBytes));
        for (MemoryCategory category : MemoryCategory.values()) {
            int index = category.ordinal();
            properties.setProperty(
                categoryKey(MemoryKind.RAM, category),
                Long.toString(this.ramCategoryBytes[index])
            );
            properties.setProperty(
                categoryKey(MemoryKind.VRAM, category),
                Long.toString(this.vramCategoryBytes[index])
            );
        }
    }

    public long maxBytes(MemoryKind kind) {
        return kind == MemoryKind.RAM ? this.ramMaxBytes : this.vramMaxBytes;
    }

    public long safetyBytes(MemoryKind kind) {
        return kind == MemoryKind.RAM ? this.ramSafetyBytes : this.vramSafetyBytes;
    }

    public long usableBytes(MemoryKind kind) {
        return this.maxBytes(kind) - this.safetyBytes(kind);
    }

    public long categoryBytes(MemoryKind kind, MemoryCategory category) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(category, "category");
        return (kind == MemoryKind.RAM
            ? this.ramCategoryBytes
            : this.vramCategoryBytes)[category.ordinal()];
    }

    public static String categoryKey(
        MemoryKind kind,
        MemoryCategory category
    ) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(category, "category");
        return "memory."
            + kind.name().toLowerCase(java.util.Locale.ROOT)
            + "."
            + CATEGORY_NAMES[category.ordinal()]
            + "Bytes";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MemoryBudgetSettings that)) {
            return false;
        }
        return this.ramMaxBytes == that.ramMaxBytes
            && this.vramMaxBytes == that.vramMaxBytes
            && this.ramSafetyBytes == that.ramSafetyBytes
            && this.vramSafetyBytes == that.vramSafetyBytes
            && Arrays.equals(this.ramCategoryBytes, that.ramCategoryBytes)
            && Arrays.equals(this.vramCategoryBytes, that.vramCategoryBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
            this.ramMaxBytes,
            this.vramMaxBytes,
            this.ramSafetyBytes,
            this.vramSafetyBytes
        );
        result = 31 * result + Arrays.hashCode(this.ramCategoryBytes);
        return 31 * result + Arrays.hashCode(this.vramCategoryBytes);
    }

    @Override
    public String toString() {
        return "MemoryBudgetSettings[ram="
            + this.ramMaxBytes
            + ", vram="
            + this.vramMaxBytes
            + ", ramSafety="
            + this.ramSafetyBytes
            + ", vramSafety="
            + this.vramSafetyBytes
            + "]";
    }

    private static long[] parseCategories(
        Properties properties,
        MemoryKind kind,
        long[] defaults
    ) {
        long[] result = new long[MemoryCategory.values().length];
        for (MemoryCategory category : MemoryCategory.values()) {
            int index = category.ordinal();
            result[index] = parsePositive(
                properties.getProperty(categoryKey(kind, category)),
                defaults[index]
            );
        }
        return result;
    }

    private static long[] categories(long[] values, String name) {
        Objects.requireNonNull(values, name);
        if (values.length != MemoryCategory.values().length) {
            throw new IllegalArgumentException(
                name + " must contain one value per memory category"
            );
        }
        long[] copy = values.clone();
        for (int i = 0; i < copy.length; i++) {
            positive(copy[i], name + "[" + i + "]");
        }
        return copy;
    }

    private static long positive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long safety(long value, long maximum, String name) {
        if (value < 0L || value >= maximum) {
            throw new IllegalArgumentException(
                name + " must be non-negative and below its maximum"
            );
        }
        return value;
    }

    private static long parsePositive(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0L ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseSafety(
        String value,
        long fallback,
        long maximum
    ) {
        long boundedFallback = fallback < maximum ? fallback : maximum / 8L;
        if (value == null) {
            return boundedFallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0L && parsed < maximum
                ? parsed
                : boundedFallback;
        } catch (NumberFormatException ignored) {
            return boundedFallback;
        }
    }
}
