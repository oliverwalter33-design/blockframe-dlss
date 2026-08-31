package de.morau.blockframe.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * Honest descriptor for the currently observed vanilla vertex contract.
 *
 * <p>It borrows the existing renderer and owns no GPU resources. It does not
 * claim tangents, stable material IDs, temporal inputs, or the future
 * GPU-driven geometry facilities.
 */
public final class VanillaShaderBridge implements ShaderBridge {
    public static final ProviderId ID = new ProviderId("blockframe:shader/vanilla");
    private static final Capabilities CAPABILITIES = new Capabilities(
        EnumSet.of(
            Semantic.POSITION,
            Semantic.UV,
            Semantic.LIGHTMAP,
            Semantic.COLOR,
            Semantic.NORMAL
        ),
        Set.of()
    );
    private static final Availability AVAILABILITY = Availability.degraded(new Reason(
        "VANILLA_SHADER_CONTRACT_PARTIAL",
        "Vanilla exposes the classic vertex subset; missing full contract semantics: "
            + CAPABILITIES.missingFullContract()
    ));

    private volatile boolean closed;

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Availability availability() {
        return this.closed
            ? Availability.unavailable(new Reason("PROVIDER_CLOSED", "Vanilla shader bridge is closed"))
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
            : new ProviderLifecycle(ProviderLifecycle.State.DEGRADED, 0L);
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
