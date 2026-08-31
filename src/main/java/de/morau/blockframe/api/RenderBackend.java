package de.morau.blockframe.api;

import java.util.Objects;

/**
 * Backend capability boundary. Implementations adapt an already-created
 * graphics device; they do not imply ownership of the game device itself.
 */
public interface RenderBackend extends BlockframeProvider<RenderBackend.Capabilities> {
    record Capabilities(
        Api api,
        boolean graphics,
        boolean compute,
        boolean gpuTimestamps,
        boolean persistentMapping,
        boolean indexedIndirectDraw,
        boolean multiDrawIndexedIndirect,
        boolean bufferDeviceAddress,
        boolean meshShaders,
        boolean deviceGeneratedCommands,
        int maximumFramesInFlight
    ) {
        public Capabilities {
            api = Objects.requireNonNull(api, "api");
            if (api == Api.UNKNOWN) {
                graphics = false;
                compute = false;
                gpuTimestamps = false;
                persistentMapping = false;
                indexedIndirectDraw = false;
                multiDrawIndexedIndirect = false;
                bufferDeviceAddress = false;
                meshShaders = false;
                deviceGeneratedCommands = false;
                maximumFramesInFlight = 0;
            } else {
                maximumFramesInFlight = Math.max(0, maximumFramesInFlight);
                multiDrawIndexedIndirect = indexedIndirectDraw && multiDrawIndexedIndirect;
                deviceGeneratedCommands = indexedIndirectDraw && deviceGeneratedCommands;
            }
        }
    }

    enum Api {
        UNKNOWN,
        OPENGL,
        VULKAN
    }
}
