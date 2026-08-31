package de.morau.nvidiadlss.mixin.accessor;

import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to the state-selected multipart child list. The census
 * initializes that list through the normal model contract before reading it.
 */
@Mixin(MultiPartModel.class)
public interface NativeTerrainMultiPartModelAccessor {
    @Accessor("models")
    @Nullable List<BlockStateModel>
    blockframe$getNativeTerrainSelectedModels();
}
