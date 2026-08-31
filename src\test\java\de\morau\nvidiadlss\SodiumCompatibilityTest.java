package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URL;
import org.junit.jupiter.api.Test;

class SodiumCompatibilityTest {
    @Test
    void sodiumKeepsDlssHooksAndRejectsOnlyNativeTerrainOwnership() {
        assertTrue(
            SodiumCompatibility.mixinAllowed(
                "de.morau.nvidiadlss.mixin.GameRendererMixin",
                true
            )
        );
        assertFalse(
            SodiumCompatibility.mixinAllowed(
                "de.morau.nvidiadlss.mixin.NativeTerrainModelManagerMixin",
                true
            )
        );
        assertTrue(
            SodiumCompatibility.mixinAllowed(
                "de.morau.nvidiadlss.mixin.NativeTerrainModelManagerMixin",
                false
            )
        );
    }

    @Test
    void nativeTerrainAllowListDoesNotMatchSimilarNames() {
        assertFalse(
            SodiumCompatibility.isNativeTerrainMixin(
                "example.NativeTerrainModelManagerMixinUnexpected"
            )
        );
        assertFalse(SodiumCompatibility.isNativeTerrainMixin(null));
    }

    @Test
    void detectsSodiumFromSyntheticClassResourceWithoutLoadingItsClasses() throws Exception {
        URL markerUrl = URI.create("file:/synthetic/sodium/SodiumClientMod.class").toURL();
        ClassLoader syntheticLoader = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                if ("net/caffeinemc/mods/sodium/client/SodiumClientMod.class".equals(name)) {
                    return markerUrl;
                }
                return null;
            }
        };

        assertTrue(SodiumCompatibility.detected(syntheticLoader));
    }
}
