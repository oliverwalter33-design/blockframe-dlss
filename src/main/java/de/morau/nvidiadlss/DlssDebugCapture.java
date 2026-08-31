package de.morau.nvidiadlss;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * Development-only same-frame A-F capture for the third-person DLAA audit.
 *
 * <p>F9 requests a single full-frame capture only after the master switch
 * {@code -Dnvidia_dlss.developerDiagnostics=true} is explicitly enabled. A
 * bounded automatic sequence additionally requires
 * {@code -Dnvidia_dlss.devSequenceFrames=N}.
 * Sequence captures use a centered ROI so 240-frame diagnostics remain
 * finite at 1440p and 4K. No sequence capture is enabled in normal builds.
 */
public final class DlssDebugCapture {
    private static final int SEQUENCE_START = DeveloperDiagnostics.enabled()
        ? intSetting("nvidia_dlss.devSequenceStartFrame", 180)
        : 180;
    private static final int SEQUENCE_FRAMES = DeveloperDiagnostics.enabled()
        ? Math.max(0, intSetting("nvidia_dlss.devSequenceFrames", 0))
        : 0;
    private static final int SEQUENCE_ROI = DeveloperDiagnostics.enabled()
        ? Math.max(64, intSetting("nvidia_dlss.devSequenceRoi", 768))
        : 64;
    private static final String SEQUENCE_ID = DeveloperDiagnostics.enabled()
        ? sanitize(System.getProperty(
            "nvidia_dlss.devSequenceId",
            "third-person-dlaa"
        ))
        : "third-person-dlaa";
    private static volatile boolean requested;
    private static CaptureSession active;

    private DlssDebugCapture() {}

    public static void request() {
        if (!DeveloperDiagnostics.enabled()) {
            return;
        }
        requested = true;
        NvidiaDlssMod.LOGGER.info(
            "DLSS-A-F-Debug-Capture fuer den naechsten vollstaendigen Weltframe angefordert"
        );
    }

    public static boolean requested() {
        return DeveloperDiagnostics.enabled() && requested;
    }

    public static boolean shouldCaptureFrame(int frame) {
        return DeveloperDiagnostics.enabled()
            && (requested || isSequenceFrame(frame));
    }

