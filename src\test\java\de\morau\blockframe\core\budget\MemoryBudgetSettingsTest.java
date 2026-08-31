package de.morau.blockframe.core.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MemoryBudgetSettingsTest {
    @Test
    void validPropertiesRoundTripEveryGlobalAndCategoryLimit() {
        Properties properties = new Properties();
        properties.setProperty(MemoryBudgetSettings.RAM_MAX_BYTES_KEY, " 1000 ");
        properties.setProperty(MemoryBudgetSettings.VRAM_MAX_BYTES_KEY, "2000");
        properties.setProperty(MemoryBudgetSettings.RAM_SAFETY_BYTES_KEY, "100");
        properties.setProperty(MemoryBudgetSettings.VRAM_SAFETY_BYTES_KEY, "250");
        for (MemoryCategory category : MemoryCategory.values()) {
            properties.setProperty(
                MemoryBudgetSettings.categoryKey(MemoryKind.RAM, category),
                Long.toString(110L + category.ordinal())
            );
            properties.setProperty(
                MemoryBudgetSettings.categoryKey(MemoryKind.VRAM, category),
                Long.toString(210L + category.ordinal())
            );
        }

        MemoryBudgetSettings settings = MemoryBudgetSettings.from(properties);

        assertEquals(1_000L, settings.maxBytes(MemoryKind.RAM));
        assertEquals(2_000L, settings.maxBytes(MemoryKind.VRAM));
        assertEquals(100L, settings.safetyBytes(MemoryKind.RAM));
        assertEquals(250L, settings.safetyBytes(MemoryKind.VRAM));
        assertEquals(900L, settings.usableBytes(MemoryKind.RAM));
        assertEquals(1_750L, settings.usableBytes(MemoryKind.VRAM));
        for (MemoryCategory category : MemoryCategory.values()) {
            assertEquals(
                110L + category.ordinal(),
                settings.categoryBytes(MemoryKind.RAM, category)
            );
            assertEquals(
                210L + category.ordinal(),
                settings.categoryBytes(MemoryKind.VRAM, category)
            );
        }

        Properties serialized = new Properties();
        settings.writeTo(serialized);
        assertEquals(settings, MemoryBudgetSettings.from(serialized));
        assertEquals(
            "memory.ram.shaderResourcesBytes",
            MemoryBudgetSettings.categoryKey(
                MemoryKind.RAM,
                MemoryCategory.SHADER_RESOURCES
            )
        );
        assertEquals(
            "memory.vram.diagnosticsBytes",
            MemoryBudgetSettings.categoryKey(
                MemoryKind.VRAM,
                MemoryCategory.DIAGNOSTICS
            )
        );
    }

    @Test
    void legacyPropertiesWithoutDiagnosticsUseSafeAppendedDefaults() {
        Properties legacy = new Properties();
        for (MemoryCategory category : MemoryCategory.values()) {
            if (category == MemoryCategory.DIAGNOSTICS) {
                continue;
            }
            legacy.setProperty(
                MemoryBudgetSettings.categoryKey(MemoryKind.RAM, category),
                Long.toString(100L + category.ordinal())
            );
            legacy.setProperty(
                MemoryBudgetSettings.categoryKey(MemoryKind.VRAM, category),
                Long.toString(200L + category.ordinal())
            );
        }

        MemoryBudgetSettings settings = MemoryBudgetSettings.from(legacy);

        assertEquals(5, MemoryCategory.STAGING.ordinal());
        assertEquals(6, MemoryCategory.DIAGNOSTICS.ordinal());
        assertEquals(7, MemoryCategory.values().length);
        assertEquals(
            64L * 1024L * 1024L,
            settings.categoryBytes(
                MemoryKind.RAM,
                MemoryCategory.DIAGNOSTICS
            )
        );
        assertEquals(
            128L * 1024L * 1024L,
            settings.categoryBytes(
                MemoryKind.VRAM,
                MemoryCategory.DIAGNOSTICS
            )
        );
        assertEquals(
            105L,
            settings.categoryBytes(MemoryKind.RAM, MemoryCategory.STAGING)
        );
        assertEquals(
            205L,
            settings.categoryBytes(MemoryKind.VRAM, MemoryCategory.STAGING)
        );

        Properties serialized = new Properties();
        settings.writeTo(serialized);
        assertEquals(
            Long.toString(64L * 1024L * 1024L),
            serialized.getProperty("memory.ram.diagnosticsBytes")
        );
        assertEquals(
            Long.toString(128L * 1024L * 1024L),
            serialized.getProperty("memory.vram.diagnosticsBytes")
        );
    }

    @Test
    void malformedPropertiesUseBoundedDefaults() {
        Properties properties = new Properties();
        properties.setProperty(MemoryBudgetSettings.RAM_MAX_BYTES_KEY, "1024");
        properties.setProperty(MemoryBudgetSettings.VRAM_MAX_BYTES_KEY, "-1");
        properties.setProperty(MemoryBudgetSettings.RAM_SAFETY_BYTES_KEY, "1024");
        properties.setProperty(MemoryBudgetSettings.VRAM_SAFETY_BYTES_KEY, "bad");
        properties.setProperty(
            MemoryBudgetSettings.categoryKey(
                MemoryKind.RAM,
                MemoryCategory.TERRAIN
            ),
            "0"
        );

        MemoryBudgetSettings settings = MemoryBudgetSettings.from(properties);
        MemoryBudgetSettings defaults = MemoryBudgetSettings.defaults();

        assertEquals(1_024L, settings.maxBytes(MemoryKind.RAM));
        assertEquals(128L, settings.safetyBytes(MemoryKind.RAM));
        assertEquals(
            defaults.maxBytes(MemoryKind.VRAM),
            settings.maxBytes(MemoryKind.VRAM)
        );
        assertEquals(
            defaults.safetyBytes(MemoryKind.VRAM),
            settings.safetyBytes(MemoryKind.VRAM)
        );
        assertEquals(
            defaults.categoryBytes(MemoryKind.RAM, MemoryCategory.TERRAIN),
            settings.categoryBytes(MemoryKind.RAM, MemoryCategory.TERRAIN)
        );
    }

    @Test
    void constructorEnforcesBoundsAndDefensivelyCopiesCategories() {
        long[] ramCategories = categories(100L);
        long[] vramCategories = categories(200L);
        MemoryBudgetSettings settings = new MemoryBudgetSettings(
            1_000L,
            2_000L,
            100L,
            200L,
            ramCategories,
            vramCategories
        );

        ramCategories[MemoryCategory.TERRAIN.ordinal()] = 999L;
        vramCategories[MemoryCategory.STAGING.ordinal()] = 999L;
        assertEquals(
            100L,
            settings.categoryBytes(MemoryKind.RAM, MemoryCategory.TERRAIN)
        );
        assertEquals(
            200L,
            settings.categoryBytes(MemoryKind.VRAM, MemoryCategory.STAGING)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new MemoryBudgetSettings(
                0L,
                2_000L,
                0L,
                0L,
                categories(1L),
                categories(1L)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new MemoryBudgetSettings(
                1_000L,
                2_000L,
                1_000L,
                0L,
                categories(1L),
                categories(1L)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new MemoryBudgetSettings(
                1_000L,
                2_000L,
                -1L,
                0L,
                categories(1L),
                categories(1L)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new MemoryBudgetSettings(
                1_000L,
                2_000L,
                0L,
                0L,
                new long[MemoryCategory.values().length - 1],
                categories(1L)
            )
        );
        long[] invalidCategory = categories(1L);
        invalidCategory[MemoryCategory.PARTICLES.ordinal()] = 0L;
        assertThrows(
            IllegalArgumentException.class,
            () -> new MemoryBudgetSettings(
                1_000L,
                2_000L,
                0L,
                0L,
                invalidCategory,
                categories(1L)
            )
        );
    }

    private static long[] categories(long value) {
        long[] result = new long[MemoryCategory.values().length];
        Arrays.fill(result, value);
        return result;
    }
}
