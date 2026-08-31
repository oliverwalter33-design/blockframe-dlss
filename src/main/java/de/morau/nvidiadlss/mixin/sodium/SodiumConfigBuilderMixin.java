package de.morau.nvidiadlss.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.morau.nvidiadlss.DlssConfig;
import de.morau.nvidiadlss.DlssMode;
import de.morau.nvidiadlss.DlssOption;
import de.morau.nvidiadlss.DlssOptionsScreenController;
import de.morau.nvidiadlss.NvidiaDlssMod;
import de.morau.nvidiadlss.SharpeningMode;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Restores the direct Sodium controls used by earlier BlockFrame releases. */
@Mixin(
    targets = "net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder",
    remap = false
)
public abstract class SodiumConfigBuilderMixin {
    private static final Identifier VSYNC =
        Identifier.parse("sodium:general.vsync");
    private static final Identifier DLSS =
        Identifier.parse("voxellift:mode");
    private static final Identifier SHARPENING =
        Identifier.parse("voxellift:sharpening");
    private static final Identifier SHARPENING_AMOUNT =
        Identifier.parse("voxellift:sharpening_amount");

    @WrapOperation(
        method = "buildGeneralPage",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/api/config/structure/"
                + "OptionGroupBuilder;addOption(Lnet/caffeinemc/mods/"
                + "sodium/api/config/structure/OptionBuilder;)Lnet/"
                + "caffeinemc/mods/sodium/api/config/structure/"
                + "OptionGroupBuilder;"
        )
    )
    private OptionGroupBuilder nvidiaDlss$insertAboveVsync(
        OptionGroupBuilder group,
        OptionBuilder option,
        Operation<OptionGroupBuilder> original,
        ConfigBuilder builder
    ) {
        if (
            ((OptionBuilderAccessor) (Object) option)
                .nvidiaDlss$id()
                .equals(VSYNC)
        ) {
            EnumOptionBuilder<DlssMode> dlss = builder
                .createEnumOption(DLSS, DlssMode.class)
                .setStorageHandler(DlssConfig::save)
                .setName(Component.translatable(
                    "options.nvidia_dlss.mode"
                ))
                .setTooltip(DlssOption::modeTooltip)
                .setElementNameProvider(DlssMode::label)
                .setDefaultValue(DlssMode.OFF)
                .setBinding(DlssConfig::setMode, DlssConfig::mode);
            group.addOption(dlss);

            EnumOptionBuilder<SharpeningMode> sharpening = builder
                .createEnumOption(SHARPENING, SharpeningMode.class)
                .setStorageHandler(DlssConfig::save)
                .setName(Component.translatable(
                    "options.nvidia_dlss.sharpening"
                ))
                .setTooltip(Component.translatable(
                    "options.nvidia_dlss.sharpening.tooltip"
                ))
                .setElementNameProvider(SharpeningMode::label)
                .setDefaultValue(SharpeningMode.AUTO)
                .setBinding(
                    DlssConfig::setSharpening,
                    DlssConfig::sharpening
                );
            group.addOption(sharpening);

            IntegerOptionBuilder sharpeningAmount = builder
                .createIntegerOption(SHARPENING_AMOUNT)
                .setStorageHandler(DlssConfig::save)
                .setName(Component.translatable(
                    "options.nvidia_dlss.sharpening.amount"
                ))
                .setTooltip(Component.translatable(
                    "options.nvidia_dlss.sharpening.amount.tooltip"
                ))
                .setRange(0, 100, 1)
                .setValueFormatter(value ->
                    Component.literal(value + " %")
                )
                .setDefaultValue(20)
                .setBinding(
                    DlssConfig::setSharpeningAmount,
                    DlssConfig::sharpeningAmount
                );
            group.addOption(sharpeningAmount);

            DlssOptionsScreenController.markDirectSodiumIntegration();
            NvidiaDlssMod.LOGGER.info(
                "DLSS-Optionen in Sodium direkt vor VSync eingefügt"
            );
        }
        return original.call(group, option);
    }
}
