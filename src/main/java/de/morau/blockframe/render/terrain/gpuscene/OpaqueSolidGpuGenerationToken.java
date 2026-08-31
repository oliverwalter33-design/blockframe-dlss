package de.morau.blockframe.render.terrain.gpuscene;

/**
 * Immutable ownership proof for one exact opaque-solid draw template.
 *
 * <p>Every value is captured at an owner mutation boundary. A token is never
 * refreshed by polling the live Vulkan objects. Any owner generation change
 * makes it unusable until a new token is published.</p>
 */
public record OpaqueSolidGpuGenerationToken(
    long deviceGeneration,
    long rendererGeneration,
    long worldGeneration,
    long sectionMeshGeneration,
    long vertexBufferGeneration,
    long vertexRangeGeneration,
    long indexBufferGeneration,
    long indexRangeGeneration,
    long sectionNode,
    long vertexBufferHandle,
    long vertexOffset,
    long vertexLength,
    long indexBufferHandle,
    long indexOffset,
    long indexLength,
    int indexBindingKey,
    int indexTypeKey,
    int indexCount,
    int baseVertex,
    int pipelineKey,
    int shaderAbiKey,
    int materialKey
) {
    public static final int INDEX_BINDING_CUSTOM = 1;
    public static final int INDEX_BINDING_SEQUENTIAL_QUAD = 2;

    public OpaqueSolidGpuGenerationToken {
        requirePositive(deviceGeneration, "deviceGeneration");
        requirePositive(rendererGeneration, "rendererGeneration");
        requirePositive(worldGeneration, "worldGeneration");
        requirePositive(sectionMeshGeneration, "sectionMeshGeneration");
        requirePositive(vertexBufferGeneration, "vertexBufferGeneration");
        requirePositive(vertexRangeGeneration, "vertexRangeGeneration");
        requirePositive(indexBufferGeneration, "indexBufferGeneration");
        requirePositive(indexRangeGeneration, "indexRangeGeneration");
        requirePositive(vertexBufferHandle, "vertexBufferHandle");
        requireRange(vertexOffset, vertexLength, "vertex");
        if (
            indexBindingKey != INDEX_BINDING_CUSTOM
                && indexBindingKey != INDEX_BINDING_SEQUENTIAL_QUAD
        ) {
            throw new IllegalArgumentException("unknown index binding");
        }
        if (indexBindingKey == INDEX_BINDING_CUSTOM) {
            requirePositive(indexBufferHandle, "indexBufferHandle");
            requireRange(indexOffset, indexLength, "index");
        } else if (
            indexBufferHandle != 0L
                || indexOffset != 0L
                || indexLength != 0L
        ) {
            throw new IllegalArgumentException(
                "sequential index binding must not capture a stale handle"
            );
        }
        if (indexTypeKey <= 0 || indexCount <= 0) {
            throw new IllegalArgumentException("invalid indexed draw");
        }
        if (
            pipelineKey == 0
                || shaderAbiKey == 0
                || materialKey == 0
        ) {
            throw new IllegalArgumentException(
                "pipeline, shader ABI and material keys are required"
            );
        }
    }

    public boolean matches(OwnerEpochs epochs) {
        return epochs != null
            && this.deviceGeneration == epochs.deviceGeneration()
            && this.rendererGeneration == epochs.rendererGeneration()
            && this.worldGeneration == epochs.worldGeneration()
            && this.sectionMeshGeneration
                == epochs.sectionMeshGeneration()
            && this.vertexBufferGeneration
                == epochs.vertexBufferGeneration()
            && this.vertexRangeGeneration
                == epochs.vertexRangeGeneration()
            && this.indexBufferGeneration
                == epochs.indexBufferGeneration()
            && this.indexRangeGeneration
                == epochs.indexRangeGeneration()
            && this.pipelineKey == epochs.pipelineKey()
            && this.shaderAbiKey == epochs.shaderAbiKey()
            && this.materialKey == epochs.materialKey();
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireRange(
        long offset,
        long length,
        String name
    ) {
        if (
            offset < 0L
                || length <= 0L
                || offset > Long.MAX_VALUE - length
        ) {
            throw new IllegalArgumentException(
                "invalid " + name + " range"
            );
        }
    }

    public record OwnerEpochs(
        long deviceGeneration,
        long rendererGeneration,
        long worldGeneration,
        long sectionMeshGeneration,
        long vertexBufferGeneration,
        long vertexRangeGeneration,
        long indexBufferGeneration,
        long indexRangeGeneration,
        int pipelineKey,
        int shaderAbiKey,
        int materialKey
    ) {
    }
}
