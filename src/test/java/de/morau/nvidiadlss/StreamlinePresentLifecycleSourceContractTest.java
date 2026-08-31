package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StreamlinePresentLifecycleSourceContractTest {
    @Test
    void dlssEvaluationRequiresAnAcquiredPresentationSurface()
        throws Exception {
        String mixin = Files.readString(
            projectSource(
                "src/main/java/de/morau/nvidiadlss/mixin/"
                    + "GameRendererMixin.java"
            ),
            StandardCharsets.UTF_8
        );
        String renderer = Files.readString(
            projectSource(
                "src/main/java/de/morau/nvidiadlss/"
                    + "DlssRenderer.java"
            ),
            StandardCharsets.UTF_8
        );

        int handlerStart = mixin.indexOf(
            "private void nvidiaDlss$useLowResolutionWorldTarget"
        );
        int handlerEnd = mixin.indexOf(
            "@ModifyArg",
            handlerStart
        );
        assertTrue(handlerStart >= 0, "The render handler must exist");
        assertTrue(handlerEnd > handlerStart, "The render handler must end");
        String renderHandler = mixin.substring(handlerStart, handlerEnd);
        int acquiredIndex = renderHandler.indexOf(
            "this.minecraft.windowSurface().isAcquired()"
        );
        int beginFrameIndex = renderHandler.indexOf(
            "DlssRenderer.beginFrame("
        );
        assertTrue(
            acquiredIndex >= 0,
            "A minimized or invalid surface must not produce Streamline "
                + "evaluates without the matching Present hook"
        );
        assertTrue(
            beginFrameIndex >= 0 && acquiredIndex < beginFrameIndex,
            "Surface acquisition must be included before beginFrame"
        );

        int rendererHandlerStart = renderer.indexOf(
            "public static RenderTarget beginFrame("
        );
        int rendererHandlerEnd = renderer.indexOf(
            "private static void ensureResources(",
            rendererHandlerStart
        );
        assertTrue(
            rendererHandlerStart >= 0,
            "DlssRenderer.beginFrame must exist"
        );
        assertTrue(
            rendererHandlerEnd > rendererHandlerStart,
            "DlssRenderer.beginFrame must end before ensureResources"
        );
        String beginFrame = renderer.substring(
            rendererHandlerStart,
            rendererHandlerEnd
        );
        int inactiveGuardIndex = beginFrame.indexOf(
            "|| !shouldRenderLevel"
        );
        int ensureResourcesIndex = beginFrame.indexOf(
            "ensureResources("
        );
        assertTrue(
            inactiveGuardIndex >= 0,
            "The non-presenting-frame guard must exist"
        );
        assertTrue(
            ensureResourcesIndex >= 0,
            "The resource-allocation call must exist"
        );
        assertTrue(
            inactiveGuardIndex < ensureResourcesIndex,
            "The non-presenting frame must leave the temporal path before "
                + "allocating or evaluating DLSS resources"
        );
        assertTrue(
            renderer.contains(
                "requestReset(!shouldRenderLevel "
                    + "? \"unterbrochene Rendersequenz\""
            ),
            "Resuming presentation must reject the interrupted history"
        );
    }

    @Test
    void acquiredFramesStillPresentThroughTheStreamlineProxy()
        throws Exception {
        String mixin = Files.readString(
            projectSource(
                "src/main/java/de/morau/nvidiadlss/mixin/"
                    + "VulkanGpuSurfaceMixin.java"
            ),
            StandardCharsets.UTF_8
        );

        assertTrue(
            mixin.contains(
                "NativeStreamline.queuePresent("
                    + "queue.address(), presentInfo.address())"
            )
        );
        assertTrue(
            mixin.contains(
                "result == NOT_HANDLED "
                    + "? KHRSwapchain.vkQueuePresentKHR"
            )
        );
    }

    private static Path projectSource(String relativePath) {
        return Path.of(
            System.getProperty("blockframe.projectDir")
        ).resolve(relativePath);
    }
}
