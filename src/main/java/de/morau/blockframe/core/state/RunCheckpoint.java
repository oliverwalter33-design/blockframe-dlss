package de.morau.blockframe.core.state;

/** Sparse lifecycle checkpoints. They are never advanced from a frame poll. */
public enum RunCheckpoint {
    PROCESS_STARTED,
    BACKEND_INITIALIZED,
    ACTIVE_FEATURES_PUBLISHED,
    FIRST_WORLD_FRAME,
    STABILITY_WINDOW_COMPLETE,
    FAILURE_RECORDED,
    CLIENT_SHUTDOWN
}
