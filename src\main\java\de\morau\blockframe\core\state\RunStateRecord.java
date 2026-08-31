package de.morau.blockframe.core.state;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable persisted run snapshot.
 *
 * <p>Only bounded stable identifiers are accepted. Paths, exception messages,
 * stack traces and vendor payloads have no field in this model.</p>
 */
public record RunStateRecord(
    int schemaVersion,
    int writerVersion,
    UUID runId,
    long runGeneration,
    long commitGeneration,
    String modVersion,
    String minecraftVersion,
    RunBackend backend,
    String configFingerprint,
    int featureSchemaVersion,
    long requestedFeatureMask,
    long effectiveFeatureMask,
    RunPhase phase,
    RunCheckpoint checkpoint,
    boolean cleanShutdown,
    ConfirmedRunError currentError,
    String currentErrorContext,
    PreviousRun previousRun,
    LastKnownGood lastKnownGood,
    ConfirmedFailure lastConfirmedFailure,
    SafeStartState safeStart
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int CURRENT_WRITER_VERSION = 1;
    public static final String NO_CONTEXT = "none";

    private static final Pattern VERSION =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CONTEXT =
        Pattern.compile("(?:none|[a-z0-9][a-z0-9._\\-]{0,63})");

    public RunStateRecord {
        if (schemaVersion <= 0 || writerVersion <= 0) {
            throw new IllegalArgumentException(
                "schema and writer versions must be positive"
            );
        }
        runId = Objects.requireNonNull(runId, "runId");
        positive(runGeneration, "runGeneration");
        positive(commitGeneration, "commitGeneration");
        modVersion = version(modVersion, "modVersion");
        minecraftVersion = version(minecraftVersion, "minecraftVersion");
        backend = Objects.requireNonNull(backend, "backend");
        configFingerprint = digest(
            configFingerprint,
            "configFingerprint"
        );
        if (featureSchemaVersion <= 0 || featureSchemaVersion > 65_535) {
            throw new IllegalArgumentException(
                "featureSchemaVersion must be in 1..65535"
            );
        }
        if ((effectiveFeatureMask & ~requestedFeatureMask) != 0L) {
            throw new IllegalArgumentException(
                "effectiveFeatureMask must be a subset of requestedFeatureMask"
            );
        }
        phase = Objects.requireNonNull(phase, "phase");
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        currentError = Objects.requireNonNull(currentError, "currentError");
        currentErrorContext = context(currentErrorContext);
        if (
            phase == RunPhase.CLEAN_SHUTDOWN && !cleanShutdown
                || cleanShutdown
                    && phase != RunPhase.CLEAN_SHUTDOWN
                    && phase != RunPhase.FAILED
        ) {
            throw new IllegalArgumentException(
                "clean marker is valid only for CLEAN_SHUTDOWN or FAILED"
            );
        }
        if (
            currentError == ConfirmedRunError.NONE
                && !NO_CONTEXT.equals(currentErrorContext)
        ) {
            throw new IllegalArgumentException(
                "a run without a confirmed error must use context 'none'"
            );
        }
        if (
            currentError != ConfirmedRunError.NONE
                && phase != RunPhase.FAILED
        ) {
            throw new IllegalArgumentException(
                "a confirmed current error requires FAILED phase"
            );
        }
        if (
            phase == RunPhase.FAILED
                && currentError == ConfirmedRunError.NONE
        ) {
            throw new IllegalArgumentException(
                "FAILED phase requires a confirmed error"
            );
        }
        if (
            currentError != ConfirmedRunError.NONE
                && NO_CONTEXT.equals(currentErrorContext)
        ) {
            throw new IllegalArgumentException(
                "a confirmed current error requires a stable context code"
            );
        }
        safeStart = Objects.requireNonNull(safeStart, "safeStart");
    }

    public boolean identityMatches(RunStateIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return this.modVersion.equals(identity.modVersion())
            && this.minecraftVersion.equals(identity.minecraftVersion())
            && this.configFingerprint.equals(identity.configFingerprint())
            && this.featureSchemaVersion == identity.featureSchemaVersion()
            && this.requestedFeatureMask
                == identity.requestedFeatureMask();
    }

    static String version(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (!VERSION.matcher(checked).matches()) {
            throw new IllegalArgumentException(
                label + " must be a bounded stable version token"
            );
        }
        return checked;
    }

    static String digest(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (!DIGEST.matcher(checked).matches()) {
            throw new IllegalArgumentException(
                label + " must be a lowercase SHA-256 digest"
            );
        }
        return checked;
    }

    static String context(String value) {
        String checked = Objects.requireNonNull(value, "errorContext");
        if (!CONTEXT.matcher(checked).matches()) {
            throw new IllegalArgumentException(
                "error context must be a bounded stable code"
            );
        }
        return checked;
    }

    static long positive(long value, String label) {
        if (value <= 0L) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    /** Summary of the immediately preceding run. */
    public record PreviousRun(
        UUID runId,
        long runGeneration,
        RunPhase phase,
        boolean cleanShutdown,
        ConfirmedRunError error,
        String errorContext
    ) {
        public PreviousRun {
            runId = Objects.requireNonNull(runId, "runId");
            positive(runGeneration, "previous runGeneration");
            phase = Objects.requireNonNull(phase, "phase");
            error = Objects.requireNonNull(error, "error");
            errorContext = context(errorContext);
            if (
                phase == RunPhase.CLEAN_SHUTDOWN && !cleanShutdown
                    || cleanShutdown
                        && phase != RunPhase.CLEAN_SHUTDOWN
                        && phase != RunPhase.FAILED
            ) {
                throw new IllegalArgumentException(
                    "previous clean marker is valid only for clean/failed runs"
                );
            }
            if (
                error == ConfirmedRunError.NONE
                    && !NO_CONTEXT.equals(errorContext)
            ) {
                throw new IllegalArgumentException(
                    "previous run without error must use context 'none'"
                );
            }
            if (error != ConfirmedRunError.NONE && phase != RunPhase.FAILED) {
                throw new IllegalArgumentException(
                    "previous confirmed error requires FAILED phase"
                );
            }
            if (
                phase == RunPhase.FAILED
                    && error == ConfirmedRunError.NONE
            ) {
                throw new IllegalArgumentException(
                    "previous FAILED phase requires a confirmed error"
                );
            }
            if (
                error != ConfirmedRunError.NONE
                    && NO_CONTEXT.equals(errorContext)
            ) {
                throw new IllegalArgumentException(
                    "previous confirmed error requires a context code"
                );
            }
        }
    }

    /** Embedded single-generation last-known-good snapshot. */
    public record LastKnownGood(
        UUID runId,
        long runGeneration,
        long commitGeneration,
        String modVersion,
        String minecraftVersion,
        RunBackend backend,
        String configFingerprint,
        int featureSchemaVersion,
        long requestedFeatureMask,
        long effectiveFeatureMask,
        RunCheckpoint checkpoint
    ) {
        public LastKnownGood {
            runId = Objects.requireNonNull(runId, "runId");
            positive(runGeneration, "LKG runGeneration");
            positive(commitGeneration, "LKG commitGeneration");
            modVersion = version(modVersion, "LKG modVersion");
            minecraftVersion = version(
                minecraftVersion,
                "LKG minecraftVersion"
            );
            backend = Objects.requireNonNull(backend, "backend");
            if (backend == RunBackend.UNKNOWN) {
                throw new IllegalArgumentException(
                    "LKG requires an initialized backend"
                );
            }
            configFingerprint = digest(
                configFingerprint,
                "LKG configFingerprint"
            );
            if (featureSchemaVersion <= 0 || featureSchemaVersion > 65_535) {
                throw new IllegalArgumentException(
                    "LKG featureSchemaVersion must be in 1..65535"
                );
            }
            if ((effectiveFeatureMask & ~requestedFeatureMask) != 0L) {
                throw new IllegalArgumentException(
                    "LKG effective mask must be a subset of requested mask"
                );
            }
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            if (checkpoint != RunCheckpoint.STABILITY_WINDOW_COMPLETE) {
                throw new IllegalArgumentException(
                    "LKG must represent the stability checkpoint"
                );
            }
        }
    }

    /** Most recently confirmed BlockFrame/device failure; success preserves it. */
    public record ConfirmedFailure(
        UUID runId,
        long runGeneration,
        ConfirmedRunError error,
        String errorContext
    ) {
        public ConfirmedFailure {
            runId = Objects.requireNonNull(runId, "runId");
            positive(runGeneration, "failure runGeneration");
            error = Objects.requireNonNull(error, "error");
            if (error == ConfirmedRunError.NONE) {
                throw new IllegalArgumentException(
                    "confirmed failure must have an error classification"
                );
            }
            errorContext = context(errorContext);
            if (NO_CONTEXT.equals(errorContext)) {
                throw new IllegalArgumentException(
                    "confirmed failure requires a stable context code"
                );
            }
        }
    }

    /** Persisted one-shot bookkeeping; UUIDs are event identities. */
    public record SafeStartState(
        UUID candidateEvent,
        UUID offeredEvent,
        UUID declinedEvent,
        UUID queuedEvent,
        UUID consumedEvent,
        boolean active
    ) {
        public SafeStartState {
            if (candidateEvent == null) {
                if (offeredEvent != null || declinedEvent != null) {
                    throw new IllegalArgumentException(
                        "offer/decline markers require a candidate event"
                    );
                }
            }
            if (
                declinedEvent != null
                    && !declinedEvent.equals(offeredEvent)
            ) {
                throw new IllegalArgumentException(
                    "decline marker requires the same offered event"
                );
            }
            if (
                candidateEvent != null
                    && candidateEvent.equals(declinedEvent)
                    && candidateEvent.equals(queuedEvent)
            ) {
                throw new IllegalArgumentException(
                    "a declined event cannot also be queued"
                );
            }
            if (
                active
                    && (candidateEvent == null
                        || !candidateEvent.equals(queuedEvent)
                        || !candidateEvent.equals(consumedEvent)
                        || candidateEvent.equals(declinedEvent))
            ) {
                throw new IllegalArgumentException(
                    "active Safe Start requires one queued/consumed candidate"
                );
            }
        }

        public static SafeStartState empty() {
            return new SafeStartState(
                null,
                null,
                null,
                null,
                null,
                false
            );
        }

        public boolean hasOffer() {
            return this.candidateEvent != null
                && !this.candidateEvent.equals(this.offeredEvent);
        }

        public boolean hasQueuedOneShot() {
            return this.queuedEvent != null
                && !this.queuedEvent.equals(this.consumedEvent);
        }

        public boolean isDeclined() {
            return this.candidateEvent != null
                && this.candidateEvent.equals(this.declinedEvent);
        }
    }
}
