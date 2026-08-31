package de.morau.nvidiadlss;

/** Clean-room presence guard; it never opens or links Sodium code. */
public final class SodiumCompatibility {
    private static final String[] CLASS_MARKERS = {
        "net/caffeinemc/mods/sodium/client/SodiumClientMod.class",
        "me/jellysquid/mods/sodium/client/SodiumClientMod.class"
    };
    private static final String[] NATIVE_TERRAIN_MIXIN_SUFFIXES = {
        ".NativeTerrainDispatcherEvidenceMixin",
        ".NativeTerrainModelManagerMixin",
        ".NativeTerrainOpaqueSubmissionEvidenceMixin",
        ".NativeTerrainSectionCompilerEvidenceMixin",
        ".NativeTerrainUberHeapEvidenceMixin",
        ".accessor.NativeTerrainMultiPartModelAccessor",
        ".accessor.NativeTerrainWeightedVariantsAccessor"
    };
    private static final String SODIUM_SHADER_SUPPORT_MIXIN_SUFFIX =
        ".SodiumShaderChunkRendererMixin";

    private SodiumCompatibility() {
    }

    public static boolean detected(ClassLoader loader) {
        for (String marker : CLASS_MARKERS) {
            if (loader.getResource(marker) != null) {
                return true;
            }
        }
        return false;
    }

    public static boolean mixinAllowed(
        String mixinClassName,
        boolean sodiumPresent
    ) {
        if (isSodiumShaderSupportMixin(mixinClassName)) {
            return sodiumPresent;
        }
        return !sodiumPresent || !isNativeTerrainMixin(mixinClassName);
    }

    public static boolean isSodiumShaderSupportMixin(String mixinClassName) {
        return mixinClassName != null
            && mixinClassName.endsWith(SODIUM_SHADER_SUPPORT_MIXIN_SUFFIX);
    }

    public static boolean isNativeTerrainMixin(String mixinClassName) {
        if (mixinClassName == null) {
            return false;
        }
        for (String suffix : NATIVE_TERRAIN_MIXIN_SUFFIXES) {
            if (mixinClassName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
