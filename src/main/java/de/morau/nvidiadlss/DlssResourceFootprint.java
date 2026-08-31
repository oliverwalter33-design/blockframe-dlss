package de.morau.nvidiadlss;

/**
 * Checked logical footprint of the complete DLSS world target plus all
 * auxiliary images. Committed bytes include conservative 64-KiB image
 * alignment; they are not presented as driver-reported physical usage.
 */
record DlssResourceFootprint(long requestedBytes, long committedBytes) {
    private static final long IMAGE_ALIGNMENT = 64L * 1024L;

    static DlssResourceFootprint forDimensions(
        int lowWidth,
        int lowHeight,
        int outputWidth,
        int outputHeight,
        boolean developerDiagnostics
    ) {
        if (
            lowWidth <= 0
                || lowHeight <= 0
                || outputWidth <= 0
                || outputHeight <= 0
        ) {
            throw new IllegalArgumentException(
                "DLSS resource dimensions must be positive"
            );
        }

        long lowPixels = Math.multiplyExact(
            (long)lowWidth,
            (long)lowHeight
        );
        long outputPixels = Math.multiplyExact(
            (long)outputWidth,
            (long)outputHeight
        );
        long requested = 0L;
        long committed = 0L;

        // Low world color/depth, dense RG16F motion and the supported
        // transparency/composition hint: 16 bytes per render pixel. The
        // rejected depth-history gate and unsupported history-bias image are not
        // allocated by the normal or diagnostic path.
        for (int index = 0; index < 4; index++) {
            long imageBytes = Math.multiplyExact(lowPixels, 4L);
            requested = Math.addExact(requested, imageBytes);
            committed = Math.addExact(
                committed,
                align(imageBytes, IMAGE_ALIGNMENT)
            );
        }
        // Depth/motion visualization and R8_UINT classification exist only
        // in an explicitly enabled developer process. The release descriptor
        // layout has no debug bindings and therefore needs no dummy images.
        if (developerDiagnostics) {
            for (int index = 0; index < 2; index++) {
                long imageBytes = Math.multiplyExact(lowPixels, 4L);
                requested = Math.addExact(requested, imageBytes);
                committed = Math.addExact(
                    committed,
                    align(imageBytes, IMAGE_ALIGNMENT)
                );
            }
            requested = Math.addExact(requested, lowPixels);
            committed = Math.addExact(
                committed,
                align(lowPixels, IMAGE_ALIGNMENT)
            );
        }
        // DLSS output plus optional NVSharpen output.
        for (int index = 0; index < 2; index++) {
            long imageBytes = Math.multiplyExact(outputPixels, 4L);
            requested = Math.addExact(requested, imageBytes);
            committed = Math.addExact(
                committed,
                align(imageBytes, IMAGE_ALIGNMENT)
            );
        }
        return new DlssResourceFootprint(requested, committed);
    }

    private static long align(long value, long alignment) {
        long mask = alignment - 1L;
        return Math.addExact(value, mask) & ~mask;
    }
}
