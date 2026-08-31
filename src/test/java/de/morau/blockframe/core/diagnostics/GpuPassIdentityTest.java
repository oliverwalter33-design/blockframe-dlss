package de.morau.blockframe.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GpuPassIdentityTest {
    @Test
    void currentPassesHaveStableUniqueNamesAndBreadcrumbIds() {
        assertEquals("BlockFrame / Frame", GpuPassIdentity.FRAME.label());
        assertEquals(
            "BlockFrame / Motion Compute",
            GpuPassIdentity.MOTION_COMPUTE.label()
        );
        assertEquals(
            "BlockFrame / DLSS Evaluate",
            GpuPassIdentity.DLSS_EVALUATE.label()
        );
        assertEquals(
            "BlockFrame / Graphics Submit",
            GpuPassIdentity.GRAPHICS_SUBMIT.label()
        );

        Set<String> labels = new HashSet<>();
        Set<Integer> breadcrumbIds = new HashSet<>();
        for (GpuPassIdentity identity : GpuPassIdentity.values()) {
            assertEquals(identity.label(), identity.get());
            labels.add(identity.label());
            if (identity != GpuPassIdentity.FRAME) {
                breadcrumbIds.add(identity.breadcrumbId());
            }
        }
        assertEquals(GpuPassIdentity.values().length, labels.size());
        assertEquals(3, breadcrumbIds.size());
    }

    @Test
    void breadcrumbMappingUsesTheExistingThreeProductiveIds() {
        assertEquals(
            GpuPassIdentity.MOTION_COMPUTE,
            GpuPassIdentity.fromBreadcrumbId(
                GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
            )
        );
        assertEquals(
            GpuPassIdentity.DLSS_EVALUATE,
            GpuPassIdentity.fromBreadcrumbId(
                GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE
            )
        );
        assertEquals(
            GpuPassIdentity.GRAPHICS_SUBMIT,
            GpuPassIdentity.fromBreadcrumbId(
                GpuSubmissionBreadcrumbs.PASS_GRAPHICS_SUBMIT
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> GpuPassIdentity.fromBreadcrumbId(0)
        );
    }
}
