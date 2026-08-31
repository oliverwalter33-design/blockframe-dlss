package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.BudgetAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Configuration;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.ControlledFixtureAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.CreationCleanupAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.CreationFailureAction;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.ExclusiveWorldFactoryAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.FormatAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.FrameOutputAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Phase;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Preflight;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.RejectionReason;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.RendererApi;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.RequestedBackend;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.SelectedBackend;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Selection;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.WorldResourceCreationPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.ActivationAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainDeviceCapabilityNegotiator.Attestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainDeviceCapabilityNegotiator.Limits;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainDeviceCapabilityNegotiator.Probe;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainDeviceCapabilityNegotiator.QueueCapabilities;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK12;

class NativeTerrainBackendSelectorTest {
    @Test
    void configurationIsExactRestartBoundAndDefaultsToMojang() {
        Configuration missing = Configuration.parse(null);
        assertEquals(
            RequestedBackend.MOJANG_REFERENCE,
            missing.requestedBackend()
        );
        assertTrue(missing.recognized());
        assertTrue(missing.restartRequired());

        Configuration nativeExperimental =
            Configuration.parse("native-experimental");
        assertTrue(nativeExperimental.nativeExperimentalRequested());
        assertTrue(nativeExperimental.restartRequired());

        Configuration invalid =
            Configuration.parse("Native-Experimental");
        assertFalse(invalid.recognized());
        assertEquals(
            RequestedBackend.MOJANG_REFERENCE,
            invalid.requestedBackend()
        );
    }

