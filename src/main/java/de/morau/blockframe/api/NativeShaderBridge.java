package de.morau.blockframe.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * Capability inventory for the existing native DLSS handoff.
 *
 * <p>This is deliberately unavailable as a terrain shader bridge: the current
 * code has camera history, reversed-Z depth, motion, jitter, and a transparency
 * hint, but no native geometry frontend or complete shader semantics. Auto
 * exposure is not reported as an explicit exposure input, and the existing
 * bias-current-color hint is not reported as a reactive mask.
 */
public final class NativeShaderBridge implements ShaderBridge {
    public static final ProviderId ID = new ProviderId("blockframe:shader/native");
    private static final Capabilities CAPABILITIES = new Capabilities(
        EnumSet.of(
            Semantic.CURRENT_CAMERA_MATRIX,
            Semantic.PREVIOUS_CAMERA_MATRIX,
            Semantic.REVERSED_Z_DEPTH,
            Semantic.MOTION_VECTORS,
            Semantic.CAMERA_JITTER,
            Semantic.TRANSPARENCY_MASK
        ),
        Set.of()
    );
    private static final Availability AVAILABILITY = Availability.unavailable(
        new Reason(
            "NATIVE_GEOMETRY_BRIDGE_NOT_IMPLEMENTED",
            "The existing native path evaluates DLSS/DLAA but is not a terrain shader bridge"
        ),
        new Reason(
            "NATIVE_SHADER_CONTRACT_PARTIAL",
            "Missing full contract semantics: " + CAPABILITIES.missingFullContract()
        )
    );

    private volatile boolean closed;

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Availability availability() {
        return this.closed
            ? Availability.unavailable(new Reason("PROVIDER_CLOSED", "Native shader bridge is closed"))
            : AVAILABILITY;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public ProviderLifecycle lifecycle() {
        return this.closed
            ? ProviderLifecycle.closed(0L)
            : new ProviderLifecycle(ProviderLifecycle.State.UNAVAILABLE, 0L);
    }

    @Override
    public ProviderOwnership ownership() {
        return ProviderOwnership.engineManagedWithoutResources();
    }

    @Override
    public Negotiation negotiate(Request request) {
        return ShaderBridge.evaluate(this.availability(), CAPABILITIES, request);
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