    /**
     * Schedules developer-only input readbacks before slEvaluateFeature is
     * called. Color, depth, motion and the supported transparency resource
     * therefore carry one identical frame id.
     */
    public static synchronized void captureBeforeEvaluate(
        CommandEncoder encoder,
        int frame,
        DlssMode mode,
        int outputWidth,
        int outputHeight,
        int inputWidth,
        int inputHeight,
        String preset,
        GpuTexture inputColor,
        GpuTexture inputDepth,
        GpuTexture rawMotion,
        GpuTexture depthDebug,
        GpuTexture motionDebug,
        GpuTexture motionValidity,
        GpuTexture historyBias,
        GpuTexture transparencyHint
    ) {
        if (!DeveloperDiagnostics.enabled()) {
            return;
        }
        boolean singleFrame = requested;
        if (!singleFrame && !isSequenceFrame(frame)) {
            return;
        }
        requested = false;
        if (active != null) {
            NvidiaDlssMod.LOGGER.error(
                "DLSS-A-F-Capture Frame {} begann, bevor Frame {} abgeschlossen war",
                frame,
                active.frame
            );
            active = null;
        }
        try {
            Path directory = Minecraft.getInstance()
                .gameDirectory
                .toPath()
                .resolve("dlss-af-sequences")
                .resolve(singleFrame ? "single" : SEQUENCE_ID);
            Files.createDirectories(directory);
            String base = String.format(
                Locale.ROOT,
                "%s_%dx%d_frame%08d_preset%s",
                mode.name().toLowerCase(Locale.ROOT),
                outputWidth,
                outputHeight,
                frame,
                preset
            );
            Region inputRegion = singleFrame
                ? Region.full(inputWidth, inputHeight)
                : Region.centered(inputWidth, inputHeight, SEQUENCE_ROI);
            Region outputRegion = singleFrame
                ? Region.full(outputWidth, outputHeight)
                : Region.centered(outputWidth, outputHeight, SEQUENCE_ROI);
            CaptureSession session = new CaptureSession(
                directory,
                base,
                frame,
                inputRegion,
                outputRegion,
                singleFrame
            );
            active = session;

            captureRgbaTexture(
                encoder,
                inputColor,
                inputRegion,
                directory.resolve(base + "_A_pre_sl_color.png"),
                "A input color"
            );
            captureRgbaTexture(
                encoder,
                depthDebug,
                inputRegion,
                directory.resolve(base + "_B_pre_sl_depth_visual.png"),
                "B depth visualization"
            );
            captureRawTexture(
                encoder,
                inputDepth,
                inputRegion,
                directory.resolve(base + "_B_pre_sl_depth_d32f.bin"),
                "B raw D32F depth"
            );
            captureRgbaTexture(
                encoder,
                motionDebug,
                inputRegion,
                directory.resolve(base + "_C_pre_sl_motion_visual.png"),
                "C motion visualization"
            );
            captureRawTexture(
                encoder,
                rawMotion,
                inputRegion,
                directory.resolve(base + "_C_pre_sl_motion_rg16f.bin"),
                "C raw RG16F motion"
            );
            captureMotionValidityTexture(
                encoder,
                motionValidity,
                inputRegion,
                directory.resolve(
                    base + "_C_pre_sl_motion_validity_r8ui.png"
                ),
                "C motion validity/ownership"
            );
            captureRawTexture(
                encoder,
                motionValidity,
                inputRegion,
                directory.resolve(
                    base + "_C_pre_sl_motion_validity_r8ui.bin"
                ),
                "C raw R8_UINT motion validity/ownership"
            );
            captureMaskTexture(
                encoder,
                historyBias,
                inputRegion,
                directory.resolve(base + "_D0_player_history_bias.png"),
                "D0 local-player BiasCurrentColorHint"
            );
            captureRawTexture(
                encoder,
                historyBias,
                inputRegion,
                directory.resolve(base + "_D0_player_history_bias_rgba8.bin"),
                "D0 raw RGBA8 local-player BiasCurrentColorHint"
            );
            captureMaskTexture(
                encoder,
                transparencyHint,
                inputRegion,
                directory.resolve(base + "_D_transparency_hint.png"),
                "D TransparencyHint"
            );
            captureRawTexture(
                encoder,
                transparencyHint,
                inputRegion,
                directory.resolve(base + "_D_transparency_hint_rgba8.bin"),
                "D raw TransparencyHint"
            );
        } catch (Exception error) {
            active = null;
            NvidiaDlssMod.LOGGER.error(
                "DLSS-A-F-Capture A-D konnte nicht gestartet werden",
                error
            );
        }
    }

    /** Schedules E immediately after the DLSS command buffer is enqueued. */
    public static synchronized void captureAfterEvaluate(
        CommandEncoder encoder,
        int frame,
        GpuTexture dlssOutput,
        GpuTexture afterSharpen,
        float appliedSharpness,
        float jitterX,
        float jitterY,
        float previousJitterX,
        float previousJitterY,
        String metadataJson
    ) {
        if (!DeveloperDiagnostics.enabled()) {
            return;
        }
        CaptureSession session = active;
        if (session == null || session.frame != frame) {
            return;
        }
        captureRgbaTexture(
            encoder,
            dlssOutput,
            session.outputRegion,
            session.directory.resolve(session.base + "_E_post_sl_output.png"),
            "E immediate Streamline DLSS/DLAA output"
        );
        if (appliedSharpness > 0.0F) {
            captureRgbaTexture(
                encoder,
                afterSharpen,
                session.outputRegion,
                session.directory.resolve(session.base + "_E2_post_nis.png"),
                "E2 post NIS"
            );
        }
        captureJitter(
            jitterX,
            jitterY,
            previousJitterX,
            previousJitterY,
            session.directory.resolve(session.base + "_D_jitter.png")
        );
        try {
            Files.writeString(
                session.directory.resolve(session.base + "_capture.json"),
                session.manifestJson(metadataJson)
            );
        } catch (Exception error) {
            NvidiaDlssMod.LOGGER.error(
                "DLSS-A-F-Metadaten konnten nicht geschrieben werden",
                error
            );
        }
    }

