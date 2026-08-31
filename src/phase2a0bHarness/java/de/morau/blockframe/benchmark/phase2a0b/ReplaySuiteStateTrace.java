package de.morau.blockframe.benchmark.phase2a0b;

import java.util.Arrays;

/**
 * Explicit per-scene transition owner. A completed scene may advance only to
 * the next scene's fresh PREFLIGHT; a failure terminates the complete suite.
 */
public final class ReplaySuiteStateTrace {
    private static final int SUCCESS_STATE_COUNT = 7;
    private final String[] sceneIds;
    private final Phase2a0bContracts.SceneType[] sceneTypes;
    private final BenchmarkState[][] traces;
    private final int[] traceSizes;
    private int sceneIndex;
    private BenchmarkState state = BenchmarkState.PREFLIGHT;

    public ReplaySuiteStateTrace(
        String[] sceneIds,
        Phase2a0bContracts.SceneType[] sceneTypes
    ) {
        if (sceneIds == null || sceneIds.length == 0) {
            throw new IllegalArgumentException("suite requires scenes");
        }
        if (sceneTypes == null || sceneTypes.length != sceneIds.length) {
            throw new IllegalArgumentException(
                "suite scene types must match scenes"
            );
        }
        this.sceneIds = sceneIds.clone();
        this.sceneTypes = sceneTypes.clone();
        this.traces =
            new BenchmarkState[sceneIds.length][SUCCESS_STATE_COUNT + 1];
        this.traceSizes = new int[sceneIds.length];
        record(BenchmarkState.PREFLIGHT);
    }

    public BenchmarkState state() {
        return this.state;
    }

    public int sceneIndex() {
        return this.sceneIndex;
    }

    public int sceneCount() {
        return this.sceneIds.length;
    }

    public String sceneId() {
        return this.sceneIds[this.sceneIndex];
    }

    public void transition(BenchmarkState next) {
        if (next == null) {
            throw new NullPointerException("next");
        }
        if (next == BenchmarkState.FAILED) {
            if (this.state == BenchmarkState.FAILED) {
                throw new IllegalStateException(
                    "terminal suite cannot fail again"
                );
            }
            this.state = next;
            record(next);
            return;
        }
        BenchmarkState expected = switch (this.state) {
            case PREFLIGHT -> BenchmarkState.WORLD_WAIT;
            case WORLD_WAIT -> BenchmarkState.CHUNK_WARMUP;
            case CHUNK_WARMUP -> BenchmarkState.WARMUP;
            case WARMUP ->
                this.sceneTypes[this.sceneIndex]
                        == Phase2a0bContracts.SceneType.PERFORMANCE
                    ? BenchmarkState.MEASURE
                    : BenchmarkState.REFERENCE_CAPTURE;
            case MEASURE -> BenchmarkState.REFERENCE_CAPTURE;
            case REFERENCE_CAPTURE -> BenchmarkState.COMPLETE;
            case COMPLETE, FAILED -> null;
        };
        if (next != expected) {
            throw new IllegalStateException(
                "invalid state transition " + this.state + " -> " + next
            );
        }
        this.state = next;
        record(next);
    }

    /**
     * Advances after the current scene has reached COMPLETE. Returns false
     * when the complete suite is finished.
     */
    public boolean advanceScene() {
        if (this.state != BenchmarkState.COMPLETE) {
            throw new IllegalStateException(
                "scene must be COMPLETE before advancing"
            );
        }
        if (this.sceneIndex + 1 == this.sceneIds.length) {
            return false;
        }
        this.sceneIndex++;
        this.state = BenchmarkState.PREFLIGHT;
        record(BenchmarkState.PREFLIGHT);
        return true;
    }

    public BenchmarkState[] trace(int index) {
        if (index < 0 || index >= this.traces.length) {
            throw new IndexOutOfBoundsException(index);
        }
        return Arrays.copyOf(this.traces[index], this.traceSizes[index]);
    }

    private void record(BenchmarkState value) {
        int size = this.traceSizes[this.sceneIndex];
        if (size == this.traces[this.sceneIndex].length) {
            throw new IllegalStateException("state trace overflow");
        }
        this.traces[this.sceneIndex][size] = value;
        this.traceSizes[this.sceneIndex] = size + 1;
    }
}
