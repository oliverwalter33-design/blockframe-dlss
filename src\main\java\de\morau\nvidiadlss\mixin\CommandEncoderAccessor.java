package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.TracyGpuProfiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CommandEncoder.class)
public interface CommandEncoderAccessor {
    @Accessor("backend") CommandEncoderBackend nvidiaDlss$backend();

    @Accessor("profiler")
    @Nullable TracyGpuProfiler blockframe$tracyGpuProfiler();
}