    /** Capture after renderLevel, before entity-outline/post/UI work. */
    public static synchronized void captureFinalWithoutHud(
        GpuTexture texture,
        int width,
        int height
    ) {
        if (!DeveloperDiagnostics.enabled()) {
            return;
        }
        CaptureSession session = active;
        if (session == null || session.postRenderLevelDone) {
            return;
        }
        session.postRenderLevelDone = true;
        captureRgbaTexture(
            RenderSystem.getDevice().createCommandEncoder(),
            texture,
            session.outputRegion.clampTo(width, height),
            session.directory.resolve(
                session.base + "_F0_post_renderlevel_before_entity_outline.png"
            ),
            "F0 post renderLevel"
        );
    }

    /**
     * Capture the exact texture passed to WindowSurface.blitFromTexture after
     * later entity-outline, post-effect and UI passes.
     */
    public static synchronized void captureFinalWithHud(
        GpuTexture texture,
        int width,
        int height
    ) {
        if (!DeveloperDiagnostics.enabled()) {
            return;
        }
        CaptureSession session = active;
        if (session == null || session.backbufferSourceDone) {
            return;
        }
        session.backbufferSourceDone = true;
        captureRgbaTexture(
            RenderSystem.getDevice().createCommandEncoder(),
            texture,
            session.outputRegion.clampTo(width, height),
            session.directory.resolve(
                session.base + "_F_backbuffer_source_after_all_passes.png"
            ),
            "F backbuffer source"
        );
        NvidiaDlssMod.LOGGER.info(
            "DLSS-A-F-Capture Frame {} vollstaendig in die GPU-Lesequeue eingestellt",
            session.frame
        );
        active = null;
    }

