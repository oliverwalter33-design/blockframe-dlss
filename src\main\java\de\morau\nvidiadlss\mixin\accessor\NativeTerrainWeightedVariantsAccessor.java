package de.morau.nvidiadlss.mixin.accessor;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.util.random.WeightedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only, source-contract-bound access used by the post-bake terrain
 * census. It enumerates every weighted model instead of sampling one random
 * branch.
 */
@Mixin(WeightedVariants.class)
public interface NativeTerrainWeightedVariantsAccessor {
    @Accessor("list")
    WeightedList<BlockStateModel>
    blockframe$getNativeTerrainVariants();
}
