package de.morau.nvidiadlss;

/**
 * Conservative close helper for resources that fail during construction.
 */
final class CreationRollback {
    private CreationRollback() {
    }

    /**
     * Returns {@code true} only when no owner remains to be retained.
     */
    static boolean close(
        AutoCloseable resource,
        Throwable creationFailure
    ) {
        if (resource == null) {
            return true;
        }
        try {
            resource.close();
            return true;
        } catch (Throwable closeFailure) {
            if (closeFailure != creationFailure) {
                creationFailure.addSuppressed(closeFailure);
            }
            return false;
        }
    }
}
