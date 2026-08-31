package de.morau.blockframe.core.state;

/** The strongest publication guarantee actually achieved by this process. */
public enum RunStatePublicationMode {
    NONE,
    ATOMIC,
    RECOVERABLE_TWO_SLOT
}
