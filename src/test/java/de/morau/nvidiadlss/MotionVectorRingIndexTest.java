package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MotionVectorRingIndexTest {
    @Test
    void cyclesAllThreeSlotsWithoutUnboundedCounterGrowth() {
        int slot = 0;
        for (int iteration = 0; iteration < 1_000_000; iteration++) {
            assertEquals(
                iteration % 3,
                slot
            );
            slot = MotionVectorGenerator.nextFrameRingIndex(slot);
        }
        assertEquals(1, slot);
    }

    @Test
    void wrapsTheLastSlotDirectlyToZero() {
        assertEquals(1, MotionVectorGenerator.nextFrameRingIndex(0));
        assertEquals(2, MotionVectorGenerator.nextFrameRingIndex(1));
        assertEquals(0, MotionVectorGenerator.nextFrameRingIndex(2));
    }
}
