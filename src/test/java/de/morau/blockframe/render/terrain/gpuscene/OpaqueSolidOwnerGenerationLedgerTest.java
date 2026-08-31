package de.morau.blockframe.render.terrain.gpuscene;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpaqueSolidOwnerGenerationLedgerTest {
    @Test
    void meshMustInvalidateBeforeReplacement() {
        OpaqueSolidOwnerGenerationLedger ledger =
            new OpaqueSolidOwnerGenerationLedger();
        Object section = new Object();
        Object first = new Object();
        Object second = new Object();
        long firstGeneration = ledger.publishMesh(section, first);
        assertThrows(
            IllegalStateException.class,
            () -> ledger.publishMesh(section, second)
        );
        assertTrue(
            ledger.invalidateMeshBeforeReplace(section, first)
        );
        assertFalse(
            ledger.meshGenerationCurrent(
                section,
                first,
                firstGeneration
            )
        );
        long secondGeneration = ledger.publishMesh(section, second);
        assertNotEquals(firstGeneration, secondGeneration);
        assertTrue(
            ledger.meshGenerationCurrent(
                section,
                second,
                secondGeneration
            )
        );
    }

    @Test
    void rangeMustInvalidateBeforeReuseAndBufferBeforeClose() {
        OpaqueSolidOwnerGenerationLedger ledger =
            new OpaqueSolidOwnerGenerationLedger();
        Object uber = new Object();
        Object mesh = new Object();
        Object buffer = new Object();
        long bufferGeneration = ledger.publishBuffer(buffer, 77L);
        var first = ledger.publishRange(
            uber,
            mesh,
            buffer,
            bufferGeneration,
            32L,
            96L
        );
        assertThrows(
            IllegalStateException.class,
            () ->
                ledger.publishRange(
                    uber,
                    mesh,
                    buffer,
                    bufferGeneration,
                    128L,
                    96L
                )
        );
        assertTrue(
            ledger.invalidateRangeBeforeFree(
                uber,
                mesh,
                first.generation()
            )
        );
        assertFalse(
            ledger.rangeGenerationCurrent(
                uber,
                mesh,
                first.generation()
            )
        );
        var second = ledger.publishRange(
            uber,
            mesh,
            buffer,
            bufferGeneration,
            128L,
            96L
        );
        assertNotEquals(first.generation(), second.generation());
        assertTrue(
            ledger.invalidateBufferBeforeClose(
                buffer,
                bufferGeneration
            )
        );
        assertFalse(
            ledger.bufferGenerationCurrent(
                buffer,
                bufferGeneration
            )
        );
        assertThrows(
            IllegalStateException.class,
            () ->
                ledger.publishRange(
                    uber,
                    new Object(),
                    buffer,
                    bufferGeneration,
                    256L,
                    32L
                )
        );
    }

    @Test
    void clearInvalidatesWithoutEnumeratingToCallers() {
        OpaqueSolidOwnerGenerationLedger ledger =
            new OpaqueSolidOwnerGenerationLedger();
        Object buffer = new Object();
        long generation = ledger.publishBuffer(buffer, 100L);
        assertTrue(
            ledger.bufferGenerationCurrent(buffer, generation)
        );
        ledger.clear();
        assertFalse(
            ledger.bufferGenerationCurrent(buffer, generation)
        );
        assertFalse(
            java.util.Arrays.stream(
                OpaqueSolidOwnerGenerationLedger.class.getMethods()
            ).anyMatch(
                method ->
                    method.getName().contains("entries")
                        || method.getName().contains("scan")
                        || method.getName().contains("allSlots")
            )
        );
    }
}
