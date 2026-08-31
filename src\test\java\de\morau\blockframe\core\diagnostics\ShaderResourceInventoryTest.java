package de.morau.blockframe.core.diagnostics;

import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.COMPUTE_PIPELINE;
import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.DESCRIPTOR;
import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.DESCRIPTOR_POOL;
import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.DESCRIPTOR_SET;
import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.DESCRIPTOR_SET_LAYOUT;
import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.MANAGED_UNIFORM_BUFFER;
import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.MATERIAL_SAMPLER;
import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.PIPELINE_LAYOUT;
import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.RAW_DEPTH_SAMPLER;
import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.SHADER_MODULE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShaderResourceInventoryTest {
    @Test
    void exactMotionOwnerCountsAndPeaksAreRetained() {
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();

        inventory.created(SHADER_MODULE);
        inventory.created(DESCRIPTOR_SET_LAYOUT);
        inventory.created(DESCRIPTOR_POOL);
        inventory.created(DESCRIPTOR_SET, 3);
        inventory.created(DESCRIPTOR, 27);
        inventory.created(PIPELINE_LAYOUT);
        inventory.created(COMPUTE_PIPELINE);
        inventory.created(RAW_DEPTH_SAMPLER);
        inventory.created(MANAGED_UNIFORM_BUFFER, 3);

        ShaderResourceInventory.Snapshot snapshot =
            inventory.snapshot();
        assertEquals(1, snapshot.current(SHADER_MODULE));
        assertEquals(1, snapshot.current(DESCRIPTOR_SET_LAYOUT));
        assertEquals(1, snapshot.current(DESCRIPTOR_POOL));
        assertEquals(3, snapshot.current(DESCRIPTOR_SET));
        assertEquals(27, snapshot.current(DESCRIPTOR));
        assertEquals(1, snapshot.current(PIPELINE_LAYOUT));
        assertEquals(1, snapshot.current(COMPUTE_PIPELINE));
        assertEquals(1, snapshot.current(RAW_DEPTH_SAMPLER));
        assertEquals(
            3,
            snapshot.current(MANAGED_UNIFORM_BUFFER)
        );
        assertEquals(39, snapshot.currentTotal());
        assertEquals(0L, snapshot.integrityErrors());
    }

    @Test
    void managedObjectsRemainRetiringForThreeFrames() {
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        inventory.created(MANAGED_UNIFORM_BUFFER, 3);
        inventory.created(MATERIAL_SAMPLER);

        inventory.queuedForRetirement(
            MANAGED_UNIFORM_BUFFER,
            3
        );
        inventory.queuedForRetirement(MATERIAL_SAMPLER);
        ShaderResourceInventory.Snapshot queued =
            inventory.snapshot();
        assertEquals(
            0,
            queued.active(MANAGED_UNIFORM_BUFFER)
        );
        assertEquals(
            3,
            queued.retiring(MANAGED_UNIFORM_BUFFER)
        );
        assertEquals(1, queued.retiring(MATERIAL_SAMPLER));

        inventory.advanceFrame();
        inventory.advanceFrame();
        assertEquals(
            3,
            inventory
                .snapshot()
                .retiring(MANAGED_UNIFORM_BUFFER)
        );
        inventory.advanceFrame();

        ShaderResourceInventory.Snapshot retired =
            inventory.snapshot();
        assertEquals(
            0,
            retired.current(MANAGED_UNIFORM_BUFFER)
        );
        assertEquals(0, retired.current(MATERIAL_SAMPLER));
        assertEquals(
            3L,
            retired.destroyed(MANAGED_UNIFORM_BUFFER)
        );
        assertEquals(1L, retired.destroyed(MATERIAL_SAMPLER));
    }

    @Test
    void confirmedEncoderDrainCompletesAllRetirements() {
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        inventory.created(MATERIAL_SAMPLER, 2);
        inventory.queuedForRetirement(MATERIAL_SAMPLER, 2);

        assertEquals(2, inventory.completeGpuRetirements());
        ShaderResourceInventory.Snapshot snapshot =
            inventory.snapshot();
        assertEquals(0, snapshot.current(MATERIAL_SAMPLER));
        assertEquals(2L, snapshot.destroyed(MATERIAL_SAMPLER));
    }

    @Test
    void cleanupFailureAndUnderflowStayVisible() {
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        inventory.created(COMPUTE_PIPELINE);
        inventory.cleanupFailed(COMPUTE_PIPELINE);
        inventory.destroyed(COMPUTE_PIPELINE, 2);

        ShaderResourceInventory.Snapshot snapshot =
            inventory.snapshot();
        assertEquals(1, snapshot.current(COMPUTE_PIPELINE));
        assertEquals(1L, snapshot.cleanupFailures(COMPUTE_PIPELINE));
        assertEquals(1L, snapshot.integrityErrors());
    }

    @Test
    void closeReportsOutstandingOwnershipAsLeaks() {
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        inventory.created(SHADER_MODULE);
        assertFalse(inventory.closeAndReport());

        ShaderResourceInventory.Snapshot snapshot =
            inventory.snapshot();
        assertTrue(snapshot.closed());
        assertEquals(1L, snapshot.leaks());
        assertFalse(snapshot.currentTotal() == 0);
        assertFalse(inventory.closeAndReport());
    }

    @Test
    void cleanCloseIsIdempotentButCleanupErrorsRemainUnclean() {
        ShaderResourceInventory clean =
            new ShaderResourceInventory();
        assertTrue(clean.closeAndReport());
        assertTrue(clean.closeAndReport());

        ShaderResourceInventory failed =
            new ShaderResourceInventory();
        failed.cleanupFailed(MATERIAL_SAMPLER);
        assertFalse(failed.closeAndReport());
        assertEquals(
            1L,
            failed.snapshot().cleanupFailuresTotal()
        );
    }
}
