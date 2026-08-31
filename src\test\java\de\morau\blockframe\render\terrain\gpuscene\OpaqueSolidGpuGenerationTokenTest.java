package de.morau.blockframe.render.terrain.gpuscene;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpaqueSolidGpuGenerationTokenTest {
    @Test
    void exactOwnerEpochsMatchAndEveryMutationDemotes() {
        OpaqueSolidGpuGenerationToken token = token();
        var exact = epochs();
        assertTrue(token.matches(exact));
        assertFalse(
            token.matches(
                new OpaqueSolidGpuGenerationToken.OwnerEpochs(
                    2, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
                )
            )
        );
        assertFalse(
            token.matches(
                new OpaqueSolidGpuGenerationToken.OwnerEpochs(
                    1, 2, 3, 4, 50, 6, 7, 8, 9, 10, 11
                )
            )
        );
        assertFalse(
            token.matches(
                new OpaqueSolidGpuGenerationToken.OwnerEpochs(
                    1, 2, 3, 4, 5, 60, 7, 8, 9, 10, 11
                )
            )
        );
        assertFalse(
            token.matches(
                new OpaqueSolidGpuGenerationToken.OwnerEpochs(
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 100, 11
                )
            )
        );
    }

    @Test
    void invalidAndOverflowingRangesFailClosed() {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new OpaqueSolidGpuGenerationToken(
                    1, 2, 3, 4, 5, 6, 7, 8,
                    12, 13, Long.MAX_VALUE, 2,
                    0, 0, 0,
                    OpaqueSolidGpuGenerationToken
                        .INDEX_BINDING_SEQUENTIAL_QUAD,
                    1, 6, 0, 9, 10, 11
                )
        );
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new OpaqueSolidGpuGenerationToken(
                    1, 2, 3, 4, 5, 6, 7, 8,
                    12, 13, 0, 64,
                    99, 0, 16,
                    OpaqueSolidGpuGenerationToken
                        .INDEX_BINDING_SEQUENTIAL_QUAD,
                    1, 6, 0, 9, 10, 11
                )
        );
    }

    private static OpaqueSolidGpuGenerationToken token() {
        return new OpaqueSolidGpuGenerationToken(
            1, 2, 3, 4, 5, 6, 7, 8,
            12, 13, 64, 128,
            0, 0, 0,
            OpaqueSolidGpuGenerationToken
                .INDEX_BINDING_SEQUENTIAL_QUAD,
            1, 36, 4, 9, 10, 11
        );
    }

    private static OpaqueSolidGpuGenerationToken.OwnerEpochs epochs() {
        return new OpaqueSolidGpuGenerationToken.OwnerEpochs(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
        );
    }
}
