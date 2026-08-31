package de.morau.blockframe.core.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict, canonical, checksummed UTF-8/LF run-state codec. */
public final class RunStateCodec {
    public static final int MAX_BYTES = 64 * 1024;

    private static final String MAGIC = "blockframe-run-state";
    private static final String ABSENT = "none";
    private static final String ZERO_DIGEST = "0".repeat(64);
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> DATA_KEYS = List.of(
        "magic",
        "schema-version",
        "writer-version",
        "run-id",
        "run-generation",
        "commit-generation",
        "mod-version",
        "minecraft-version",
        "backend",
        "config-fingerprint",
        "feature-schema-version",
        "requested-feature-mask",
        "effective-feature-mask",
        "phase",
        "checkpoint",
        "clean-shutdown",
        "current-error",
        "current-error-context",
        "previous-present",
        "previous-run-id",
        "previous-run-generation",
        "previous-phase",
        "previous-clean-shutdown",
        "previous-error",
        "previous-error-context",
        "lkg-present",
        "lkg-run-id",
        "lkg-run-generation",
        "lkg-commit-generation",
        "lkg-mod-version",
        "lkg-minecraft-version",
        "lkg-backend",
        "lkg-config-fingerprint",
        "lkg-feature-schema-version",
        "lkg-requested-feature-mask",
        "lkg-effective-feature-mask",
        "lkg-checkpoint",
        "failure-present",
        "failure-run-id",
        "failure-run-generation",
        "failure-error",
        "failure-error-context",
        "safe-candidate-event",
        "safe-offered-event",
        "safe-declined-event",
        "safe-queued-event",
        "safe-consumed-event",
        "safe-active"
    );

    private RunStateCodec() {
    }

