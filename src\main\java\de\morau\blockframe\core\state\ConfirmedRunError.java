package de.morau.blockframe.core.state;

/**
 * Bounded confirmed failure classification.
 *
 * <p>An unclean process exit is deliberately not a confirmed error.</p>
 */
public enum ConfirmedRunError {
    NONE,
    BLOCKFRAME_ERROR,
    DEVICE_LOSS
}
