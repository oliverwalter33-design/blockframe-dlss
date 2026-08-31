package de.morau.nvidiadlss.nativebridge;

/**
 * Per-feature result returned by one Streamline frame-token evaluation.
 *
 * <p>The high 32 bits contain the signed DLSS result and the low 32 bits
 * contain the signed NIS result. Keeping both results prevents a later NIS
 * failure from hiding a successful DLSS command buffer.
 */
public record StreamlineEvaluationResult(int dlssResult, int nisResult) {
    public static final int NIS_NOT_REQUESTED = Integer.MIN_VALUE;
    public static final int NOT_EVALUATED = Integer.MIN_VALUE;
    public static final int NIS_COMMAND_BUFFER_FAILURE = -1214;

    public static StreamlineEvaluationResult unpack(long packed) {
        return new StreamlineEvaluationResult(
            (int) (packed >> 32),
            (int) packed
        );
    }

    public static long pack(int dlssResult, int nisResult) {
        return ((long) dlssResult << 32)
            | Integer.toUnsignedLong(nisResult);
    }

    public static StreamlineEvaluationResult notEvaluated() {
        return new StreamlineEvaluationResult(
            NOT_EVALUATED,
            NIS_NOT_REQUESTED
        );
    }

    public boolean dlssSucceeded() {
        return this.dlssResult == 0;
    }

    public boolean nisRequested() {
        return this.nisResult != NIS_NOT_REQUESTED;
    }

    public boolean nisSucceeded() {
        return this.nisRequested() && this.nisResult == 0;
    }
}
