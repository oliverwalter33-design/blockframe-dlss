package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

class MinecraftTerrainSectionSnapshotContractTest {
    @Test
    void blockEntityQueryIsExplicitlyUnsupportedBySourceContract()
        throws IOException {
        String source = Files.readString(
            Path.of(
                System.getProperty("blockframe.projectDir", ".")
            ).resolve(
                "src/main/java/de/morau/blockframe/render/terrain/"
                    + "nativeengine/MinecraftTerrainSectionSnapshot.java"
            ),
            StandardCharsets.UTF_8
        );
        int method = source.indexOf(
            "public @Nullable BlockEntity getBlockEntity("
        );
        int unsupported = source.indexOf(
            "this.unsupportedQuery = true;",
            method
        );
        int noLiveObject = source.indexOf("return null;", unsupported);
        assertTrue(method >= 0);
        assertTrue(unsupported > method);
        assertTrue(noLiveObject > unsupported);
    }

    @Test
    void wrongWorldThreadFailsBeforeReadingTheSource() {
        boolean[] invoked = {false};
        BlockAndTintGetter source = throwingSource(invoked);
        MemoryBudgetManager budgets =
            new MemoryBudgetManager(MemoryBudgetSettings.defaults());

        var capture = MinecraftTerrainSectionSnapshot.capture(
            source,
            SectionPos.of(0, 0, 0),
            NativeTerrainCompilerTestFixtures.generations(),
            NativeTerrainCompilerTestFixtures.section(),
            NativeTerrainCompilerTestFixtures.digest(9_000L),
            budgets,
            1024L * 1024L,
            false
        );

        assertFalse(capture.successful());
        assertEquals(
            MinecraftTerrainSectionSnapshot.FailureReason
                .WRONG_CAPTURE_THREAD,
            capture.failureReason()
        );
        assertFalse(invoked[0]);
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void perSectionRamLimitRejectsBeforeReadingTheSource() {
        boolean[] invoked = {false};
        BlockAndTintGetter source = throwingSource(invoked);
        MemoryBudgetManager budgets =
            new MemoryBudgetManager(MemoryBudgetSettings.defaults());

        var capture = MinecraftTerrainSectionSnapshot.capture(
            source,
            SectionPos.of(0, 0, 0),
            NativeTerrainCompilerTestFixtures.generations(),
            NativeTerrainCompilerTestFixtures.section(),
            NativeTerrainCompilerTestFixtures.digest(9_001L),
            budgets,
            1L,
            true
        );

        assertFalse(capture.successful());
        assertEquals(
            MinecraftTerrainSectionSnapshot.FailureReason
                .RAM_BUDGET_REJECTED,
            capture.failureReason()
        );
        assertFalse(invoked[0]);
        assertTrue(budgets.closeAndReport());
    }

    private static BlockAndTintGetter throwingSource(
        boolean[] invoked
    ) {
        return (BlockAndTintGetter)Proxy.newProxyInstance(
            BlockAndTintGetter.class.getClassLoader(),
            new Class<?>[] {BlockAndTintGetter.class},
            (proxy, method, arguments) -> {
                invoked[0] = true;
                throw new AssertionError(
                    "source must not be queried by this gate"
                );
            }
        );
    }

}
