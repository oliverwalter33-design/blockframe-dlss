package de.morau.nvidiadlss;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Adds an entry point when Reese's Sodium Options replaces the vanilla menu. */
@EventBusSubscriber(
    modid = NvidiaDlssMod.MOD_ID,
    value = Dist.CLIENT
)
public final class DlssOptionsScreenController {
    static final String REESES_OPTIONS_SCREEN =
        "me.flashyreese.mods.reeses_sodium_options.client.gui."
            + "SodiumVideoOptionsScreen";
    private static volatile boolean directSodiumIntegration;

    private DlssOptionsScreenController() {
    }

    @SubscribeEvent
    public static void onScreenInitialized(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (
            directSodiumIntegrationActive()
                || !isReesesOptionsScreen(screen)
        ) {
            return;
        }

        Bounds bounds = reesesToolbarBounds(
            screen.width,
            screen.height
        );
        Button settings = Button.builder(
            Component.translatable(
                "options.nvidia_dlss.menu_button",
                DlssConfig.mode().label()
            ),
            button -> openSettings(screen)
        )
            .bounds(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height()
            )
            .build();
        event.addListener(settings);
    }

    /** Suppresses the fallback button after Sodium accepted native options. */
    public static void markDirectSodiumIntegration() {
        directSodiumIntegration = true;
    }

    static boolean directSodiumIntegrationActive() {
        return directSodiumIntegration;
    }

    static boolean isReesesOptionsScreen(Screen screen) {
        for (
            Class<?> type = screen.getClass();
            type != null;
            type = type.getSuperclass()
        ) {
            if (REESES_OPTIONS_SCREEN.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    static Bounds reesesToolbarBounds(int screenWidth, int screenHeight) {
        int parentWidth = Math.min(
            screenWidth,
            screenHeight * 16 / 9
        );
        int contentX = (screenWidth - parentWidth) / 2
            + parentWidth / 40;
        int y = screenHeight * 7 / 8 + 5;
        int availableWidth = Math.max(1, screenWidth - contentX - 8);
        int width = Math.min(180, availableWidth);
        return new Bounds(
            Math.max(0, contentX),
            Math.max(0, Math.min(y, screenHeight - Button.DEFAULT_HEIGHT)),
            width,
            Button.DEFAULT_HEIGHT
        );
    }

    private static void openSettings(Screen parent) {
        Minecraft minecraft = parent.getMinecraft();
        minecraft.gui.setScreen(
            new BlockFrameDlssSettingsScreen(
                parent,
                minecraft.options
            )
        );
    }

    record Bounds(int x, int y, int width, int height) {
    }
}
