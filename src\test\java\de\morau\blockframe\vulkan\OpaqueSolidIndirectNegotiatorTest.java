package de.morau.blockframe.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class OpaqueSolidIndirectNegotiatorTest {
    @Test
    void completeCapabilitiesEnableBothOptionalCoreFeatures() {
        HashSet<VulkanFeature> features = new HashSet<>();
        var selection = OpaqueSolidIndirectNegotiator.configure(
            true,
            available(true, true, true, true),
            features
        );
        assertTrue(selection.enabled());
        assertTrue(
            features.contains(
                OpaqueSolidIndirectNegotiator
                    .DRAW_INDIRECT_COUNT_FEATURE
            )
        );
        assertTrue(
            features.contains(
                OpaqueSolidIndirectNegotiator
                    .DRAW_INDIRECT_FIRST_INSTANCE_FEATURE
            )
        );
    }

    @Test
    void everyMissingCapabilityFailsOpenWithoutFeatureMutation() {
        for (int missing = 0; missing < 4; missing++) {
            boolean[] values = {true, true, true, true};
            values[missing] = false;
            HashSet<VulkanFeature> features = new HashSet<>();
            var selection = OpaqueSolidIndirectNegotiator.configure(
                true,
                available(
                    values[0],
                    values[1],
                    values[2],
                    values[3]
                ),
                features
            );
            assertFalse(selection.enabled());
            assertTrue(selection.requested());
            assertTrue(features.isEmpty());
        }
    }

    @Test
    void disabledPolicyDoesNotMutateForeignFeatureSet() {
        HashSet<VulkanFeature> features = new HashSet<>();
        features.add(
            OpaqueSolidIndirectNegotiator.DRAW_INDIRECT_COUNT_FEATURE
        );
        int before = features.size();
        var selection = OpaqueSolidIndirectNegotiator.configure(
            false,
            available(true, true, true, true),
            features
        );
        assertFalse(selection.enabled());
        assertFalse(selection.requested());
        assertEquals(before, features.size());
    }

    @Test
    void undersizedStorageTexelAndIndirectLimitsFailOpen() {
        int[][] limits = {
            {5_242_879, 1_000_000, 1_000_000},
            {128 * 1024 * 1024, 32_767, 1_000_000},
            {128 * 1024 * 1024, 1_000_000, 16_383}
        };
        for (int[] limit : limits) {
            HashSet<VulkanFeature> features = new HashSet<>();
            var availability =
                new VulkanDeviceCapabilityProbe
                    .OpaqueSolidIndirectAvailability(
                        true,
                        true,
                        true,
                        true,
                        limit[0],
                        limit[1],
                        limit[2],
                        ""
                    );
            var selection = OpaqueSolidIndirectNegotiator.configure(
                true,
                availability,
                features
            );
            assertFalse(selection.enabled());
            assertTrue(features.isEmpty());
        }
    }

    private static VulkanDeviceCapabilityProbe
        .OpaqueSolidIndirectAvailability available(
        boolean multi,
        boolean shader,
        boolean count,
        boolean firstInstance
    ) {
        return new VulkanDeviceCapabilityProbe
            .OpaqueSolidIndirectAvailability(
                multi,
                shader,
                count,
                firstInstance,
                128 * 1024 * 1024,
                1_000_000,
                1_000_000,
                ""
            );
    }
}
