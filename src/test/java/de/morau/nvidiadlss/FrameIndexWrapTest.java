package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FrameIndexWrapTest {
    @Test
    void jitterUsesTheSameUnsigned32BitFrameAsStreamline() {
        int phases = 17;
        for (int frame : new int[] {
            0, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -2, -1
        }) {
            assertEquals(
                Integer.remainderUnsigned(frame, phases) + 1,
                DlssRenderer.jitterPhaseForFrame(frame, phases)
            );
        }
    }

    @Test
    void signedAndUnsignedWrapBoundariesStayInsideTheHaltonPeriod() {
        assertEquals(8, DlssRenderer.jitterPhaseForFrame(Integer.MAX_VALUE, 8));
        assertEquals(1, DlssRenderer.jitterPhaseForFrame(Integer.MIN_VALUE, 8));
        assertEquals(8, DlssRenderer.jitterPhaseForFrame(-1, 8));
        assertEquals(1, DlssRenderer.jitterPhaseForFrame(0, 8));
    }

    @Test
    void rejectsInvalidPhaseCounts() {
        assertThrows(IllegalArgumentException.class, () -> DlssRenderer.jitterPhaseForFrame(0, 0));
        assertThrows(IllegalArgumentException.class, () -> DlssRenderer.jitterPhaseForFrame(0, -1));
    }
}