    @Test
    void productionDefaultSelectsMojangWithoutNativePreflight() {
        NativeTerrainBackendSelector selector =
            new NativeTerrainBackendSelector();

        Selection selection = selector.selectBeforeWorldResources(
            Configuration.parse(null),
            null
        );

        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selection.backend()
        );
        assertFalse(selection.preflightEvaluated());
        assertEquals(
            List.of(RejectionReason.CONFIGURATION_SELECTS_MOJANG),
            selection.rejectionReasons()
        );
        WorldResourceCreationPermit permit =
            selector.beginWorldResourceCreation();
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            permit.backend()
        );
        selector.completeWorldResourceCreation(permit);
        assertEquals(
            Phase.WORLD_RESOURCES_ACTIVE,
            selector.phase()
        );
        selector.completeWorldResourceRetirement();
        assertEquals(Phase.SELECTED, selector.phase());
        selector.close();
        assertEquals(Phase.CLOSED, selector.phase());
    }

    @Test
    void preOwnerPreflightEvaluationFailureSealsMojang() {
        NativeTerrainBackendSelector selector =
            new NativeTerrainBackendSelector();
        Configuration requested =
            Configuration.parse(
                NativeTerrainBackendSelector
                    .NATIVE_CONFIGURATION_VALUE
            );

        Selection selection =
            selector.failClosedBeforeWorldResources(
                requested,
                "injected-preflight-failure"
            );

        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selection.backend()
        );
        assertEquals(
            List.of(RejectionReason.PREFLIGHT_EVALUATION_FAILED),
            selection.rejectionReasons()
        );
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selector.beginWorldResourceCreation().backend()
        );
    }

    @Test
    void completePreflightAtomicallySelectsExperimentalNativeBackend() {
        NativeTerrainBackendSelector selector =
            new NativeTerrainBackendSelector();

        Selection selection = selector.selectBeforeWorldResources(
            Configuration.parse("native-experimental"),
            readyPreflight()
        );

        assertTrue(selection.nativeBackendSelected());
        assertTrue(selection.preflightEvaluated());
        assertTrue(selection.rejectionReasons().isEmpty());
        WorldResourceCreationPermit permit =
            selector.beginWorldResourceCreation();
        assertEquals(
            SelectedBackend.BLOCKFRAME_NATIVE_EXPERIMENTAL,
            permit.backend()
        );
        assertEquals(generations(), permit.plannedGenerations());
        selector.completeWorldResourceCreation(permit);
    }

    @Test
    void anyPreflightFailureCreatesOnlyAMojangPermit() {
        NativeTerrainBackendSelector selector =
            new NativeTerrainBackendSelector();
        Attestation wrongGeneration = readyCapability(2L);
        ActivationAttestation incompatibleCensus =
            new ActivationAttestation(
                generations().resources(),
                new Digest(21L, 22L, 23L, 24L),
                false,
                3,
                false,
                List.of(
                    new NativeTerrainAssetCensus.Blocker(
                        NativeTerrainAssetCensus.BlockerReason
                            .UNSUPPORTED_ASSET,
                        NativeTerrainTestFixtures.id(90L),
                        NativeTerrainAssetCensus.Category.UNSUPPORTED
                    )
                )
            );
        Preflight rejected = new Preflight(
            generations(),
            RendererApi.OPENGL,
            wrongGeneration,
            incompatibleCensus,
            new FormatAttestation(
                TerrainMeshProducerABI.VERSION + 1,
                false,
                false,
                false,
                false,
                "format-contract-unavailable"
            ),
            new BudgetAttestation(
                2_048L,
                1_024L,
                4_096L,
                2_048L,
                true
            ),
            readyExclusiveWorldFactory(),
            readyFrameOutput(),
            readyControlledFixture(),
            true,
            true,
            "previous-native-crash"
        );

        Selection selection = selector.selectBeforeWorldResources(
            Configuration.parse("native-experimental"),
            rejected
        );

        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selection.backend()
        );
        assertTrue(
            selection.rejectionReasons().contains(
                RejectionReason.NOT_VULKAN
            )
        );
        assertTrue(
            selection.rejectionReasons().contains(
                RejectionReason
                    .CAPABILITY_DEVICE_GENERATION_MISMATCH
            )
        );
        assertTrue(
            selection.rejectionReasons().contains(
                RejectionReason.TERRAIN_ABI_VERSION_UNSUPPORTED
            )
        );
        assertTrue(
            selection.rejectionReasons().contains(
                RejectionReason.ASSET_CENSUS_INCOMPLETE
            )
        );
        assertTrue(
            selection.rejectionReasons().contains(
                RejectionReason
                    .ASSET_OR_RENDER_TYPE_UNSUPPORTED
            )
        );
        assertTrue(
            selection.rejectionReasons().contains(
                RejectionReason.RAM_BUDGET_UNAVAILABLE
            )
        );
        assertTrue(
            selection.rejectionReasons().contains(
                RejectionReason.VRAM_BUDGET_UNAVAILABLE
            )
        );
        assertTrue(
            selection.rejectionReasons().contains(
                RejectionReason.SAFE_START_ACTIVE
            )
        );
        assertTrue(
            selection.rejectionReasons().contains(
                RejectionReason.NATIVE_BACKEND_QUARANTINED
            )
        );
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selector.beginWorldResourceCreation().backend()
        );
    }

    @Test
    void capabilityAndCensusGenerationsMustMatchPlannedOwners() {
        NativeTerrainBackendSelector capabilityMismatch =
            new NativeTerrainBackendSelector();
        Preflight wrongCapability = replaceCapabilities(
            readyPreflight(),
            readyCapability(2L)
        );
        assertTrue(
            capabilityMismatch
                .selectBeforeWorldResources(
                    Configuration.parse("native-experimental"),
                    wrongCapability
                )
                .rejectionReasons()
                .contains(
                    RejectionReason
                        .CAPABILITY_DEVICE_GENERATION_MISMATCH
                )
        );

        NativeTerrainBackendSelector censusMismatch =
            new NativeTerrainBackendSelector();
        Preflight base = readyPreflight();
        Preflight wrongCensus = new Preflight(
            base.plannedGenerations(),
            base.rendererApi(),
            base.capabilities(),
            new ActivationAttestation(
                base.assetCensus().resourceGeneration() + 1L,
                base.assetCensus().censusDigest(),
                true,
                2,
                true,
                List.of()
            ),
            base.formats(),
            base.budget(),
            base.exclusiveWorldFactory(),
            base.frameOutput(),
            base.controlledFixture(),
            false,
            false,
            ""
        );
        assertTrue(
            censusMismatch
                .selectBeforeWorldResources(
                    Configuration.parse("native-experimental"),
                    wrongCensus
                )
                .rejectionReasons()
                .contains(
                    RejectionReason
                        .CENSUS_RESOURCE_GENERATION_MISMATCH
                )
        );
    }

    @Test
    void selectionCannotPromoteOrChangeWithoutRestart() {
        NativeTerrainBackendSelector selector =
            new NativeTerrainBackendSelector();
        selector.selectBeforeWorldResources(
            Configuration.parse("mojang"),
            null
        );

        assertThrows(
            IllegalStateException.class,
            () -> selector.selectBeforeWorldResources(
                Configuration.parse("native-experimental"),
                readyPreflight()
            )
        );
    }

    @Test
    void partialNativeCreationNeverStartsMojangBeforeCleanup() {
        NativeTerrainBackendSelector cleanupRequired =
            nativeSelector();
        WorldResourceCreationPermit dirtyPermit =
            cleanupRequired.beginWorldResourceCreation();

        assertEquals(
            CreationFailureAction
                .RETRY_NATIVE_CLEANUP_BEFORE_ANY_BACKEND_CREATION,
            cleanupRequired.abortWorldResourceCreation(
                dirtyPermit,
                new CreationCleanupAttestation(
                    generations().device(),
                    1L,
                    2L,
                    3,
                    false
                )
            )
        );
        assertEquals(
            Phase.QUARANTINED_CLEANUP_REQUIRED,
            cleanupRequired.phase()
        );
        assertThrows(
            IllegalStateException.class,
            cleanupRequired::beginWorldResourceCreation
        );

        NativeTerrainBackendSelector released = nativeSelector();
        WorldResourceCreationPermit cleanPermit =
            released.beginWorldResourceCreation();
        assertEquals(
            CreationFailureAction.REBUILD_MOJANG_BEFORE_WORLD_ENTRY,
            released.abortWorldResourceCreation(
                cleanPermit,
                new CreationCleanupAttestation(
                    generations().device(),
                    0L,
                    0L,
                    0,
                    true
                )
            )
        );
        assertEquals(
            Phase.SELECTED,
            released.phase()
        );
        WorldResourceCreationPermit fallback =
            released.beginWorldResourceCreation();
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            fallback.backend()
        );
    }

    @Test
    void creationPermitsAreOwnerBound() {
        NativeTerrainBackendSelector first = nativeSelector();
        NativeTerrainBackendSelector second = nativeSelector();
        WorldResourceCreationPermit foreign =
            first.beginWorldResourceCreation();
        second.beginWorldResourceCreation();

        assertThrows(
            IllegalArgumentException.class,
            () -> second.completeWorldResourceCreation(foreign)
        );
    }

    @Test
    void failedReferenceCreationCanRetryWithoutChangingBackend() {
        NativeTerrainBackendSelector selector =
            new NativeTerrainBackendSelector();
        selector.selectBeforeWorldResources(
            Configuration.parse("mojang"),
            null
        );
        WorldResourceCreationPermit failed =
            selector.beginWorldResourceCreation();
        selector.abortReferenceWorldResourceCreation(failed);
        assertEquals(Phase.SELECTED, selector.phase());
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selector.beginWorldResourceCreation().backend()
        );
    }

    @Test
    void incompleteNativeCleanupRetainsPermitUntilCleanFallback() {
        NativeTerrainBackendSelector selector = nativeSelector();
        WorldResourceCreationPermit permit =
            selector.beginWorldResourceCreation();

        assertEquals(
            CreationFailureAction
                .RETRY_NATIVE_CLEANUP_BEFORE_ANY_BACKEND_CREATION,
            selector.abortWorldResourceCreation(
                permit,
                new CreationCleanupAttestation(
                    generations().device(),
                    1L,
                    2L,
                    3,
                    false
                )
            )
        );
        assertEquals(
            CreationFailureAction
                .RETRY_NATIVE_CLEANUP_BEFORE_ANY_BACKEND_CREATION,
            selector.retryWorldResourceCreationCleanup(
                permit,
                new CreationCleanupAttestation(
                    generations().device(),
                    1L,
                    0L,
                    1,
                    false
                )
            )
        );
        assertEquals(
            CreationFailureAction.REBUILD_MOJANG_BEFORE_WORLD_ENTRY,
            selector.retryWorldResourceCreationCleanup(
                permit,
                new CreationCleanupAttestation(
                    generations().device(),
                    0L,
                    0L,
                    0,
                    true
                )
            )
        );
        assertEquals(Phase.SELECTED, selector.phase());
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selector.selection().backend()
        );
    }

    @Test
    void mojangPermitCannotRequestNativeWorldRevalidation() {
        NativeTerrainBackendSelector selector =
            new NativeTerrainBackendSelector();
        selector.selectBeforeWorldResources(
            Configuration.parse("mojang"),
            null
        );
        WorldResourceCreationPermit permit =
            selector.beginWorldResourceCreation();
        selector.completeWorldResourceCreation(permit);

        assertThrows(
            IllegalStateException.class,
            () -> selector
                .completeWorldResourceRetirementForRevalidation(
                    permit
                )
        );
        assertEquals(
            Phase.WORLD_RESOURCES_ACTIVE,
            selector.phase()
        );
        selector.completeWorldResourceRetirement();
    }

    @Test
    void worldRetirementRequiresFreshRevalidatedGenerations() {
        NativeTerrainBackendSelector selector = nativeSelector();
        WorldResourceCreationPermit first =
            selector.beginWorldResourceCreation();
        selector.completeWorldResourceCreation(first);
        selector.completeWorldResourceRetirementForRevalidation(
            first
        );

        assertEquals(
            Phase.WORLD_REVALIDATION_REQUIRED,
            selector.phase()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> selector.revalidateBeforeWorldResources(
                readyPreflight(generations())
            )
        );
        GenerationStamp fresh = nextGenerations(generations());
        Selection revalidated =
            selector.revalidateBeforeWorldResources(
                readyPreflight(fresh)
            );

        assertTrue(revalidated.nativeBackendSelected());
        WorldResourceCreationPermit second =
            selector.beginWorldResourceCreation();
        assertEquals(fresh, second.plannedGenerations());
        assertThrows(
            IllegalArgumentException.class,
            () -> selector.completeWorldResourceCreation(first)
        );
    }

    @Test
    void publishedFailureIsCentrallyQuarantinedUntilClose() {
        NativeTerrainBackendSelector selector = nativeSelector();
        WorldResourceCreationPermit permit =
            selector.beginWorldResourceCreation();
        selector.completeWorldResourceCreation(permit);

        selector.quarantinePublishedWorldResources(
            permit,
            "post-publish-fixture"
        );

        assertEquals(
            Phase.WORLD_RESOURCES_QUARANTINED,
            selector.phase()
        );
        assertEquals(
            "post-publish-fixture",
            selector.quarantineReason()
        );
        assertThrows(
            IllegalStateException.class,
            selector::beginWorldResourceCreation
        );
        selector.completeQuarantinedWorldResourceRetirement(
            permit,
            new CreationCleanupAttestation(
                generations().device(),
                0L,
                0L,
                0,
                true
            )
        );
        assertEquals(Phase.QUARANTINED, selector.phase());
        selector.close();
        assertEquals(Phase.CLOSED, selector.phase());
    }

    @Test
    void allExclusiveActivationAttestationsAreMandatory() {
        Preflight ready = readyPreflight();
        Preflight missing = new Preflight(
            ready.plannedGenerations(),
            ready.rendererApi(),
            ready.capabilities(),
            ready.assetCensus(),
            ready.formats(),
            ready.budget(),
            new ExclusiveWorldFactoryAttestation(
                false,
                false,
                false,
                false,
                false,
                "exclusive-routing-unavailable"
            ),
            new FrameOutputAttestation(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                "typed-frame-output-unavailable"
            ),
            new ControlledFixtureAttestation(
                false,
                false,
                false,
                false,
                "controlled-fixture-unavailable"
            ),
            false,
            false,
            ""
        );
        Selection rejected =
            new NativeTerrainBackendSelector()
                .selectBeforeWorldResources(
                    Configuration.parse("native-experimental"),
                    missing
                );

        assertTrue(
            rejected.rejectionReasons().contains(
                RejectionReason.EXCLUSIVE_WORLD_FACTORY_UNAVAILABLE
            )
        );
        assertTrue(
            rejected.rejectionReasons().contains(
                RejectionReason.FRAME_OUTPUT_ABI_UNAVAILABLE
            )
        );
        assertTrue(
            rejected.rejectionReasons().contains(
                RejectionReason.CONTROLLED_FIXTURE_UNAVAILABLE
            )
        );
    }

    private static NativeTerrainBackendSelector nativeSelector() {
        NativeTerrainBackendSelector selector =
            new NativeTerrainBackendSelector();
        selector.selectBeforeWorldResources(
            Configuration.parse("native-experimental"),
            readyPreflight()
        );
        return selector;
    }

    private static Preflight readyPreflight() {
        return readyPreflight(generations());
    }

    private static Preflight readyPreflight(
        GenerationStamp plannedGenerations
    ) {
        return new Preflight(
            plannedGenerations,
            RendererApi.VULKAN,
            readyCapability(plannedGenerations.device()),
            new ActivationAttestation(
                plannedGenerations.resources(),
                new Digest(11L, 12L, 13L, 14L),
                true,
                2,
                true,
                List.of()
            ),
            new FormatAttestation(
                TerrainMeshProducerABI.VERSION,
                true,
                true,
                true,
                true,
                ""
            ),
            new BudgetAttestation(
                2_048L,
                8_192L,
                4_096L,
                16_384L,
                true
            ),
            readyExclusiveWorldFactory(),
            readyFrameOutput(),
            readyControlledFixture(),
            false,
            false,
            ""
        );
    }

    private static Preflight replaceCapabilities(
        Preflight original,
        Attestation capabilities
    ) {
        return new Preflight(
            original.plannedGenerations(),
            original.rendererApi(),
            capabilities,
            original.assetCensus(),
            original.formats(),
            original.budget(),
            original.exclusiveWorldFactory(),
            original.frameOutput(),
            original.controlledFixture(),
            original.safeStart(),
            original.quarantined(),
            original.quarantineReason()
        );
    }

    private static ExclusiveWorldFactoryAttestation
    readyExclusiveWorldFactory() {
        return new ExclusiveWorldFactoryAttestation(
            true,
            true,
            true,
            true,
            true,
            ""
        );
    }

    private static FrameOutputAttestation readyFrameOutput() {
        return new FrameOutputAttestation(
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            ""
        );
    }

    private static ControlledFixtureAttestation
    readyControlledFixture() {
        return new ControlledFixtureAttestation(
            true,
            true,
            true,
            true,
            ""
        );
    }

    private static GenerationStamp nextGenerations(
        GenerationStamp previous
    ) {
        return new GenerationStamp(
            previous.device(),
            previous.renderer() + 1L,
            previous.world() + 1L,
            previous.resources() + 1L,
            previous.producer() + 1L,
            previous.sectionMesh() + 1L
        );
    }

    private static Attestation readyCapability(
        long deviceGeneration
    ) {
        return NativeTerrainDeviceCapabilityNegotiator.configure(
            deviceGeneration,
            true,
            true,
            supportedProbe(),
            new HashSet<>(),
            new HashSet<>()
        );
    }

    private static Probe supportedProbe() {
        return new Probe(
            VK12.VK_API_VERSION_1_2,
            new QueueCapabilities(0, 0, 0, 0),
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            new Limits(
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_STORAGE_BUFFER_RANGE,
                16L,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DRAW_INDIRECT_COUNT,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_COMPUTE_WORK_GROUP_COUNT_X,
                1L,
                1L,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_COMPUTE_WORK_GROUP_INVOCATIONS,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_COMPUTE_WORK_GROUP_SIZE_X,
                1,
                1,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DESCRIPTOR_STORAGE_BUFFERS,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DESCRIPTOR_STORAGE_BUFFERS,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DESCRIPTOR_SAMPLED_IMAGES,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DESCRIPTOR_SAMPLED_IMAGES,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DESCRIPTOR_SAMPLED_IMAGES,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DESCRIPTOR_SAMPLED_IMAGES,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DESCRIPTOR_SAMPLED_IMAGES,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_BOUND_DESCRIPTOR_SETS,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_PUSH_CONSTANT_BYTES,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_MEMORY_ALLOCATION_COUNT,
                64L
            ),
            ""
        );
    }

    private static GenerationStamp generations() {
        return NativeTerrainTestFixtures.generations();
    }
}
