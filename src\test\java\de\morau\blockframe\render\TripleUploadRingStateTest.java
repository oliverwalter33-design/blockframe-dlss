package de.morau.blockframe.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TripleUploadRingStateTest {
    @Test
    void selectsManagedPathOnlyForEnabledPersistentMappingWithUsableCapacity() {
        assertTrue(TripleUploadRingState.shouldUseManagedPath(true, true, 2));
        assertFalse(TripleUploadRingState.shouldUseManagedPath(false, true, 1024));
        assertFalse(TripleUploadRingState.shouldUseManagedPath(true, false, 1024));
        assertFalse(TripleUploadRingState.shouldUseManagedPath(true, true, 1));
    }

    @Test
    void matchesMinecraftPersistentStagingCapacityPolicy() {
        assertEquals(512, TripleUploadRingState.capacityPerSlot(1024));
        assertEquals(512, TripleUploadRingState.capacityPerSlot(1025));
        assertThrows(IllegalArgumentException.class, () -> TripleUploadRingState.capacityPerSlot(1));
    }

    @Test
    void boundsEachSlotAndResetsUsageOnRotation() {
        TripleUploadRingState state = new TripleUploadRingState(64);

        state.recordAppend(24);
        state.recordAppend(40);

        assertEquals(64, state.usedBytes());
        assertEquals(64, state.peakUsedBytes());
        assertEquals(64, state.appendedBytes());
        assertThrows(IllegalStateException.class, () -> state.recordAppend(1));

        assertEquals(1, state.rotate());
        assertEquals(0, state.usedBytes());
        assertEquals(64, state.peakUsedBytes());
    }

    @Test
    void wrapsExactlyThreeSlotsAndMarksFirstReuse() {
        TripleUploadRingState state = new TripleUploadRingState(16);

        assertFalse(state.currentSlotIsBeingReused());
        assertEquals(1, state.rotate());
        assertFalse(state.currentSlotIsBeingReused());
        assertEquals(2, state.rotate());
        assertFalse(state.currentSlotIsBeingReused());
        assertEquals(0, state.rotate());
        assertTrue(state.currentSlotIsBeingReused());
        assertEquals(3, state.rotations());
    }

    @Test
    void tracksActualCopyVolumeSeparatelyFromAppends() {
        TripleUploadRingState state = new TripleUploadRingState(64);

        state.recordAppend(32);
        state.recordCopy(12);
        state.recordCopy(12);

        assertEquals(32, state.appendedBytes());
        assertEquals(24, state.copiedBytes());
        assertThrows(IllegalArgumentException.class, () -> state.recordCopy(-1));
    }
}