    public static byte[] encode(RunStateRecord record) {
        StringBuilder body = new StringBuilder(2_048);
        append(body, "magic", MAGIC);
        append(body, "schema-version", record.schemaVersion());
        append(body, "writer-version", record.writerVersion());
        append(body, "run-id", record.runId());
        append(body, "run-generation", record.runGeneration());
        append(body, "commit-generation", record.commitGeneration());
        append(body, "mod-version", record.modVersion());
        append(body, "minecraft-version", record.minecraftVersion());
        append(body, "backend", record.backend());
        append(body, "config-fingerprint", record.configFingerprint());
        append(body, "feature-schema-version", record.featureSchemaVersion());
        appendUnsigned(
            body,
            "requested-feature-mask",
            record.requestedFeatureMask()
        );
        appendUnsigned(
            body,
            "effective-feature-mask",
            record.effectiveFeatureMask()
        );
        append(body, "phase", record.phase());
        append(body, "checkpoint", record.checkpoint());
        append(body, "clean-shutdown", record.cleanShutdown());
        append(body, "current-error", record.currentError());
        append(body, "current-error-context", record.currentErrorContext());

        RunStateRecord.PreviousRun previous = record.previousRun();
        append(body, "previous-present", previous != null);
        append(
            body,
            "previous-run-id",
            previous == null ? ABSENT : previous.runId()
        );
        append(
            body,
            "previous-run-generation",
            previous == null ? 0L : previous.runGeneration()
        );
        append(
            body,
            "previous-phase",
            previous == null ? RunPhase.STARTING : previous.phase()
        );
        append(
            body,
            "previous-clean-shutdown",
            previous != null && previous.cleanShutdown()
        );
        append(
            body,
            "previous-error",
            previous == null ? ConfirmedRunError.NONE : previous.error()
        );
        append(
            body,
            "previous-error-context",
            previous == null
                ? RunStateRecord.NO_CONTEXT
                : previous.errorContext()
        );

        RunStateRecord.LastKnownGood lkg = record.lastKnownGood();
        append(body, "lkg-present", lkg != null);
        append(body, "lkg-run-id", lkg == null ? ABSENT : lkg.runId());
        append(
            body,
            "lkg-run-generation",
            lkg == null ? 0L : lkg.runGeneration()
        );
        append(
            body,
            "lkg-commit-generation",
            lkg == null ? 0L : lkg.commitGeneration()
        );
        append(body, "lkg-mod-version", lkg == null ? ABSENT : lkg.modVersion());
        append(
            body,
            "lkg-minecraft-version",
            lkg == null ? ABSENT : lkg.minecraftVersion()
        );
        append(
            body,
            "lkg-backend",
            lkg == null ? RunBackend.UNKNOWN : lkg.backend()
        );
        append(
            body,
            "lkg-config-fingerprint",
            lkg == null ? ZERO_DIGEST : lkg.configFingerprint()
        );
        append(
            body,
            "lkg-feature-schema-version",
            lkg == null ? 0 : lkg.featureSchemaVersion()
        );
        appendUnsigned(
            body,
            "lkg-requested-feature-mask",
            lkg == null ? 0L : lkg.requestedFeatureMask()
        );
        appendUnsigned(
            body,
            "lkg-effective-feature-mask",
            lkg == null ? 0L : lkg.effectiveFeatureMask()
        );
        append(
            body,
            "lkg-checkpoint",
            lkg == null
                ? RunCheckpoint.PROCESS_STARTED
                : lkg.checkpoint()
        );

        RunStateRecord.ConfirmedFailure failure =
            record.lastConfirmedFailure();
        append(body, "failure-present", failure != null);
        append(
            body,
            "failure-run-id",
            failure == null ? ABSENT : failure.runId()
        );
        append(
            body,
            "failure-run-generation",
            failure == null ? 0L : failure.runGeneration()
        );
        append(
            body,
            "failure-error",
            failure == null ? ConfirmedRunError.NONE : failure.error()
        );
        append(
            body,
            "failure-error-context",
            failure == null
                ? RunStateRecord.NO_CONTEXT
                : failure.errorContext()
        );

        RunStateRecord.SafeStartState safe = record.safeStart();
        append(
            body,
            "safe-candidate-event",
            nullableUuid(safe.candidateEvent())
        );
        append(body, "safe-offered-event", nullableUuid(safe.offeredEvent()));
        append(
            body,
            "safe-declined-event",
            nullableUuid(safe.declinedEvent())
        );
        append(body, "safe-queued-event", nullableUuid(safe.queuedEvent()));
        append(body, "safe-consumed-event", nullableUuid(safe.consumedEvent()));
        append(body, "safe-active", safe.active());

        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(bodyBytes);
        byte[] encoded = (
            body + "checksum=" + checksum + "\n"
        ).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                "encoded run state exceeds " + MAX_BYTES + " bytes"
            );
        }
        return encoded;
    }

    public static RunStateRecord decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0) {
            throw new InvalidFormatException("run state is empty");
        }
        if (encoded.length > MAX_BYTES) {
            throw new InvalidFormatException("run state exceeds size limit");
        }

        String text = decodeUtf8(encoded);
        if (!text.endsWith("\n") || text.indexOf('\r') >= 0) {
            throw new InvalidFormatException(
                "run state must use canonical LF termination"
            );
        }
        String[] lines = text.split("\n", -1);
        detectFutureVersion(lines);
        if (lines.length != DATA_KEYS.size() + 2) {
            throw new InvalidFormatException(
                "run state has an unexpected field count"
            );
        }

        Cursor cursor = new Cursor(lines);
        String magic = cursor.next("magic");
        if (!MAGIC.equals(magic)) {
            throw new InvalidFormatException("run-state magic mismatch");
        }
        int schema = cursor.positiveInt("schema-version");
        int writer = cursor.positiveInt("writer-version");
        UUID runId = cursor.uuid("run-id");
        long runGeneration = cursor.positiveLong("run-generation");
        long commitGeneration = cursor.positiveLong("commit-generation");
        String modVersion = cursor.next("mod-version");
        String minecraftVersion = cursor.next("minecraft-version");
        RunBackend backend = cursor.enumeration(
            "backend",
            RunBackend.class
        );
        String fingerprint = cursor.digest("config-fingerprint");
        int featureSchema = cursor.positiveInt("feature-schema-version");
        long requestedMask = cursor.unsignedLong("requested-feature-mask");
        long effectiveMask = cursor.unsignedLong("effective-feature-mask");
        RunPhase phase = cursor.enumeration("phase", RunPhase.class);
        RunCheckpoint checkpoint = cursor.enumeration(
            "checkpoint",
            RunCheckpoint.class
        );
        boolean clean = cursor.bool("clean-shutdown");
        ConfirmedRunError currentError = cursor.enumeration(
            "current-error",
            ConfirmedRunError.class
        );
        String currentContext = cursor.next("current-error-context");

        boolean previousPresent = cursor.bool("previous-present");
        String previousId = cursor.next("previous-run-id");
        long previousGeneration = cursor.nonNegativeLong(
            "previous-run-generation"
        );
        RunPhase previousPhase = cursor.enumeration(
            "previous-phase",
            RunPhase.class
        );
        boolean previousClean = cursor.bool("previous-clean-shutdown");
        ConfirmedRunError previousError = cursor.enumeration(
            "previous-error",
            ConfirmedRunError.class
        );
        String previousContext = cursor.next("previous-error-context");
        RunStateRecord.PreviousRun previous = null;
        if (previousPresent) {
            previous = new RunStateRecord.PreviousRun(
                parseUuid(previousId, "previous-run-id"),
                positive(previousGeneration, "previous-run-generation"),
                previousPhase,
                previousClean,
                previousError,
                previousContext
            );
        } else {
            requireAbsent(previousId, "previous-run-id");
            requireZero(previousGeneration, "previous-run-generation");
            requireEqual(
                previousPhase,
                RunPhase.STARTING,
                "previous-phase"
            );
            requireEqual(previousClean, false, "previous-clean-shutdown");
            requireEqual(
                previousError,
                ConfirmedRunError.NONE,
                "previous-error"
            );
            requireEqual(
                previousContext,
                RunStateRecord.NO_CONTEXT,
                "previous-error-context"
            );
        }

        boolean lkgPresent = cursor.bool("lkg-present");
        String lkgId = cursor.next("lkg-run-id");
        long lkgRunGeneration = cursor.nonNegativeLong("lkg-run-generation");
        long lkgCommitGeneration = cursor.nonNegativeLong(
            "lkg-commit-generation"
        );
        String lkgModVersion = cursor.next("lkg-mod-version");
        String lkgMinecraftVersion = cursor.next("lkg-minecraft-version");
        RunBackend lkgBackend = cursor.enumeration(
            "lkg-backend",
            RunBackend.class
        );
        String lkgFingerprint = cursor.digest("lkg-config-fingerprint");
        int lkgFeatureSchema = cursor.nonNegativeInt(
            "lkg-feature-schema-version"
        );
        long lkgRequestedMask = cursor.unsignedLong(
            "lkg-requested-feature-mask"
        );
        long lkgEffectiveMask = cursor.unsignedLong(
            "lkg-effective-feature-mask"
        );
        RunCheckpoint lkgCheckpoint = cursor.enumeration(
            "lkg-checkpoint",
            RunCheckpoint.class
        );
        RunStateRecord.LastKnownGood lkg = null;
        if (lkgPresent) {
            lkg = new RunStateRecord.LastKnownGood(
                parseUuid(lkgId, "lkg-run-id"),
                positive(lkgRunGeneration, "lkg-run-generation"),
                positive(lkgCommitGeneration, "lkg-commit-generation"),
                lkgModVersion,
                lkgMinecraftVersion,
                lkgBackend,
                lkgFingerprint,
                positiveInt(lkgFeatureSchema, "lkg-feature-schema-version"),
                lkgRequestedMask,
                lkgEffectiveMask,
                lkgCheckpoint
            );
        } else {
            requireAbsent(lkgId, "lkg-run-id");
            requireZero(lkgRunGeneration, "lkg-run-generation");
            requireZero(lkgCommitGeneration, "lkg-commit-generation");
            requireEqual(lkgModVersion, ABSENT, "lkg-mod-version");
            requireEqual(
                lkgMinecraftVersion,
                ABSENT,
                "lkg-minecraft-version"
            );
            requireEqual(lkgBackend, RunBackend.UNKNOWN, "lkg-backend");
            requireEqual(
                lkgFingerprint,
                ZERO_DIGEST,
                "lkg-config-fingerprint"
            );
            requireZero(lkgFeatureSchema, "lkg-feature-schema-version");
            requireZero(lkgRequestedMask, "lkg-requested-feature-mask");
            requireZero(lkgEffectiveMask, "lkg-effective-feature-mask");
            requireEqual(
                lkgCheckpoint,
                RunCheckpoint.PROCESS_STARTED,
                "lkg-checkpoint"
            );
        }

        boolean failurePresent = cursor.bool("failure-present");
        String failureId = cursor.next("failure-run-id");
        long failureGeneration = cursor.nonNegativeLong(
            "failure-run-generation"
        );
        ConfirmedRunError failureError = cursor.enumeration(
            "failure-error",
            ConfirmedRunError.class
        );
        String failureContext = cursor.next("failure-error-context");
        RunStateRecord.ConfirmedFailure failure = null;
        if (failurePresent) {
            failure = new RunStateRecord.ConfirmedFailure(
                parseUuid(failureId, "failure-run-id"),
                positive(failureGeneration, "failure-run-generation"),
                failureError,
                failureContext
            );
        } else {
            requireAbsent(failureId, "failure-run-id");
            requireZero(failureGeneration, "failure-run-generation");
            requireEqual(
                failureError,
                ConfirmedRunError.NONE,
                "failure-error"
            );
            requireEqual(
                failureContext,
                RunStateRecord.NO_CONTEXT,
                "failure-error-context"
            );
        }

        UUID candidate = cursor.nullableUuid("safe-candidate-event");
        UUID offered = cursor.nullableUuid("safe-offered-event");
        UUID declined = cursor.nullableUuid("safe-declined-event");
        UUID queued = cursor.nullableUuid("safe-queued-event");
        UUID consumed = cursor.nullableUuid("safe-consumed-event");
        boolean active = cursor.bool("safe-active");

        String checksum = cursor.next("checksum");
        if (!DIGEST.matcher(checksum).matches()) {
            throw new InvalidFormatException("checksum is not canonical");
        }
        if (cursor.index != lines.length - 1 || !lines[cursor.index].isEmpty()) {
            throw new InvalidFormatException("unexpected trailing data");
        }

        StringBuilder body = new StringBuilder(text.length());
        for (int index = 0; index < DATA_KEYS.size(); index++) {
            body.append(lines[index]).append('\n');
        }
        byte[] expectedChecksum = HexFormat.of().parseHex(checksum);
        byte[] actualChecksum = sha256Bytes(
            body.toString().getBytes(StandardCharsets.UTF_8)
        );
        if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
            throw new InvalidFormatException("checksum mismatch");
        }

        RunStateRecord record;
        try {
            record = new RunStateRecord(
                schema,
                writer,
                runId,
                runGeneration,
                commitGeneration,
                modVersion,
                minecraftVersion,
                backend,
                fingerprint,
                featureSchema,
                requestedMask,
                effectiveMask,
                phase,
                checkpoint,
                clean,
                currentError,
                currentContext,
                previous,
                lkg,
                failure,
                new RunStateRecord.SafeStartState(
                    candidate,
                    offered,
                    declined,
                    queued,
                    consumed,
                    active
                )
            );
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidFormatException(
                "run state violates the schema contract",
                invalid
            );
        }
        if (!Arrays.equals(encoded, encode(record))) {
            throw new InvalidFormatException(
                "run state is valid UTF-8 but not canonical"
            );
        }
        return record;
    }

    private static void detectFutureVersion(String[] lines)
        throws IOException {
        if (lines.length < 3) {
            return;
        }
        if (!("magic=" + MAGIC).equals(lines[0])) {
            return;
        }
        Integer schema = headerVersion(lines[1], "schema-version");
        Integer writer = headerVersion(lines[2], "writer-version");
        if (
            schema != null
                && schema > RunStateRecord.CURRENT_SCHEMA_VERSION
        ) {
            throw new FutureFormatException(
                "future run-state schema " + schema
            );
        }
        if (
            writer != null
                && writer > RunStateRecord.CURRENT_WRITER_VERSION
        ) {
            throw new FutureFormatException(
                "future run-state writer " + writer
            );
        }
        if (
            schema != null
                && schema != RunStateRecord.CURRENT_SCHEMA_VERSION
        ) {
            throw new InvalidFormatException(
                "unsupported run-state schema " + schema
            );
        }
        if (
            writer != null
                && writer != RunStateRecord.CURRENT_WRITER_VERSION
        ) {
            throw new InvalidFormatException(
                "unsupported run-state writer " + writer
            );
        }
    }

    private static Integer headerVersion(String line, String key) {
        String prefix = key + "=";
        if (!line.startsWith(prefix)) {
            return null;
        }
        try {
            return Integer.valueOf(line.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String decodeUtf8(byte[] encoded) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidFormatException(
                "run state is not strict UTF-8",
                exception
            );
        }
    }

    private static void append(
        StringBuilder target,
        String key,
        Object value
    ) {
        target.append(key).append('=').append(value).append('\n');
    }

    private static void appendUnsigned(
        StringBuilder target,
        String key,
        long value
    ) {
        append(target, key, Long.toUnsignedString(value));
    }

    private static String nullableUuid(UUID value) {
        return value == null ? ABSENT : value.toString();
    }

    private static UUID parseUuid(String value, String key)
        throws InvalidFormatException {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new InvalidFormatException(key + " is not a canonical UUID");
        }
    }

    private static long positive(long value, String key)
        throws InvalidFormatException {
        if (value <= 0L) {
            throw new InvalidFormatException(key + " must be positive");
        }
        return value;
    }

    private static int positiveInt(int value, String key)
        throws InvalidFormatException {
        if (value <= 0) {
            throw new InvalidFormatException(key + " must be positive");
        }
        return value;
    }

    private static void requireAbsent(String value, String key)
        throws InvalidFormatException {
        requireEqual(value, ABSENT, key);
    }

    private static void requireZero(long value, String key)
        throws InvalidFormatException {
        if (value != 0L) {
            throw new InvalidFormatException(
                key + " must use its absent sentinel"
            );
        }
    }

    private static void requireEqual(
        Object value,
        Object expected,
        String key
    ) throws InvalidFormatException {
        if (!ObjectsEqual.equals(value, expected)) {
            throw new InvalidFormatException(
                key + " must use its canonical absent sentinel"
            );
        }
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    private static byte[] sha256Bytes(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class Cursor {
        private final String[] lines;
        private int index;

        private Cursor(String[] lines) {
            this.lines = lines;
        }

        private String next(String key) throws InvalidFormatException {
            if (this.index >= this.lines.length - 1) {
                throw new InvalidFormatException("missing field " + key);
            }
            String prefix = key + "=";
            String line = this.lines[this.index++];
            if (!line.startsWith(prefix)) {
                throw new InvalidFormatException(
                    "expected field " + key
                );
            }
            String value = line.substring(prefix.length());
            if (
                value.isEmpty()
                    || value.indexOf('\0') >= 0
                    || value.length() > 512
            ) {
                throw new InvalidFormatException(
                    key + " is empty or exceeds its bound"
                );
            }
            return value;
        }

        private boolean bool(String key) throws InvalidFormatException {
            String value = this.next(key);
            if ("true".equals(value)) {
                return true;
            }
            if ("false".equals(value)) {
                return false;
            }
            throw new InvalidFormatException(key + " is not boolean");
        }

        private int positiveInt(String key) throws InvalidFormatException {
            return RunStateCodec.positiveInt(
                this.nonNegativeInt(key),
                key
            );
        }

        private int nonNegativeInt(String key)
            throws InvalidFormatException {
            String value = this.next(key);
            try {
                int parsed = Integer.parseInt(value);
                if (
                    parsed < 0
                        || !Integer.toString(parsed).equals(value)
                ) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new InvalidFormatException(
                    key + " is not a canonical non-negative integer"
                );
            }
        }

        private long positiveLong(String key)
            throws InvalidFormatException {
            return positive(this.nonNegativeLong(key), key);
        }

        private long nonNegativeLong(String key)
            throws InvalidFormatException {
            String value = this.next(key);
            try {
                long parsed = Long.parseLong(value);
                if (
                    parsed < 0L
                        || !Long.toString(parsed).equals(value)
                ) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new InvalidFormatException(
                    key + " is not a canonical non-negative long"
                );
            }
        }

        private long unsignedLong(String key)
            throws InvalidFormatException {
            String value = this.next(key);
            try {
                long parsed = Long.parseUnsignedLong(value);
                if (!Long.toUnsignedString(parsed).equals(value)) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new InvalidFormatException(
                    key + " is not a canonical unsigned long"
                );
            }
        }

        private UUID uuid(String key) throws InvalidFormatException {
            return parseUuid(this.next(key), key);
        }

        private UUID nullableUuid(String key)
            throws InvalidFormatException {
            String value = this.next(key);
            return ABSENT.equals(value) ? null : parseUuid(value, key);
        }

        private String digest(String key) throws InvalidFormatException {
            String value = this.next(key);
            if (!DIGEST.matcher(value).matches()) {
                throw new InvalidFormatException(
                    key + " is not a lowercase SHA-256 digest"
                );
            }
            return value;
        }

        private <E extends Enum<E>> E enumeration(
            String key,
            Class<E> type
        ) throws InvalidFormatException {
            String value = this.next(key);
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException exception) {
                throw new InvalidFormatException(
                    key + " has an unknown value"
                );
            }
        }
    }

    private static final class ObjectsEqual {
        private ObjectsEqual() {
        }

        private static boolean equals(Object first, Object second) {
            return first == null ? second == null : first.equals(second);
        }
    }

    public static class InvalidFormatException extends IOException {
        public InvalidFormatException(String message) {
            super(message);
        }

        public InvalidFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class FutureFormatException
        extends InvalidFormatException {
        public FutureFormatException(String message) {
            super(message);
        }
    }
}
