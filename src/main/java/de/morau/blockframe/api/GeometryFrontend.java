package de.morau.blockframe.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Negotiates geometry paths independently from a concrete Minecraft or Vulkan
 * command API.
 */
public interface GeometryFrontend extends BlockframeProvider<GeometryFrontend.Capabilities> {
    record Capabilities(Path path, Set<Feature> features) {
        public Capabilities {
            path = Objects.requireNonNull(path, "path");
            features = immutableFeatures(features);
        }

        public boolean supports(Feature feature) {
            return this.features.contains(Objects.requireNonNull(feature, "feature"));
        }
    }

    /**
     * Shader-pack constraints are deliberately narrow: merely activating a
     * shader pack can veto mesh shaders, but cannot veto the arena, culling,
     * upload ring, or indexed-indirect facilities.
     */
    record ShaderPackConstraints(boolean shaderPackActive, boolean permitsMeshShaders) {
        public static ShaderPackConstraints none() {
            return new ShaderPackConstraints(false, true);
        }
    }

    /** Immutable result of capability and bridge compatibility negotiation. */
    record Plan(Set<Feature> enabledFeatures) {
        public Plan {
            enabledFeatures = immutableFeatures(enabledFeatures);
        }

        public boolean enabled(Feature feature) {
            return this.enabledFeatures.contains(Objects.requireNonNull(feature, "feature"));
        }

        public static Plan negotiate(
            Capabilities frontend,
            Set<Feature> bridgeCompatibleFeatures,
            ShaderPackConstraints constraints
        ) {
            Objects.requireNonNull(frontend, "frontend");
            Objects.requireNonNull(bridgeCompatibleFeatures, "bridgeCompatibleFeatures");
            Objects.requireNonNull(constraints, "constraints");

            EnumSet<Feature> enabled = frontend.features().isEmpty()
                ? EnumSet.noneOf(Feature.class)
                : EnumSet.copyOf(frontend.features());
            enabled.retainAll(bridgeCompatibleFeatures);
            if (constraints.shaderPackActive() && !constraints.permitsMeshShaders()) {
                enabled.remove(Feature.MESH_SHADERS);
            }
            return new Plan(enabled);
        }
    }

    enum Path {
        VANILLA,
        NATIVE_FAST_PATH,
        SHADER_COMPATIBLE_PATH
    }

    enum Feature {
        PACKED_VERTEX_DATA,
        MESH_ARENA,
        HZB_CULLING,
        TRIPLE_BUFFER_UPLOAD_RING,
        INDEXED_INDIRECT_DRAW,
        MULTI_DRAW_INDEXED_INDIRECT,
        SHADOW_VISIBILITY_PATH,
        MESH_SHADERS
    }

    private static Set<Feature> immutableFeatures(Set<Feature> features) {
        Objects.requireNonNull(features, "features");
        if (features.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(features));
    }
}
