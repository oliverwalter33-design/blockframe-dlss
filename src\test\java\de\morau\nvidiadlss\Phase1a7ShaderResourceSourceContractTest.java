package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase1a7ShaderResourceSourceContractTest {
    @Test
    void materialSamplerOwnerIsFixedBudgetedAndDeviceScoped()
        throws Exception {
        String policy = source(
            "src/main/java/de/morau/nvidiadlss/DlssSamplerPolicy.java"
        );
        String cache = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "FixedMaterialSamplerCache.java"
        );

        assertTrue(
            policy.contains(
                "static final int MAX_MATERIAL_SAMPLERS = 64"
            )
        );
        assertTrue(
            policy.contains(
                "MemoryCategory.SHADER_RESOURCES"
            )
        );
        assertTrue(
            policy.contains(
                "CACHE_METADATA_COMMITTED_BYTES"
            )
        );
        assertTrue(
            policy.contains(
                "public static synchronized void deviceConnected("
            )
        );
        assertTrue(
            policy.contains("if (device != lifecycleDevice)")
        );
        assertTrue(
            cache.contains(
                "return this.states[slot] == SLOT_LIVE"
            )
        );
        assertTrue(
            cache.contains(
                "SLOT_CLOSE_UNCERTAIN"
            )
        );
        assertFalse(policy.contains("HashMap"));
        assertFalse(policy.contains("IdentityHashMap"));
        assertFalse(policy.contains("SamplerKey"));
        assertFalse(cache.contains("HashMap"));
        assertFalse(cache.contains("SamplerKey"));
        assertFalse(policy.contains("RenderSystem.getDevice()"));
        assertTrue(policy.contains("closePrepareConfirmed"));
        assertTrue(
            policy.contains(
                "device == lifecycleDevice\n"
                    + "                && closePrepareConfirmed\n"
                    + "                && pendingCreationLease == 0L"
            )
        );
        assertTrue(
            policy.contains(
                "RuntimeException\n"
                    + "                | LinkageError\n"
                    + "                | OutOfMemoryError error"
            )
        );
        assertTrue(
            policy.contains(
                "new RuntimeBudgetLeaseController()"
            )
        );
        int createCache = policy.indexOf(
            "private static FixedMaterialSamplerCache createCache("
        );
        int createBiasedSampler = policy.indexOf(
            "private static GpuSampler createBiasedSampler(",
            createCache
        );
        assertTrue(createCache >= 0);
        assertTrue(createBiasedSampler > createCache);
        String creationPath = policy.substring(
            createCache,
            createBiasedSampler
        );
        int reserve = creationPath.indexOf("leases.tryReserve(");
        int cacheAllocation = creationPath.indexOf(
            "new FixedMaterialSamplerCache("
        );
        assertTrue(reserve >= 0);
        assertTrue(cacheAllocation > reserve);
        assertFalse(
            creationPath.contains(
                "new RuntimeBudgetLeaseController()"
            )
        );
        assertTrue(
            creationPath.contains(
                "original fallback: cache allocation failure"
            )
        );
    }

    @Test
    void normalVulkanBindPathDoesNotNormalizeOrAllocateKeys()
        throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanRenderPassMixin.java"
        );
        String policy = source(
            "src/main/java/de/morau/nvidiadlss/DlssSamplerPolicy.java"
        );
        String cache = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "FixedMaterialSamplerCache.java"
        );

        assertTrue(
            mixin.contains(
                "activePipeline.info().getLocation()"
            )
        );
        assertTrue(
            mixin.contains(
                "@ModifyExpressionValue("
            )
        );
        assertTrue(
            mixin.contains(
                "method = \"pushDescriptors\""
            )
        );
        assertTrue(
            mixin.contains(
                "VulkanRenderPass$TextureViewAndSampler;"
            )
        );
        assertTrue(
            mixin.contains(
                "@Local(name = \"entry\")"
            )
        );
        assertTrue(
            mixin.contains(
                "blockAtlasBinding.equals(entry.name())"
            )
        );
        assertFalse(mixin.contains("@ModifyArg("));
        assertFalse(
            mixin.contains(
                "nvidiaDlss$selectedSampler"
            )
        );
        assertTrue(
            mixin.contains("&& FoliageAudit.enabled()")
        );
        assertTrue(
            mixin.contains(
                "DlssTerrainSamplerScope.isBlockAtlas(textureLabel)"
            )
        );
        assertTrue(
            mixin.contains(
                "DlssTerrainSamplerScope.eligible(\n"
                    + "                    pipelineId,"
            )
        );
        assertTrue(
            mixin.contains(
                "DlssTerrainSamplerScope.isCutout(pipelineId)"
            )
        );
        assertFalse(mixin.contains("getLocation().getPath()"));
        assertFalse(mixin.contains(".toLowerCase("));
        assertFalse(policy.contains("new SamplerKey"));
        assertFalse(cache.contains("new SamplerKey"));
        assertFalse(policy.contains("ThreadLocal.withInitial"));
        assertTrue(
            cache.contains(
                "private static final String CAPACITY_FALLBACK"
            )
        );
        assertTrue(cache.contains("firstCloseFailureType"));
        assertTrue(
            cache.contains(
                "Diagnostics must not alter physical ownership "
                    + "transitions."
            )
        );
        assertFalse(
            cache.contains(
                "\"Material sampler queue-for-destroy failed; "
                    + "ownership remains uncertain\",\n"
                    + "                    error"
            )
        );
        int materialSampler = policy.indexOf(
            "public static synchronized GpuSampler materialSampler("
        );
        int deviceConnected = policy.indexOf(
            "public static synchronized void deviceConnected(",
            materialSampler
        );
        assertTrue(materialSampler >= 0);
        assertTrue(deviceConnected > materialSampler);
        assertFalse(
            policy.substring(materialSampler, deviceConnected)
                .contains("catch (Throwable error)")
        );
    }

    @Test
    void everyCurrentMotionObjectHasInventoryTransitions()
        throws Exception {
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "MotionVectorGenerator.java"
        );

        assertTrue(
            motion.contains(
                "ResourceKind.SHADER_MODULE"
            )
        );
        assertTrue(
            motion.contains(
                "ResourceKind.DESCRIPTOR_SET_LAYOUT"
            )
        );
        assertTrue(
            motion.contains(
                "ResourceKind.DESCRIPTOR_POOL"
            )
        );
        assertTrue(
            motion.contains(
                "ResourceKind.DESCRIPTOR_SET"
            )
        );
        assertTrue(
            motion.contains(
                "ResourceKind.DESCRIPTOR"
            )
        );
        assertTrue(
            motion.contains(
                "ResourceKind.PIPELINE_LAYOUT"
            )
        );
        assertTrue(
            motion.contains(
                "ResourceKind.COMPUTE_PIPELINE"
            )
        );
        assertTrue(
            motion.contains(
                "ResourceKind.RAW_DEPTH_SAMPLER"
            )
        );
        assertTrue(
            motion.contains(
                "ResourceKind.MANAGED_UNIFORM_BUFFER"
            )
        );
        assertTrue(
            motion.contains(
                "static final int DESCRIPTOR_COUNT ="
            )
        );
        assertTrue(
            motion.contains(
                "retainedFailedConstruction"
            )
        );
        assertTrue(
            motion.contains(
                "this.descriptorSets[index] = 0L"
            )
        );
        assertTrue(motion.contains("backendAlive"));
        assertTrue(motion.contains("recordCreationOutcome("));
    }

    @Test
    void materialSamplerIsLabelledAndOpenGlCreatesNothing()
        throws Exception {
        String policy = source(
            "src/main/java/de/morau/nvidiadlss/DlssSamplerPolicy.java"
        );
        String glMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "GlRenderPassMixin.java"
        );

        assertTrue(
            policy.contains(
                "VK12.VK_OBJECT_TYPE_SAMPLER"
            )
        );
        assertTrue(
            policy.contains(
                "\"BlockFrame / DLSS Material Sampler \""
            )
        );
        assertFalse(glMixin.contains("DlssSamplerPolicy"));
    }

    @Test
    void samplerFinishPrecedesGlobalRetirementCompletion()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        int finish = renderer.indexOf(
            "finishDeviceCloseAfterEncoderDrain("
        );
        int samplerFinish = renderer.indexOf(
            "DLSS-Materialsampler nach Encoder-Drain",
            finish
        );
        int inventoryCompletion = renderer.indexOf(
            ".shaderResources()",
            samplerFinish
        );
        int budgetCompletion = renderer.indexOf(
            ".memoryBudgets()",
            inventoryCompletion
        );

        assertTrue(finish >= 0);
        assertTrue(samplerFinish > finish);
        assertTrue(inventoryCompletion > samplerFinish);
        assertTrue(budgetCompletion > inventoryCompletion);
        assertTrue(
            renderer.contains(
                "samplerCloseFinished"
            )
        );
        assertTrue(renderer.contains("deviceGenerationBlocked"));
        assertTrue(renderer.contains("public static void deviceClosed("));
        assertTrue(
            renderer.contains(
                "DlssSamplerPolicy::clearClientThreadState"
            )
        );
        assertTrue(
            renderer.contains(
                "if (deviceGenerationBlocked)"
            )
        );
        String deviceMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanDeviceMixin.java"
        );
        assertTrue(deviceMixin.contains("@WrapMethod(method = \"close\")"));
        assertTrue(deviceMixin.contains("finally {"));
        assertTrue(deviceMixin.contains("DlssRenderer.deviceClosed("));
    }

    private static String source(String relativePath)
        throws Exception {
        Path root = Path.of(
            System.getProperty("blockframe.projectDir")
        );
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
