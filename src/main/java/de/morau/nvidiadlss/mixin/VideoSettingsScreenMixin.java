package de.morau.nvidiadlss.mixin;

import de.morau.nvidiadlss.DlssOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Inject(method = "displayOptions", at = @At("RETURN"), cancellable = true)
    private static void nvidiaDlss$insertAboveVsync(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        List<OptionInstance<?>> entries = new ArrayList<>(Arrays.asList(cir.getReturnValue()));
        int index = entries.indexOf(options.enableVsync());
        if (index < 0) index = 0;
        entries.add(index, DlssOption.get());
        entries.add(index + 1, DlssOption.sharpening());
        entries.add(index + 2, DlssOption.sharpeningAmount());
        cir.setReturnValue(entries.toArray(OptionInstance<?>[]::new));
    }
}
