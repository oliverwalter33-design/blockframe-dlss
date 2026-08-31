package de.morau.nvidiadlss.mixin.accessor;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access used by the allocation-free exact-geometry walker. */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
    @Accessor("cubes")
    List<ModelPart.Cube> nvidiaDlss$cubes();

    @Accessor("children")
    Map<String, ModelPart> nvidiaDlss$children();
}
