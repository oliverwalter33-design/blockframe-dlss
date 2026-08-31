package de.morau.blockframe.api;

/**
 * Capability boundary for hybrid chunk meshing. GPU support never removes the
 * CPU fallback required for modded or otherwise unsupported geometry.
 */
public interface ChunkMeshingProvider
    extends BlockframeProvider<ChunkMeshingProvider.Capabilities> {

    record Capabilities(
        boolean cpuMeshing,
        boolean gpuCubeMeshing,
        boolean gpuFluidMeshing,
        boolean moddedBakedModelFallback,
        boolean connectedTextureFallback,
        boolean specialGeometryFallback,
        boolean immutableWorldSnapshots,
        boolean prioritizedVisibleRebuilds
    ) {
        public boolean safeUniversalFallback() {
            return this.cpuMeshing
                && this.moddedBakedModelFallback
                && this.connectedTextureFallback
                && this.specialGeometryFallback
                && this.immutableWorldSnapshots;
        }
    }
}
