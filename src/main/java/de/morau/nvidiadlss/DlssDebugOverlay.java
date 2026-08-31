package de.morau.nvidiadlss;

import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.faststart.FastStartRuntime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Output-resolution diagnostic layer. It is extracted after the DLSS world pass. */
public final class DlssDebugOverlay {
    private static int page;

    private DlssDebugOverlay() {}

    public static void toggle() {
        page = (page + 1) % 3;
        NvidiaDlssMod.LOGGER.info(
            "BlockFrame-F8-Diagnose: {}",
            switch (page) {
                case 1 -> "Renderer/Engine";
                case 2 -> "FastStart";
                default -> "aus";
            }
        );
    }

    public static void render(GuiGraphicsExtractor graphics) {
        if (page == 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        List<String> lines;
        if (page == 2) {
            lines = FastStartRuntime.debugLines();
            renderLines(graphics, minecraft, lines);
            return;
        }
        List<String> dlssLines = DlssRenderer.debugLines();
        List<String> engineLines = BlockframeRuntime.engine().debugLines();
        List<String> runStateLines =
            BlockframeRuntime.runStateDebugLines();
        List<String> gpuSceneLines = List.of(
            "Opaque-solid GPU scene V16: NO_GO_UNREGISTERED"
        );
        lines = new ArrayList<>(
            dlssLines.size()
                + engineLines.size()
                + runStateLines.size()
                + gpuSceneLines.size()
                + 3
        );
        lines.addAll(dlssLines);
        lines.add("--- BlockFrame Engine ---");
        lines.addAll(engineLines);
        lines.add("--- BlockFrame Run State (cached) ---");
        lines.addAll(runStateLines);
        lines.add("--- Opaque Solid GPU Scene (cached) ---");
        lines.add(
            "Opaque-solid templates V1: NO_GO_DISABLED"
        );
        lines.addAll(gpuSceneLines);
        renderLines(graphics, minecraft, lines);
    }

    private static void renderLines(
        GuiGraphicsExtractor graphics,
        Minecraft minecraft,
        List<String> lines
    ) {
        int width = 0;
        for (String line : lines) width = Math.max(width, minecraft.font.width(line));
        int height = lines.size() * 10 + 8;
        graphics.fill(4, 4, width + 14, height, 0xC0101010);
        int y = 8;
        for (String line : lines) {
            graphics.text(minecraft.font, line, 9, y, 0xFFF2F2F2, true);
            y += 10;
        }
    }
}
