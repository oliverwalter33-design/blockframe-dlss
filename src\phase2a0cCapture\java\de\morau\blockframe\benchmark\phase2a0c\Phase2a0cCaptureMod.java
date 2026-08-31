package de.morau.blockframe.benchmark.phase2a0c;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Development-only entrypoint for one immutable Phase 2A.0C scene.
 *
 * <p>This source set intentionally has no dependency on BlockFrame production
 * output. All process, fixture, profile and restore ownership stays external.</p>
 */
@Mod(Phase2a0cCaptureMod.MOD_ID)
public final class Phase2a0cCaptureMod {
    public static final String MOD_ID = "blockframe_2a0c_capture";

    public Phase2a0cCaptureMod(IEventBus ignoredBus, ModContainer container) {
        Phase2a0cCaptureRuntime.bootstrap(
            container.getModInfo().getVersion().toString()
        );
    }
}
