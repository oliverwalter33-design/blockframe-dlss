package de.morau.blockframe.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stable shader semantic and geometry-compatibility contract.
 *
 * <p>The Aperture entry is a provider discovery slot only. No adapter or
 * availability claim exists until a stable public Aperture API is available.
 */
public interface ShaderBridge extends BlockframeProvider<ShaderBridge.Capabilities> {
    ProviderSlot APERTURE_PROVIDER_SLOT = new ProviderSlot(
        new ProviderId("aperture:shader_bridge"),
        "stable public Aperture shader-bridge API"
    );

    Negotiation negotiate(Request request);

    record Capabilities(
        Set<Semantic> semantics,
        Set<GeometryFrontend.Feature> compatibleGeometryFeatures
    ) {
        public Capabilities {
            semantics = immutableSemantics(semantics);
            compatibleGeometryFeatures = immutableFeatures(compatibleGeometryFeatures);
        }

        public Set<Semantic> missing(Set<Semantic> required) {
            Objects.requireNonNull(required, "required");
            EnumSet<Semantic> missing = required.isEmpty()
                ? EnumSet.noneOf(Semantic.class)
                : EnumSet.copyOf(required);
            missing.removeAll(this.semantics);
            return missing.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(missing);
        }

        public Set<Semantic> missingFullContract() {
            return this.missing(EnumSet.allOf(Semantic.class));
        }
    }

    record Request(
        Set<Semantic> requiredSemantics,
        GeometryFrontend.Capabilities geometryFrontend,
        GeometryFrontend.ShaderPackConstraints shaderPackConstraints,
        TemporalCoordination.Request temporalRequest
    ) {
        public Request {
            requiredSemantics = immutableSemantics(requiredSemantics);
            geometryFrontend = Objects.requireNonNull(geometryFrontend, "geometryFrontend");
            shaderPackConstraints = Objects.requireNonNull(shaderPackConstraints, "shaderPackConstraints");
            temporalRequest = Objects.requireNonNull(temporalRequest, "temporalRequest");
        }
    }

    record Negotiation(
        Availability availability,
        Set<Semantic> missingSemantics,
        GeometryFrontend.Plan geometryPlan,
        TemporalCoordination.Decision temporalDecision
    ) {
        public Negotiation {
            availability = Objects.requireNonNull(availability, "availability");
            missingSemantics = immutableSemantics(missingSemantics);
            geometryPlan = Objects.requireNonNull(geometryPlan, "geometryPlan");
            temporalDecision = Objects.requireNonNull(temporalDecision, "temporalDecision");
            if (availability.usable() && (!missingSemantics.isEmpty() || !temporalDecision.accepted())) {
                throw new IllegalArgumentException("Usable shader negotiation cannot omit required semantics");
            }
        }
    }

    record ProviderSlot(ProviderId id, String requiredPublicApi) {
        public ProviderSlot {
            id = Objects.requireNonNull(id, "id");
            requiredPublicApi = Objects.requireNonNull(requiredPublicApi, "requiredPublicApi").trim();
            if (requiredPublicApi.isEmpty()) {
                throw new IllegalArgumentException("requiredPublicApi must not be blank");
            }
        }
    }

    /**
     * Shared pure negotiation used by concrete bridge descriptors. It never
     * upgrades provider availability and rejects missing semantics.
     */
    static Negotiation evaluate(
        Availability providerAvailability,
        Capabilities capabilities,
        Request request
    ) {
        Objects.requireNonNull(providerAvailability, "providerAvailability");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(request, "request");

        Set<Semantic> missing = capabilities.missing(request.requiredSemantics());
        TemporalCoordination.Decision temporal =
            TemporalCoordination.negotiate(request.temporalRequest());
        GeometryFrontend.Plan geometry = GeometryFrontend.Plan.negotiate(
            request.geometryFrontend(),
            capabilities.compatibleGeometryFeatures(),
            request.shaderPackConstraints()
        );

        List<Reason> reasons = new ArrayList<>(providerAvailability.reasons());
        if (!missing.isEmpty()) {
            reasons.add(new Reason(
                "MISSING_SHADER_SEMANTICS",
                "Missing required shader semantics: " + missing
            ));
        }
        if (!temporal.accepted()) {
            reasons.addAll(temporal.availability().reasons());
        }

        Availability result;
        if (providerAvailability.state() == Availability.State.UNAVAILABLE
            || !missing.isEmpty()
            || !temporal.accepted()) {
            result = new Availability(Availability.State.UNAVAILABLE, reasons);
        } else {
            result = providerAvailability;
        }
        return new Negotiation(result, missing, geometry, temporal);
    }

    enum Semantic {
        POSITION,
        UV,
        LIGHTMAP,
        COLOR,
        NORMAL,
        TANGENT,
        MATERIAL_ID,
        BLOCK_ID,
        BIOME_ID,
        ENTITY_ID,
        CURRENT_MODEL_MATRIX,
        PREVIOUS_MODEL_MATRIX,
        CURRENT_CAMERA_MATRIX,
        PREVIOUS_CAMERA_MATRIX,
        REVERSED_Z_DEPTH,
        MOTION_VECTORS,
        CAMERA_JITTER,
        EXPOSURE,
        REACTIVE_MASK,
        TRANSPARENCY_MASK,
        SHADOW_PASS
    }

    private static Set<Semantic> immutableSemantics(Set<Semantic> semantics) {
        Objects.requireNonNull(semantics, "semantics");
        if (semantics.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(semantics));
    }

    private static Set<GeometryFrontend.Feature> immutableFeatures(
        Set<GeometryFrontend.Feature> features
    ) {
        Objects.requireNonNull(features, "features");
        if (features.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(features));
    }
}
