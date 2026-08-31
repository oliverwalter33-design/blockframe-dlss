package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReplaySuiteStateTraceTest {
    private static final BenchmarkState[] PERFORMANCE_EXPECTED = {
        BenchmarkState.PREFLIGHT,
        BenchmarkState.WORLD_WAIT,
        BenchmarkState.CHUNK_WARMUP,
        BenchmarkState.WARMUP,
        BenchmarkState.MEASURE,
        BenchmarkState.REFERENCE_CAPTURE,
        BenchmarkState.COMPLETE
    };
    private static final BenchmarkState[] IMAGE_EXPECTED = {
        BenchmarkState.PREFLIGHT,
        BenchmarkState.WORLD_WAIT,
        BenchmarkState.CHUNK_WARMUP,
        BenchmarkState.WARMUP,
        BenchmarkState.REFERENCE_CAPTURE,
        BenchmarkState.COMPLETE
    };

    @Test
    void recordsTheCompleteStateSequenceForAllFourScenes() {
        ReplaySuiteStateTrace trace = new ReplaySuiteStateTrace(
            new String[] {"dense", "poi", "chunk", "image"},
            new Phase2a0bContracts.SceneType[] {
                Phase2a0bContracts.SceneType.PERFORMANCE,
                Phase2a0bContracts.SceneType.PERFORMANCE,
                Phase2a0bContracts.SceneType.PERFORMANCE,
                Phase2a0bContracts.SceneType.IMAGE_REFERENCE
            }
        );
        for (int scene = 0; scene < 4; scene++) {
            trace.transition(BenchmarkState.WORLD_WAIT);
            trace.transition(BenchmarkState.CHUNK_WARMUP);
            trace.transition(BenchmarkState.WARMUP);
            if (scene < 3) {
                trace.transition(BenchmarkState.MEASURE);
            }
            trace.transition(BenchmarkState.REFERENCE_CAPTURE);
            trace.transition(BenchmarkState.COMPLETE);
            assertArrayEquals(
                scene < 3 ? PERFORMANCE_EXPECTED : IMAGE_EXPECTED,
                trace.trace(scene)
            );
            if (scene < 3) {
                assertTrue(trace.advanceScene());
            } else {
                assertFalse(trace.advanceScene());
            }
        }
    }

    @Test
    void rejectsSkippedStatesAndCannotCreateASecondOwnerImplicitly() {
        ReplaySuiteStateTrace trace =
            new ReplaySuiteStateTrace(
                new String[] {"only"},
                new Phase2a0bContracts.SceneType[] {
                    Phase2a0bContracts.SceneType.PERFORMANCE
                }
            );
        assertThrows(
            IllegalStateException.class,
            () -> trace.transition(BenchmarkState.MEASURE)
        );
    }

    @Test
    void imageReferenceRejectsAnArtificialMeasureState() {
        ReplaySuiteStateTrace trace =
            new ReplaySuiteStateTrace(
                new String[] {"image"},
                new Phase2a0bContracts.SceneType[] {
                    Phase2a0bContracts.SceneType.IMAGE_REFERENCE
                }
            );
        trace.transition(BenchmarkState.WORLD_WAIT);
        trace.transition(BenchmarkState.CHUNK_WARMUP);
        trace.transition(BenchmarkState.WARMUP);
        assertThrows(
            IllegalStateException.class,
            () -> trace.transition(BenchmarkState.MEASURE)
        );
    }
}
