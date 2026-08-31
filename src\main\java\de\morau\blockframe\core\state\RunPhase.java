package de.morau.blockframe.core.state;

/** Persisted lifecycle phase for one BlockFrame client run. */
public enum RunPhase {
    STARTING,
    INITIALIZING,
    STABLE,
    FAILED,
    UNCLEAN,
    CLEAN_SHUTDOWN
}
