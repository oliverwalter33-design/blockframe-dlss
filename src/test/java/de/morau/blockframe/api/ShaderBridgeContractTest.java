package de.morau.blockframe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShaderBridgeContractTest {
    @Test
    void fullContractContainsEveryRequiredSemantic() {
        assertEquals(21, ShaderBridge.Semantic.values().length);
        ShaderBridge.Capabilities none = new ShaderBridge.Capabilities(Set.of(), Set.of());
        assertEquals(
            EnumSet.allOf(ShaderBridge.Semantic.class),
            none.missingFullContract()
        );
    }

    @Test
    void shaderPackCanDisableOnlyMeshShaders() {
        Set<GeometryFrontend.Feature> all = EnumSet.allOf(GeometryFrontend.Feature.class);
        GeometryFrontend.Capabilities frontend = new GeometryFrontend.Capabilities(
            GeometryFrontend.Path.NATIVE_FAST_PATH,
            all
        );

        GeometryFrontend.Plan plan = GeometryFrontend.Plan.negotiate(
            frontend,
            all,
            new GeometryFrontend.ShaderPackConstraints(true, false)
        );

        assertFalse(plan.enabled(GeometryFrontend.Feature.MESH_SHADERS));
        assertTrue(plan.enabled(GeometryFrontend.Feature.MESH_ARENA));
        assertTrue(plan.enabled(GeometryFrontend.Feature.HZB_CULLING));
        assertTrue(plan.enabled(GeometryFrontend.Feature.TRIPLE_BUFFER_UPLOAD_RING));
        assertTrue(plan.enabled(GeometryFrontend.Feature.INDEXED_INDIRECT_DRAW));
    }

    @Test
    void vanillaReportsOnlyObservedClassicVertexSubset() {
        VanillaShaderBridge bridge = new VanillaShaderBridge();

        assertEquals(Availability.State.DEGRADED, bridge.availability().state());
        assertTrue(bridge.capabilities().semantics().contains(ShaderBridge.Semantic.POSITION));
        assertTrue(bridge.capabilities().semantics().contains(ShaderBridge.Semantic.NORMAL));
        assertFalse(bridge.capabilities().semantics().contains(ShaderBridge.Semantic.TANGENT));
        assertFalse(bridge.capabilities().semantics().contains(ShaderBridge.Semantic.MATERIAL_ID));

        bridge.close();
        assertEquals(Availability.State.UNAVAILABLE, bridge.availability().state());
        assertEquals(ProviderLifecycle.State.CLOSED, bridge.lifecycle().state());
    }

    @Test
    void nativeBridgeDoesNotPretendToBeTerrainBridge() {
        NativeShaderBridge bridge = new NativeShaderBridge();

        assertEquals(Availability.State.UNAVAILABLE, bridge.availability().state());
        assertTrue(bridge.capabilities().semantics().contains(ShaderBridge.Semantic.MOTION_VECTORS));
        assertTrue(bridge.capabilities().semantics().contains(ShaderBridge.Semantic.REVERSED_Z_DEPTH));
        assertFalse(bridge.capabilities().semantics().contains(ShaderBridge.Semantic.EXPOSURE));
        assertFalse(bridge.capabilities().semantics().contains(ShaderBridge.Semantic.REACTIVE_MASK));
        assertTrue(bridge.capabilities().compatibleGeometryFeatures().isEmpty());
    }

    @Test
    void missingSemanticsAndDoubleTaaRejectNegotiation() {
        VanillaShaderBridge bridge = new VanillaShaderBridge();
        GeometryFrontend.Capabilities geometry = new GeometryFrontend.Capabilities(
            GeometryFrontend.Path.VANILLA,
            Set.of()
        );
        ShaderBridge.Request request = new ShaderBridge.Request(
            EnumSet.allOf(ShaderBridge.Semantic.class),
            geometry,
            GeometryFrontend.ShaderPackConstraints.none(),
            new TemporalCoordination.Request(
                true,
                true,
                TemporalCoordination.Owner.BLOCKFRAME_TEMPORAL_UPSCALER
            )
        );

        ShaderBridge.Negotiation result = bridge.negotiate(request);

        assertEquals(Availability.State.UNAVAILABLE, result.availability().state());
        assertFalse(result.missingSemantics().isEmpty());
        assertFalse(result.temporalDecision().accepted());
        assertEquals(TemporalCoordination.Owner.NONE, result.temporalDecision().jitterOwner());
    }

    @Test
    void apertureIsOnlyAnUnboundProviderSlot() {
        assertEquals("aperture:shader_bridge", ShaderBridge.APERTURE_PROVIDER_SLOT.id().value());
        assertTrue(
            ShaderBridge.APERTURE_PROVIDER_SLOT.requiredPublicApi().contains("public Aperture")
        );
    }
}
