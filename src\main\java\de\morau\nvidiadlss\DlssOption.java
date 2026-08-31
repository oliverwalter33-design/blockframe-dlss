package de.morau.nvidiadlss;

import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public final class DlssOption {
    private static final Codec<DlssMode> CODEC = Codec.STRING.xmap(DlssMode::byId, DlssMode::id);
    private static final Codec<SharpeningMode> SHARPENING_CODEC = Codec.STRING.xmap(SharpeningMode::byId, SharpeningMode::id);
    private static final OptionInstance<DlssMode> INSTANCE = new OptionInstance<>(
        "options.nvidia_dlss.mode",
        value -> Tooltip.create(modeTooltip(value)),
        (caption, value) -> Options.genericValueLabel(caption, value.label()),
        new OptionInstance.Enum<>(List.of(DlssMode.values()), CODEC),
        DlssConfig.mode(),
        DlssConfig::setMode
    );
    private static final OptionInstance<SharpeningMode> SHARPENING = new OptionInstance<>(
        "options.nvidia_dlss.sharpening",
        value -> Tooltip.create(net.minecraft.network.chat.Component.translatable("options.nvidia_dlss.sharpening.tooltip")),
        (caption, value) -> Options.genericValueLabel(caption, value.label()),
        new OptionInstance.Enum<>(List.of(SharpeningMode.values()), SHARPENING_CODEC),
        DlssConfig.sharpening(),
        DlssConfig::setSharpening
    );
    private static final OptionInstance<Integer> SHARPENING_AMOUNT = new OptionInstance<>(
        "options.nvidia_dlss.sharpening.amount",
        value -> Tooltip.create(net.minecraft.network.chat.Component.translatable("options.nvidia_dlss.sharpening.amount.tooltip")),
        (caption, value) -> Options.genericValueLabel(caption, net.minecraft.network.chat.Component.literal(value + " %")),
        new OptionInstance.IntRange(0, 100),
        DlssConfig.sharpeningAmount(),
        DlssConfig::setSharpeningAmount
    );

    private DlssOption() {}
    public static Component modeTooltip(DlssMode value) {
        return DlssStatus.tooltip()
            .copy()
            .append("\n")
            .append(Component.translatable(
                "options.nvidia_dlss.tooltip.selection",
                value.label()
            ))
            .append("\n")
            .append(Component.translatable(
                "options.nvidia_dlss.tooltip.runtime_versions"
            ));
    }
    public static OptionInstance<DlssMode> get() { return INSTANCE; }
    public static OptionInstance<SharpeningMode> sharpening() { return SHARPENING; }
    public static OptionInstance<Integer> sharpeningAmount() { return SHARPENING_AMOUNT; }
}
