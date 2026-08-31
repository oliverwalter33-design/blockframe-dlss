package de.morau.blockframe.benchmark.phase2a0b;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit development-only bootstrap. This class is in a separate source set
 * and cannot enter the production BlockFrame JAR.
 */
@Mod(Phase2a0bHarnessMod.MOD_ID)
public final class Phase2a0bHarnessMod {
    public static final String MOD_ID = "blockframe_2a0b_harness";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public Phase2a0bHarnessMod(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(Phase2a0bHarnessMod::onKey);
        Phase2a0bRuntime.bootstrap(FMLPaths.GAMEDIR.get());
        LOGGER.info(
            "BlockFrame Phase 2A.0B dev-only harness bootstrapped; "
                + "readiness is driven only by lifecycle/render callbacks"
        );
    }

    private static void onKey(InputEvent.Key event) {
        if (
            event.getKey() == GLFW.GLFW_KEY_ESCAPE
                && event.getAction() == GLFW.GLFW_PRESS
        ) {
            Phase2a0bRuntime.abortReplay("escape-requested");
        }
    }
}
