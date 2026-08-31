package de.morau.nvidiadlss;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Standalone DLSS controls used when another mod replaces video settings. */
public final class BlockFrameDlssSettingsScreen extends OptionsSubScreen {
    public BlockFrameDlssSettingsScreen(
        Screen lastScreen,
        Options options
    ) {
        super(
            lastScreen,
            options,
            Component.translatable("options.nvidia_dlss.screen.title")
        );
    }

    @Override
    protected void addOptions() {
        this.list.addBig(DlssOption.get());
        this.list.addBig(DlssOption.sharpening());
        this.list.addBig(DlssOption.sharpeningAmount());
    }
}
