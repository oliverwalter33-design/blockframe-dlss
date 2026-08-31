package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind;
import org.junit.jupiter.api.Test;

class MotionDescriptorInventoryAccountingTest {
    private static final int FRAME_RING_SIZE = 3;

    @Test
    void releaseModeClosesAllEighteenDescriptorSlots() {
        assertInventoryClosesCleanly(false, 18);
    }

    @Test
    void diagnosticModeClosesAllTwentySevenDescriptorSlots() {
        assertInventoryClosesCleanly(true, 27);
    }

    private static void assertInventoryClosesCleanly(
        boolean developerDiagnostics,
        int expectedSlots
    ) {
        int descriptorSlots =
            MotionVectorGenerator.descriptorSlotsForSets(
                FRAME_RING_SIZE,
                developerDiagnostics
            );
        assertEquals(expectedSlots, descriptorSlots);

        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        inventory.created(ResourceKind.DESCRIPTOR, descriptorSlots);
        inventory.destroyed(
            ResourceKind.DESCRIPTOR,
            MotionVectorGenerator.descriptorSlotsForSets(
                FRAME_RING_SIZE,
                developerDiagnostics
            )
        );

        ShaderResourceInventory.Snapshot snapshot =
            inventory.snapshot();
        assertEquals(0, snapshot.current(ResourceKind.DESCRIPTOR));
        assertEquals(0L, snapshot.integrityErrors());
        assertTrue(inventory.closeAndReport());
    }
}
