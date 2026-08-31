package de.morau.nvidiadlss;

import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * Exact allow-list for the private DLSS material sampler.
 *
 * <p>The block atlas is also checked at the Vulkan descriptor hand-off. This
 * class deliberately accepts only solid and alpha-tested cutout terrain
 * pipelines with no blending. Translucent terrain, particles, entities, UI,
 * fonts, lightmaps, depth, and post-processing keep their original sampler.
 */
public final class DlssTerrainSamplerScope {
    private static final String BLOCK_ATLAS_LABEL =
        "minecraft:textures/atlas/blocks.png";
    private static final Set<Identifier> OPAQUE_SOLID_PIPELINES = Set.of(
        pipeline("minecraft", "pipeline/solid_terrain"),
        pipeline("sodium", "pipeline/solid_terrain"),
        pipeline("milkshade", "pipeline/solid_terrain"),
        pipeline("voxellift", "pipeline/native_terrain_solid_v1"),
        pipeline(
            "voxellift",
            "pipeline/opaque_solid_gpu_scene_indirect_v1"
        )
    );
    private static final Set<Identifier> CUTOUT_PIPELINES = Set.of(
        pipeline("minecraft", "pipeline/cutout_terrain"),
        pipeline("sodium", "pipeline/cutout_terrain"),
        pipeline("milkshade", "pipeline/cutout_terrain"),
        pipeline("voxellift", "pipeline/native_terrain_cutout_v1")
    );

    private DlssTerrainSamplerScope() {
    }

    public static boolean eligible(
        Identifier pipelineId,
        boolean blended
    ) {
        return !blended
            && pipelineId != null
            && (
                OPAQUE_SOLID_PIPELINES.contains(pipelineId)
                    || CUTOUT_PIPELINES.contains(pipelineId)
            );
    }

    public static boolean isCutout(Identifier pipelineId) {
        return pipelineId != null
            && CUTOUT_PIPELINES.contains(pipelineId);
    }

    public static boolean isBlockAtlas(String textureLabel) {
        return BLOCK_ATLAS_LABEL.equals(textureLabel);
    }

    private static Identifier pipeline(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}
