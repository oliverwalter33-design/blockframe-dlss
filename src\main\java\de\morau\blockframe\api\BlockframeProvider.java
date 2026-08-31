package de.morau.blockframe.api;

/**
 * Common Phase-1 negotiation and lifecycle boundary.
 *
 * <p>The Blockframe engine owns the provider instance unless
 * {@link #ownership()} says otherwise and invokes {@link #close()} once during
 * shutdown or replacement. Providers own and release only their internal
 * resources. Backend/world inputs are borrowed for a call and must not be
 * retained unless an explicit future lease says so. Capability and lifecycle
 * values are immutable snapshots owned by the caller.
 *
 * <p>This interface deliberately contains no Minecraft, NeoForge, LWJGL, or
 * Vulkan types. Backend-specific adapters remain outside the stable API.
 */
public interface BlockframeProvider<C> extends AutoCloseable {
    ProviderId id();

    Availability availability();

    C capabilities();

    ProviderLifecycle lifecycle();

    ProviderOwnership ownership();

    @Override
    void close();
}
