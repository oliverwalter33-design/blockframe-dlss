package de.morau.blockframe.api;

import java.util.Objects;

/**
 * Explicit ownership boundary for a provider.
 *
 * @param providerInstance owner responsible for invoking {@code close()}
 * @param internalResources owner responsible for provider-created resources
 * @param inputs whether input handles are borrowed or may be retained
 */
public record ProviderOwnership(
    Owner providerInstance,
    Owner internalResources,
    InputRetention inputs
) {
    public ProviderOwnership {
        providerInstance = Objects.requireNonNull(providerInstance, "providerInstance");
        internalResources = Objects.requireNonNull(internalResources, "internalResources");
        inputs = Objects.requireNonNull(inputs, "inputs");
        if (providerInstance == Owner.NONE) {
            throw new IllegalArgumentException("A provider instance must have an owner");
        }
    }

    public static ProviderOwnership engineManagedWithoutResources() {
        return new ProviderOwnership(
            Owner.BLOCKFRAME_ENGINE,
            Owner.NONE,
            InputRetention.BORROWED_FOR_CALL
        );
    }

    public static ProviderOwnership engineManagedProviderResources() {
        return new ProviderOwnership(
            Owner.BLOCKFRAME_ENGINE,
            Owner.PROVIDER,
            InputRetention.BORROWED_FOR_CALL
        );
    }

    public enum Owner {
        BLOCKFRAME_ENGINE,
        PROVIDER,
        CALLER,
        NONE
    }

    public enum InputRetention {
        BORROWED_FOR_CALL,
        RETAINED_ONLY_BY_EXPLICIT_LEASE,
        NOT_APPLICABLE
    }
}
