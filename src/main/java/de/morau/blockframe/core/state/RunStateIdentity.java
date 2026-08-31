package de.morau.blockframe.core.state;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, non-personal identity used to decide whether a previous run is
 * relevant to the current configuration.
 */
public record RunStateIdentity(
    String modVersion,
    String minecraftVersion,
    String configFingerprint,
    int featureSchemaVersion,
    long requestedFeatureMask,
    long normalEffectiveFeatureMask,
    long safeStartEffectiveFeatureMask
) {
    private static final Pattern VERSION =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    public RunStateIdentity {
        modVersion = validatedVersion(modVersion, "modVersion");
        minecraftVersion = validatedVersion(
            minecraftVersion,
            "minecraftVersion"
        );
        configFingerprint = Objects.requireNonNull(
            configFingerprint,
            "configFingerprint"
        );
        if (!DIGEST.matcher(configFingerprint).matches()) {
            throw new IllegalArgumentException(
                "configFingerprint must be a lowercase SHA-256 digest"
            );
        }
        if (featureSchemaVersion <= 0 || featureSchemaVersion > 65_535) {
            throw new IllegalArgumentException(
                "featureSchemaVersion must be in 1..65535"
            );
        }
        if (
            (normalEffectiveFeatureMask & ~requestedFeatureMask) != 0L
                || (safeStartEffectiveFeatureMask & ~requestedFeatureMask)
                    != 0L
        ) {
            throw new IllegalArgumentException(
                "effective feature masks must be subsets of requestedFeatureMask"
            );
        }
    }

    public RunStateIdentity(
        String modVersion,
        String minecraftVersion,
        String configFingerprint,
        long requestedFeatureMask,
        long normalEffectiveFeatureMask,
        long safeStartEffectiveFeatureMask
    ) {
        this(
            modVersion,
            minecraftVersion,
            configFingerprint,
            1,
            requestedFeatureMask,
            normalEffectiveFeatureMask,
            safeStartEffectiveFeatureMask
        );
    }

    private static String validatedVersion(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (!VERSION.matcher(checked).matches()) {
            throw new IllegalArgumentException(
                name + " must be a bounded stable version token"
            );
        }
        return checked;
    }
}
