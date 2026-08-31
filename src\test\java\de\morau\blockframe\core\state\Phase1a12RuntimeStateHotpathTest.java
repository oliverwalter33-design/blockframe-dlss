package de.morau.blockframe.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.morau.blockframe.core.EngineConfig;
import org.junit.jupiter.api.Test;

class Phase1a12RuntimeStateHotpathTest {
    @Test
    void warmedFeatureReadsKeepThePublishedSemanticState() {
        RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "quality",
            "heap",
            false
        );
        FeatureStateRegistry registry = new FeatureStateRegistry();
        policy.publishInitial(registry, 7L);

        long requestedMask = policy.requestedMask();
        long enabledMask = policy.enabledMask();
        FeatureStateRegistry.Snapshot snapshot =
            registry.snapshot();
        var debugLines = registry.debugLines();
        FeatureState[] states =
            new FeatureState[FeatureId.COUNT];
        for (FeatureId id : FeatureId.all()) {
            states[id.bitIndex()] = registry.state(id);
        }

        long checksum = 0L;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            FeatureId id = FeatureId.fromBitIndex(
                iteration % FeatureId.COUNT
            );
            checksum ^= policy.enabled(id) ? id.mask() : 0L;
            checksum ^= registry.state(id).id().mask();
            checksum ^= registry.snapshot().revision();
            checksum ^= registry.debugLines().get(id.bitIndex()).hashCode();
        }

        assertEquals(requestedMask, policy.requestedMask());
        assertEquals(enabledMask, policy.enabledMask());
        assertSame(snapshot, registry.snapshot());
        assertSame(debugLines, registry.debugLines());
        for (FeatureId id : FeatureId.all()) {
            assertSame(states[id.bitIndex()], registry.state(id));
        }
        assertEquals((long) FeatureId.COUNT, snapshot.revision());
        assertEquals(
            requestedMask,
            snapshot.requestedMask()
        );
        assertEquals(enabledMask, snapshot.enabledMask());
        assertNotEquals(
            0L,
            checksum,
            "the read workload must remain observable"
        );
    }

    @Test
    void warmedStableSameWorldReadStaysSaturatedWithoutTransition() {
        WorldFrameStabilityTracker tracker =
            new WorldFrameStabilityTracker(120);
        Object world = new Object();

        for (int frame = 1; frame <= 120; frame++) {
            tracker.observeSuccessfulFrame(world);
        }
        for (int frame = 0; frame < 10_000; frame++) {
            assertSame(
                WorldFrameStabilityTracker.Transition.NONE,
                tracker.observeSuccessfulFrame(world)
            );
        }

        assertEquals(120, tracker.consecutiveFrames());
        assertEquals(120, tracker.requiredFrames());
    }
}