    private static void captureRgbaTexture(
        CommandEncoder encoder,
        GpuTexture texture,
        Region region,
        Path destination,
        String label
    ) {
        int pixelSize = texture.getFormat().blockSize();
        if (pixelSize != 4) {
            throw new IllegalArgumentException(
                label + " erwartet 4 Byte pro Texel, erhielt " + texture.getFormat()
            );
        }
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "BlockFrame IQ capture " + label,
            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
            region.byteSize(pixelSize)
        );
        encoder.copyTextureToBuffer(
            texture,
            buffer,
            0L,
            () -> {
                try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
                    NativeImage image = new NativeImage(
                        region.width,
                        region.height,
                        false
                    );
                    for (int y = 0; y < region.height; y++) {
                        for (int x = 0; x < region.width; x++) {
                            int abgr = read.data().getInt(
                                (x + y * region.width) * pixelSize
                            );
                            image.setPixelABGR(
                                x,
                                region.height - y - 1,
                                abgr | 0xFF000000
                            );
                        }
                    }
                    Util.ioPool().execute(() -> writeImage(image, destination));
                } finally {
                    buffer.close();
                }
            },
            0,
            region.x,
            region.y,
            region.width,
            region.height
        );
    }

    private static void captureMaskTexture(
        CommandEncoder encoder,
        GpuTexture texture,
        Region region,
        Path destination,
        String label
    ) {
        int pixelSize = texture.getFormat().blockSize();
        if (pixelSize != 4) {
            throw new IllegalArgumentException(
                label + " erwartet RGBA8, erhielt "
                    + texture.getFormat()
            );
        }
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "BlockFrame IQ capture " + label,
            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
            region.byteSize(pixelSize)
        );
        encoder.copyTextureToBuffer(
            texture,
            buffer,
            0L,
            () -> {
                try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
                    NativeImage image = new NativeImage(
                        region.width,
                        region.height,
                        false
                    );
                    for (int y = 0; y < region.height; y++) {
                        for (int x = 0; x < region.width; x++) {
                            int value = Byte.toUnsignedInt(
                                read.data().get((x + y * region.width) * pixelSize)
                            );
                            image.setPixelABGR(
                                x,
                                region.height - y - 1,
                                0xFF000000 | value << 16 | value << 8 | value
                            );
                        }
                    }
                    Util.ioPool().execute(() -> writeImage(image, destination));
                } finally {
                    buffer.close();
                }
            },
            0,
            region.x,
            region.y,
            region.width,
            region.height
        );
    }

    private static void captureMotionValidityTexture(
        CommandEncoder encoder,
        GpuTexture texture,
        Region region,
        Path destination,
        String label
    ) {
        int pixelSize = texture.getFormat().blockSize();
        if (pixelSize != 1) {
            throw new IllegalArgumentException(
                label + " erwartet R8_UINT, erhielt " + texture.getFormat()
            );
        }
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "BlockFrame IQ capture " + label,
            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
            region.byteSize(pixelSize)
        );
        encoder.copyTextureToBuffer(
            texture,
            buffer,
            0L,
            () -> {
                try (GpuBufferSlice.MappedView read =
                    buffer.map(true, false)) {
                    NativeImage image = new NativeImage(
                        region.width,
                        region.height,
                        false
                    );
                    for (int y = 0; y < region.height; y++) {
                        for (int x = 0; x < region.width; x++) {
                            int value = Byte.toUnsignedInt(
                                read.data().get(x + y * region.width)
                            );
                            image.setPixelABGR(
                                x,
                                region.height - y - 1,
                                validityColor(value)
                            );
                        }
                    }
                    Util.ioPool().execute(() ->
                        writeImage(image, destination)
                    );
                } finally {
                    buffer.close();
                }
            },
            0,
            region.x,
            region.y,
            region.width,
            region.height
        );
    }

    private static int validityColor(int value) {
        if (value == 0) {
            return 0xFF303030;
        }
        if (value == 1) {
            return 0xFF00FF00;
        }
        if (value == 2) {
            return 0xFF00B0FF;
        }
        if (value == 128) {
            return 0xFFFF8000;
        }
        return 0xFFFF00FF;
    }

    private static void captureRawTexture(
        CommandEncoder encoder,
        GpuTexture texture,
        Region region,
        Path destination,
        String label
    ) {
        int pixelSize = texture.getFormat().blockSize();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "BlockFrame IQ capture " + label,
            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
            region.byteSize(pixelSize)
        );
        encoder.copyTextureToBuffer(
            texture,
            buffer,
            0L,
            () -> {
                try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
                    ByteBuffer data = read.data();
                    byte[] bytes = new byte[Math.toIntExact(region.byteSize(pixelSize))];
                    for (int index = 0; index < bytes.length; index++) {
                        bytes[index] = data.get(index);
                    }
                    Util.ioPool().execute(() -> {
                        try {
                            Files.write(destination, bytes);
                        } catch (Exception error) {
                            NvidiaDlssMod.LOGGER.error(
                                "Raw-Capture konnte nicht geschrieben werden: {}",
                                destination,
                                error
                            );
                        }
                    });
                } finally {
                    buffer.close();
                }
            },
            0,
            region.x,
            region.y,
            region.width,
            region.height
        );
    }

    private static void writeImage(NativeImage image, Path destination) {
        try (image) {
            image.writeToFile(destination);
        } catch (Exception error) {
            NvidiaDlssMod.LOGGER.error(
                "Debug-PNG konnte nicht geschrieben werden: {}",
                destination,
                error
            );
        }
    }

    private static void captureJitter(
        float jitterX,
        float jitterY,
        float previousX,
        float previousY,
        Path destination
    ) {
        NativeImage image = new NativeImage(257, 257, false);
        for (int y = 0; y < 257; y++) {
            for (int x = 0; x < 257; x++) {
                int grid = x == 128 || y == 128 || x == 0 || y == 0
                        || x == 256 || y == 256
                    ? 0xFF505050
                    : 0xFF101010;
                image.setPixelABGR(x, y, grid);
            }
        }
        drawCross(
            image,
            Math.round((previousX + 0.5F) * 256.0F),
            Math.round((0.5F - previousY) * 256.0F),
            0xFFFFA000
        );
        drawCross(
            image,
            Math.round((jitterX + 0.5F) * 256.0F),
            Math.round((0.5F - jitterY) * 256.0F),
            0xFF00FFFF
        );
        Util.ioPool().execute(() -> writeImage(image, destination));
    }

    private static void drawCross(
        NativeImage image,
        int centerX,
        int centerY,
        int color
    ) {
        for (int offset = -5; offset <= 5; offset++) {
            int x = Math.clamp(centerX + offset, 0, image.getWidth() - 1);
            int y = Math.clamp(centerY + offset, 0, image.getHeight() - 1);
            image.setPixelABGR(
                x,
                Math.clamp(centerY, 0, image.getHeight() - 1),
                color
            );
            image.setPixelABGR(
                Math.clamp(centerX, 0, image.getWidth() - 1),
                y,
                color
            );
        }
    }

    private static boolean isSequenceFrame(int frame) {
        return DeveloperDiagnostics.enabled()
            && SEQUENCE_FRAMES > 0
            && frame >= SEQUENCE_START
            && frame < SEQUENCE_START + SEQUENCE_FRAMES;
    }

    private static int intSetting(String key, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String sanitize(String value) {
        String sanitized = value == null
            ? ""
            : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "third-person-dlaa" : sanitized;
    }

    private record Region(int x, int y, int width, int height) {
        static Region full(int width, int height) {
            return new Region(0, 0, width, height);
        }

        static Region centered(int width, int height, int maximum) {
            int captureWidth = Math.min(width, maximum);
            int captureHeight = Math.min(height, maximum);
            return new Region(
                (width - captureWidth) / 2,
                (height - captureHeight) / 2,
                captureWidth,
                captureHeight
            );
        }

        Region clampTo(int textureWidth, int textureHeight) {
            int clampedX = Math.clamp(this.x, 0, Math.max(0, textureWidth - 1));
            int clampedY = Math.clamp(this.y, 0, Math.max(0, textureHeight - 1));
            return new Region(
                clampedX,
                clampedY,
                Math.min(this.width, textureWidth - clampedX),
                Math.min(this.height, textureHeight - clampedY)
            );
        }

        long byteSize(int pixelSize) {
            return Math.multiplyExact(
                Math.multiplyExact((long)this.width, this.height),
                pixelSize
            );
        }

        String json() {
            return String.format(
                Locale.ROOT,
                "{\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d}",
                this.x,
                this.y,
                this.width,
                this.height
            );
        }
    }

    private static final class CaptureSession {
        final Path directory;
        final String base;
        final int frame;
        final Region inputRegion;
        final Region outputRegion;
        final boolean fullFrame;
        boolean postRenderLevelDone;
        boolean backbufferSourceDone;

        CaptureSession(
            Path directory,
            String base,
            int frame,
            Region inputRegion,
            Region outputRegion,
            boolean fullFrame
        ) {
            this.directory = directory;
            this.base = base;
            this.frame = frame;
            this.inputRegion = inputRegion;
            this.outputRegion = outputRegion;
            this.fullFrame = fullFrame;
        }

        String manifestJson(String rendererMetadata) {
            return String.format(
                Locale.ROOT,
                "{\n  \"schema\": \"blockframe-dlss-af-capture-v2\",\n"
                    + "  \"frame\": %d,\n"
                    + "  \"fullFrame\": %s,\n"
                    + "  \"inputRegion\": %s,\n"
                    + "  \"outputRegion\": %s,\n"
                    + "  \"stages\": {\"A\":\"pre-sl color\",\"B\":\"pre-sl depth\","
                    + "\"C\":\"pre-sl motion plus R8_UINT validity/ownership\",\"D\":\"pre-sl hints\","
                    + "\"E\":\"immediate Streamline DLSS/DLAA output\",\"F0\":\"post renderLevel before entity outline\"," 
                    + "\"F\":\"exact source texture passed to WindowSurface.blitFromTexture\"},\n"
                    + "  \"renderer\": %s\n}\n",
                this.frame,
                this.fullFrame,
                this.inputRegion.json(),
                this.outputRegion.json(),
                rendererMetadata
            );
        }
    }
}
