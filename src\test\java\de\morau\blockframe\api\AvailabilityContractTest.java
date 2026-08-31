package de.morau.blockframe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityContractTest {
    @Test
    void unavailableAndDegradedStatesRequireReasons() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new Availability(Availability.State.UNAVAILABLE, List.of())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Availability(Availability.State.DEGRADED, List.of())
        );
    }

    @Test
    void onlyUnavailableStateIsNotUsable() {
        Availability degraded = Availability.degraded(new Reason("PARTIAL", "A safe subset is available"));
        Availability unavailable = Availability.unavailable(new Reason("MISSING", "Required support is absent"));

        assertTrue(Availability.available().usable());
        assertTrue(degraded.usable());
        assertFalse(unavailable.usable());
        assertEquals("MISSING", unavailable.reasons().getFirst().code());
    }

    @Test
    void providerIdsAreNamespacedAndStable() {
        ProviderId id = new ProviderId("blockframe:shader/native");
        assertEquals("blockframe", id.namespace());
        assertEquals("shader/native", id.path());
        assertThrows(IllegalArgumentException.class, () -> new ProviderId("NativeShaderBridge"));
    }
}
