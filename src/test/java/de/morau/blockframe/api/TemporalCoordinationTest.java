package de.morau.blockframe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TemporalCoordinationTest {
    @Test
    void blockframeTemporalPathExclusivelyOwnsJitter() {
        TemporalCoordination.Decision decision =
            TemporalCoordination.negotiate(TemporalCoordination.Request.blockframe());

        assertTrue(decision.accepted());
        assertEquals(TemporalCoordination.Owner.BLOCKFRAME_TEMPORAL_UPSCALER, decision.temporalOwner());
        assertEquals(decision.temporalOwner(), decision.jitterOwner());
    }

    @Test
    void shaderPackTemporalPathExclusivelyOwnsJitter() {
        TemporalCoordination.Decision decision =
            TemporalCoordination.negotiate(TemporalCoordination.Request.shaderPack());

        assertTrue(decision.accepted());
        assertEquals(TemporalCoordination.Owner.SHADER_PACK, decision.temporalOwner());
        assertEquals(decision.temporalOwner(), decision.jitterOwner());
    }

    @Test
    void doubleTemporalAaFailsClosed() {
        TemporalCoordination.Decision decision = TemporalCoordination.negotiate(
            new TemporalCoordination.Request(
                true,
                true,
                TemporalCoordination.Owner.BLOCKFRAME_TEMPORAL_UPSCALER
            )
        );

        assertFalse(decision.accepted());
        assertEquals(TemporalCoordination.Owner.NONE, decision.temporalOwner());
        assertEquals(TemporalCoordination.Owner.NONE, decision.jitterOwner());
        assertEquals(
            TemporalCoordination.DOUBLE_TEMPORAL_AA,
            decision.availability().reasons().getFirst().code()
        );
    }

    @Test
    void mismatchedJitterOwnerFailsClosed() {
        TemporalCoordination.Decision decision = TemporalCoordination.negotiate(
            new TemporalCoordination.Request(
                true,
                false,
                TemporalCoordination.Owner.SHADER_PACK
            )
        );

        assertFalse(decision.accepted());
        assertEquals(TemporalCoordination.Owner.NONE, decision.temporalOwner());
        assertEquals(TemporalCoordination.Owner.NONE, decision.jitterOwner());
    }
}
