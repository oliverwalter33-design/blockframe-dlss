package de.morau.blockframe.core.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunStateCodecTest {
    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void fullRecordRoundTripsAsCanonicalUtf8Lf() throws Exception {
        UUID previousId = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
        );
        UUID lkgId = UUID.fromString(
            "20000000-0000-0000-0000-000000000002"
        );
        UUID failureId = UUID.fromString(
            "30000000-0000-0000-0000-000000000003"
        );
        RunStateRecord record = new RunStateRecord(
            1,
            1,
            UUID.fromString("40000000-0000-0000-0000-000000000004"),
            9L,
            27L,
            "1.2.3-test",
            "1.21.8",
            RunBackend.VULKAN,
            FINGERPRINT,
            1,
            0xffL,
            0x7fL,
            RunPhase.INITIALIZING,
            RunCheckpoint.FIRST_WORLD_FRAME,
            false,
            ConfirmedRunError.NONE,
            RunStateRecord.NO_CONTEXT,
            new RunStateRecord.PreviousRun(
                previousId,
                8L,
                RunPhase.FAILED,
                true,
                ConfirmedRunError.DEVICE_LOSS,
                "vk.device_lost"
            ),
            new RunStateRecord.LastKnownGood(
                lkgId,
                7L,
                22L,
                "1.2.3-test",
                "1.21.8",
                RunBackend.VULKAN,
                FINGERPRINT,
                1,
                0xffL,
                0x3fL,
                RunCheckpoint.STABILITY_WINDOW_COMPLETE
            ),
            new RunStateRecord.ConfirmedFailure(
                failureId,
                8L,
                ConfirmedRunError.DEVICE_LOSS,
                "vk.device_lost"
            ),
            new RunStateRecord.SafeStartState(
                previousId,
                previousId,
                null,
                previousId,
                previousId,
                true
            )
        );

        byte[] encoded = RunStateCodec.encode(record);
        String text = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\n"));
        assertFalse(text.contains("\r"));
        assertTrue(text.contains("checksum="));
        assertTrue(encoded.length < RunStateCodec.MAX_BYTES);
        assertEquals(record, RunStateCodec.decode(encoded));
        assertArrayEquals(encoded, RunStateCodec.encode(record));
    }

    @Test
    void failedOutcomeMayRetainIndependentCleanMarker() throws Exception {
        RunStateRecord failed = record(
            RunPhase.FAILED,
            RunCheckpoint.CLIENT_SHUTDOWN,
            true,
            ConfirmedRunError.BLOCKFRAME_ERROR,
            "renderer.init"
        );
        RunStateRecord decoded = RunStateCodec.decode(
            RunStateCodec.encode(failed)
        );
        assertEquals(RunPhase.FAILED, decoded.phase());
        assertTrue(decoded.cleanShutdown());
        assertEquals(
            ConfirmedRunError.BLOCKFRAME_ERROR,
            decoded.currentError()
        );
    }

    @Test
    void checksumTruncationUtf8AndSizeBoundsFailClosed() {
        byte[] valid = RunStateCodec.encode(
            record(
                RunPhase.STARTING,
                RunCheckpoint.PROCESS_STARTED,
                false,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT
            )
        );
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length / 2] ^= 1;
        assertThrows(
            RunStateCodec.InvalidFormatException.class,
            () -> RunStateCodec.decode(corrupt)
        );
        assertThrows(
            RunStateCodec.InvalidFormatException.class,
            () -> RunStateCodec.decode(
                java.util.Arrays.copyOf(valid, valid.length - 1)
            )
        );
        assertThrows(
            RunStateCodec.InvalidFormatException.class,
            () -> RunStateCodec.decode(new byte[] {(byte) 0xc3, 0x28})
        );
        assertThrows(
            RunStateCodec.InvalidFormatException.class,
            () -> RunStateCodec.decode(
                new byte[RunStateCodec.MAX_BYTES + 1]
            )
        );
    }

    @Test
    void futureSchemaAndWriterAreDistinguishedBeforeOverwrite() {
        assertThrows(
            RunStateCodec.FutureFormatException.class,
            () -> RunStateCodec.decode(
                (
                    "magic=blockframe-run-state\n"
                        + "schema-version=2\n"
                        + "writer-version=1\n"
                ).getBytes(StandardCharsets.UTF_8)
            )
        );
        assertThrows(
            RunStateCodec.FutureFormatException.class,
            () -> RunStateCodec.decode(
                (
                    "magic=blockframe-run-state\n"
                        + "schema-version=1\n"
                        + "writer-version=2\n"
                ).getBytes(StandardCharsets.UTF_8)
            )
        );
    }

    @Test
    void nonCanonicalNumbersBooleansAndCrlfAreRejected() {
        byte[] valid = RunStateCodec.encode(
            record(
                RunPhase.STARTING,
                RunCheckpoint.PROCESS_STARTED,
                false,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT
            )
        );
        String text = new String(valid, StandardCharsets.UTF_8);
        assertThrows(
            IOException.class,
            () -> RunStateCodec.decode(
                text.replace(
                    "run-generation=1\n",
                    "run-generation=01\n"
                ).getBytes(StandardCharsets.UTF_8)
            )
        );
        assertThrows(
            IOException.class,
            () -> RunStateCodec.decode(
                text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)
            )
        );
    }

    @Test
    void modelHasNoPathThrowableOrUnboundedTextField() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new RunStateIdentity(
                "C:\\Users\\someone\\mod.jar",
                "1.21.8",
                FINGERPRINT,
                1L,
                1L,
                0L
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> record(
                RunPhase.FAILED,
                RunCheckpoint.FAILURE_RECORDED,
                false,
                ConfirmedRunError.BLOCKFRAME_ERROR,
                "java.lang.IllegalStateException: absolute C:\\secret"
            )
        );
    }

    @Test
    void semanticFingerprintSortsKeysAndBoundsInput() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("z", "last");
        first.put("a", "first");
        assertEquals(
            RunStateFingerprint.sha256Canonical(first),
            RunStateFingerprint.sha256Canonical(
                Map.of("a", "first", "z", "last")
            )
        );
        assertEquals(
            64,
            RunStateFingerprint.sha256Canonical(first).length()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> RunStateFingerprint.sha256Canonical(
                Map.of("bad", "line\nbreak")
            )
        );
    }

    private static RunStateRecord record(
        RunPhase phase,
        RunCheckpoint checkpoint,
        boolean clean,
        ConfirmedRunError error,
        String context
    ) {
        RunStateRecord.ConfirmedFailure failure =
            error == ConfirmedRunError.NONE
                ? null
                : new RunStateRecord.ConfirmedFailure(
                    UUID.fromString(
                        "50000000-0000-0000-0000-000000000005"
                    ),
                    1L,
                    error,
                    context
                );
        return new RunStateRecord(
            1,
            1,
            UUID.fromString("50000000-0000-0000-0000-000000000005"),
            1L,
            1L,
            "1.0.0",
            "1.21.8",
            RunBackend.UNKNOWN,
            FINGERPRINT,
            1,
            0xffL,
            0x7fL,
            phase,
            checkpoint,
            clean,
            error,
            context,
            null,
            null,
            failure,
            RunStateRecord.SafeStartState.empty()
        );
    }
}
