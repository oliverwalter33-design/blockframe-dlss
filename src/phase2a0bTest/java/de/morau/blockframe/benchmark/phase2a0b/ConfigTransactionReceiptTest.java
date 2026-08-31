package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTransactionReceiptTest {
    private static final String START =
        "3a78ec4a863e3e10c7bcf57179e57f2889953b0700681d49e6f2ece710e1f40d";
    private static final String APPLIED =
        "73f69b073351a236efdc9389adf3e1b40752703af409cc3b5912257a3c879fde";
    private static final String BACKUP =
        "f2bb896483f55f580189c9248ec086c4e1c81fcdc20ddecfe0f414831dd69c38";
    private static final String MODS =
        "5eaadfbd0f322e0645d6f9cb0cf5c1b4794f3b2a6b637953ace8bcac9cea78ad";
    @TempDir
    Path temporary;

    @AfterEach
    void clearReadOnly() throws Exception {
        if (!Files.exists(this.temporary)) {
            return;
        }
        try (var stream = Files.walk(this.temporary)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                try {
                    Files.setAttribute(path, "dos:readonly", false);
                } catch (
                    IOException | UnsupportedOperationException ignored
                ) {
                }
            }
        }
    }

    @Test
    void immutableReceiptReplacesRuntimeConfigTreeRehash()
        throws Exception {
        Path instance = this.temporary.resolve("Instance");
        Files.createDirectories(instance);
        ConfigTransactionReceipt receipt = valid(instance);
        Path path = this.temporary.resolve("receipt.json");
        receipt.writeImmutable(path);

        ConfigTransactionReceipt loaded =
            ConfigTransactionReceipt.readOnce(path);
        loaded.validateForReplay(
            "txn-1",
            instance,
            START,
            APPLIED,
            MODS
        );
        assertEquals(
            ConfigTransactionReceipt.Status.APPLIED_VERIFIED,
            loaded.status()
        );
        assertEquals(
            Phase2a0bContracts.ConfigOwner.EXTERNAL_LAUNCHER,
            loaded.owner()
        );
    }

    @Test
    void missingChangedAndCompletedReceiptsFailClosed()
        throws Exception {
        Path instance = this.temporary.resolve("Instance");
        Files.createDirectories(instance);
        assertThrows(
            IOException.class,
            () ->
                ConfigTransactionReceipt.readOnce(
                    this.temporary.resolve("missing.json")
                )
        );

        Path changed = this.temporary.resolve("changed.json");
        valid(instance).writeImmutable(changed);
        Files.setAttribute(changed, "dos:readonly", false);
        String text = Files.readString(changed, StandardCharsets.UTF_8);
        Files.writeString(
            changed,
            text.replace(APPLIED, "83" + APPLIED.substring(2)),
            StandardCharsets.UTF_8
        );
        IOException altered = assertThrows(
            IOException.class,
            () -> ConfigTransactionReceipt.readOnce(changed)
        );
        assertTrue(altered.getMessage().contains("content hash"));

        IOException completed = assertThrows(
            IOException.class,
            () ->
                valid(instance)
                    .withStatus(ConfigTransactionReceipt.Status.COMPLETED)
                    .validateForReplay(
                        "txn-1",
                        instance,
                        START,
                        APPLIED,
                        MODS
                    )
        );
        assertTrue(completed.getMessage().contains("already completed"));
    }

    @Test
    void wrongInstanceAppliedHashAndModProfileFailClosed()
        throws Exception {
        Path instance = this.temporary.resolve("Instance");
        Files.createDirectories(instance);
        ConfigTransactionReceipt receipt = valid(instance);
        assertThrows(
            IOException.class,
            () ->
                receipt.validateForReplay(
                    "txn-1",
                    this.temporary.resolve("Other"),
                    START,
                    APPLIED,
                    MODS
                )
        );
        assertThrows(
            IOException.class,
            () ->
                receipt.validateForReplay(
                    "txn-1",
                    instance,
                    START,
                    "83" + APPLIED.substring(2),
                    MODS
                )
        );
        assertThrows(
            IOException.class,
            () ->
                receipt.validateForReplay(
                    "txn-1",
                    instance,
                    START,
                    APPLIED,
                    "6e" + MODS.substring(2)
                )
        );
    }

    private static ConfigTransactionReceipt valid(Path instance)
        throws Exception {
        return ConfigTransactionReceipt.create(
            "txn-1",
            START,
            APPLIED,
            START,
            BACKUP,
            Instant.parse("2026-07-28T03:00:00Z"),
            instance,
            MODS
        );
    }
}
