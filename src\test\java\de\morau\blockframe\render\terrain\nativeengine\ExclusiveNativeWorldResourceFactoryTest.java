package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .ExclusiveNativeWorldResourceFactory.CreationOutcome;
import de.morau.blockframe.render.terrain.nativeengine
    .ExclusiveNativeWorldResourceFactory.FactoryPhase;
import de.morau.blockframe.render.terrain.nativeengine
    .ExclusiveNativeWorldResourceFactory.OwnerGeneration;
import de.morau.blockframe.render.terrain.nativeengine
    .ExclusiveNativeWorldResourceFactory.OwnerHandle;
import de.morau.blockframe.render.terrain.nativeengine
    .ExclusiveNativeWorldResourceFactory.PrePublicationFailure;
import de.morau.blockframe.render.terrain.nativeengine
    .ExclusiveNativeWorldResourceFactory.RetirementOutcome;
import de.morau.blockframe.render.terrain.nativeengine
    .ExclusiveNativeWorldResourceFactory.RetirementReason;
import de.morau.blockframe.render.terrain.nativeengine
    .ExclusiveNativeWorldResourceFactory.WorldResourceOwner;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.ActivationAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.BudgetAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Configuration;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.ControlledFixtureAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.CreationCleanupAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.ExclusiveWorldFactoryAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.FrameOutputAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.FormatAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Phase;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Preflight;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.RendererApi;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.SelectedBackend;
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
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK12;

class ExclusiveNativeWorldResourceFactoryTest {
    @Test
    void publishesExactlyOneOwnerAndClosesIt() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        int[] constructions = {0};
        FakeOwner owner = new FakeOwner(permit.generation());

        var result = factory.create(permit, generation -> {
            constructions[0]++;
            return owner;
        });

        assertEquals(
            CreationOutcome.NATIVE_OWNER_PUBLISHED,
            result.outcome()
        );
        assertEquals(1, constructions[0]);
        assertEquals(1, owner.prepareCalls);
        assertEquals(1, owner.publishCalls);
        assertEquals(FactoryPhase.ACTIVE, factory.snapshot().phase());
        assertEquals(Phase.WORLD_RESOURCES_ACTIVE, selector.phase());
        assertThrows(
            IllegalStateException.class,
            () -> factory.create(permit, generation -> {
                constructions[0]++;
                return new FakeOwner(generation);
            })
        );
        assertThrows(
            IllegalStateException.class,
            factory::beginCreation
        );
        assertEquals(1, constructions[0]);

        factory.close();

        assertEquals(1, owner.retireCalls);
        assertEquals(RetirementReason.CLOSE, owner.lastReason);
        assertEquals(FactoryPhase.CLOSED, factory.snapshot().phase());
        assertEquals(Phase.SELECTED, selector.phase());
        factory.close();
    }

    @Test
    void cleanConstructorFailureAuthorizesOnlyMojangRebuild() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        int[] constructions = {0};

        var result = factory.create(permit, generation -> {
            constructions[0]++;
            throw new PrePublicationFailure(
                "fixture-construction",
                clean(generation)
            );
        });

        assertEquals(
            CreationOutcome.MOJANG_FALLBACK_REQUIRED,
            result.outcome()
        );
        assertEquals(1, constructions[0]);
        assertTrue(result.ownerHandleOptional().isEmpty());
        assertEquals(
            FactoryPhase.MOJANG_FALLBACK_REQUIRED,
            factory.snapshot().phase()
        );
        assertEquals(Phase.SELECTED, selector.phase());
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selector.selection().backend()
        );
        var mojangPermit = selector.beginWorldResourceCreation();
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            mojangPermit.backend()
        );
        selector.abortReferenceWorldResourceCreation(mojangPermit);
        assertThrows(
            IllegalStateException.class,
            () -> factory.create(
                permit,
                generation -> new FakeOwner(generation)
            )
        );
        factory.close();
    }

    @Test
    void prepareFailureRetiresOwnerBeforeMojangFallback() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        owner.failPrepare = true;

        var result = factory.create(permit, generation -> owner);

        assertEquals(
            CreationOutcome.MOJANG_FALLBACK_REQUIRED,
            result.outcome()
        );
        assertEquals(1, owner.prepareCalls);
        assertEquals(0, owner.publishCalls);
        assertEquals(1, owner.retireCalls);
        assertEquals(
            RetirementReason.ABORT_BEFORE_PUBLISH,
            owner.lastReason
        );
        assertFalse(factory.snapshot().ownerPresent());
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selector.selection().backend()
        );
    }

    @Test
    void incompleteConstructionCleanupRetainsOwnerUntilFallback() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());

        var result = factory.create(permit, generation -> {
            throw new PrePublicationFailure(
                "partial-owner-remains",
                dirty(generation),
                owner
            );
        });

        assertEquals(
            CreationOutcome.PRE_PUBLISH_CLEANUP_RETRY_REQUIRED,
            result.outcome()
        );
        assertTrue(factory.snapshot().ownerPresent());
        assertEquals(
            FactoryPhase.PRE_PUBLISH_CLEANUP_PENDING,
            factory.snapshot().phase()
        );
        assertEquals(
            permit.generation(),
            result.ownerHandleOptional()
                .orElseThrow()
                .generation()
        );
        assertEquals(
            Phase.QUARANTINED_CLEANUP_REQUIRED,
            selector.phase()
        );

        var retry = factory.retryPrePublicationCleanup(
            result.ownerHandleOptional().orElseThrow()
        );

        assertEquals(
            CreationOutcome.MOJANG_FALLBACK_REQUIRED,
            retry.outcome()
        );
        assertEquals(1, owner.retireCalls);
        assertFalse(factory.snapshot().ownerPresent());
        assertEquals(Phase.SELECTED, selector.phase());
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selector.selection().backend()
        );
        factory.close();
    }

    @Test
    void mismatchedConstructorCleanupOwnerRetainsActualGenerationForRetry() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        OwnerGeneration wrongGeneration = new OwnerGeneration(
            permit.generation().serial() + 1L,
            permit.generation().plannedGenerations()
        );
        FakeOwner cleanupOwner =
            new FakeOwner(wrongGeneration);

        var result = factory.create(permit, generation -> {
            throw new PrePublicationFailure(
                "mismatched-cleanup-owner",
                dirty(generation),
                cleanupOwner
            );
        });

        assertEquals(
            CreationOutcome.PRE_PUBLISH_CLEANUP_RETRY_REQUIRED,
            result.outcome()
        );
        OwnerHandle cleanupHandle =
            result.ownerHandleOptional().orElseThrow();
        assertEquals(
            wrongGeneration,
            cleanupHandle.generation()
        );
        assertNotEquals(
            permit.generation(),
            cleanupHandle.generation()
        );
        assertTrue(
            result.reason().contains(
                "cleanup-owner-generation-mismatch"
            )
        );
        assertEquals(0, cleanupOwner.prepareCalls);
        assertEquals(0, cleanupOwner.publishCalls);
        assertEquals(0, cleanupOwner.retireCalls);
        assertEquals(
            FactoryPhase.PRE_PUBLISH_CLEANUP_PENDING,
            factory.snapshot().phase()
        );
        assertEquals(
            Phase.QUARANTINED_CLEANUP_REQUIRED,
            selector.phase()
        );

        var retried =
            factory.retryPrePublicationCleanup(cleanupHandle);

        assertEquals(
            CreationOutcome.MOJANG_FALLBACK_REQUIRED,
            retried.outcome()
        );
        assertEquals(1, cleanupOwner.retireCalls);
        assertEquals(
            RetirementReason.ABORT_BEFORE_PUBLISH,
            cleanupOwner.lastReason
        );
        assertFalse(factory.snapshot().ownerPresent());
        assertEquals(Phase.SELECTED, selector.phase());
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selector.selection().backend()
        );
    }

    @Test
    void prepareCleanupRetryRetainsOriginalOwnerAndPermit() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        owner.failPrepare = true;
        owner.retirements.add(dirty(permit.generation()));
        owner.retirements.add(clean(permit.generation()));

        var initial = factory.create(permit, generation -> owner);

        assertEquals(
            CreationOutcome.PRE_PUBLISH_CLEANUP_RETRY_REQUIRED,
            initial.outcome()
        );
        assertEquals(
            Phase.QUARANTINED_CLEANUP_REQUIRED,
            selector.phase()
        );
        var retried = factory.retryPrePublicationCleanup(
            initial.ownerHandleOptional().orElseThrow()
        );
        assertEquals(
            CreationOutcome.MOJANG_FALLBACK_REQUIRED,
            retried.outcome()
        );
        assertEquals(2, owner.retireCalls);
        assertEquals(Phase.SELECTED, selector.phase());
    }

    @Test
    void publishFailureCrossesBoundaryAndNeverFallsBack() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        owner.failPublish = true;

        var result = factory.create(permit, generation -> owner);

        assertEquals(
            CreationOutcome.FAIL_CLOSED_QUARANTINED,
            result.outcome()
        );
        OwnerHandle handle =
            result.ownerHandleOptional().orElseThrow();
        assertEquals(1, owner.publishCalls);
        assertEquals(0, owner.retireCalls);
        assertTrue(
            factory.snapshot().publicationBoundaryCrossed()
        );
        assertEquals(
            Phase.WORLD_RESOURCES_QUARANTINED,
            selector.phase()
        );
        assertEquals(
            SelectedBackend.BLOCKFRAME_NATIVE_EXPERIMENTAL,
            selector.selection().backend()
        );

        var retirement = factory.retire(
            handle,
            RetirementReason.CLOSE
        );

        assertEquals(
            RetirementOutcome.RETIRED_BUT_QUARANTINED,
            retirement.outcome()
        );
        assertFalse(factory.snapshot().ownerPresent());
        assertEquals(FactoryPhase.QUARANTINED, factory.snapshot().phase());
        assertEquals(Phase.QUARANTINED, selector.phase());
        assertEquals(
            SelectedBackend.BLOCKFRAME_NATIVE_EXPERIMENTAL,
            selector.selection().backend()
        );
    }

    @Test
    void runtimeFailureRemainsQuarantinedAfterCleanRetirement() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        var created = factory.create(permit, generation -> owner);
        OwnerHandle handle =
            created.ownerHandleOptional().orElseThrow();

        factory.signalFailureAfterPublish(
            handle,
            "device-generation-failed"
        );
        assertEquals(
            Phase.WORLD_RESOURCES_QUARANTINED,
            selector.phase()
        );
        var retirement = factory.retire(
            handle,
            RetirementReason.WORLD_SWITCH
        );

        assertEquals(
            RetirementOutcome.RETIRED_BUT_QUARANTINED,
            retirement.outcome()
        );
        assertEquals(Phase.QUARANTINED, selector.phase());
        assertEquals(
            SelectedBackend.BLOCKFRAME_NATIVE_EXPERIMENTAL,
            selector.selection().backend()
        );
        assertEquals(FactoryPhase.QUARANTINED, factory.snapshot().phase());
        assertThrows(
            IllegalStateException.class,
            factory::beginCreation
        );
    }

    @Test
    void reloadAndWorldSwitchUseFreshOwnerGenerations() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var firstPermit = factory.beginCreation();
        FakeOwner first = new FakeOwner(firstPermit.generation());
        var firstResult = factory.create(
            firstPermit,
            generation -> first
        );
        OwnerHandle firstHandle =
            firstResult.ownerHandleOptional().orElseThrow();

        var reloadRetirement = factory.retire(
            firstHandle,
            RetirementReason.RELOAD
        );

        assertEquals(
            RetirementOutcome.RETIRED,
            reloadRetirement.outcome()
        );
        assertEquals(
            FactoryPhase.AWAITING_WORLD_REVALIDATION,
            factory.snapshot().phase()
        );
        assertEquals(
            Phase.WORLD_REVALIDATION_REQUIRED,
            selector.phase()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> factory.revalidateAndBeginCreation(
                readyPreflight(
                    firstPermit.generation().plannedGenerations()
                )
            )
        );
        GenerationStamp reloadGenerations = afterReload(
            firstPermit.generation().plannedGenerations()
        );
        var secondPermit = factory
            .revalidateAndBeginCreation(
                readyPreflight(reloadGenerations)
            )
            .orElseThrow();
        int[] staleConstructions = {0};
        assertThrows(
            IllegalArgumentException.class,
            () -> factory.create(
                firstPermit,
                generation -> {
                    staleConstructions[0]++;
                    return new FakeOwner(generation);
                }
            )
        );
        assertEquals(0, staleConstructions[0]);
        assertNotEquals(
            firstPermit.generation().serial(),
            secondPermit.generation().serial()
        );
        assertEquals(
            reloadGenerations,
            secondPermit.generation().plannedGenerations()
        );
        FakeOwner second = new FakeOwner(secondPermit.generation());
        var secondResult = factory.create(
            secondPermit,
            generation -> second
        );
        OwnerHandle secondHandle =
            secondResult.ownerHandleOptional().orElseThrow();
        assertThrows(
            IllegalArgumentException.class,
            () -> factory.retire(
                firstHandle,
                RetirementReason.WORLD_SWITCH
            )
        );

        var switchRetirement = factory.retire(
            secondHandle,
            RetirementReason.WORLD_SWITCH
        );

        assertEquals(
            RetirementOutcome.RETIRED,
            switchRetirement.outcome()
        );
        assertEquals(
            FactoryPhase.AWAITING_WORLD_REVALIDATION,
            factory.snapshot().phase()
        );
        assertEquals(RetirementReason.RELOAD, first.lastReason);
        assertEquals(
            RetirementReason.WORLD_SWITCH,
            second.lastReason
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> factory.revalidateAndBeginCreation(
                readyPreflight(afterReload(reloadGenerations))
            )
        );
        GenerationStamp switchedGenerations = afterWorldSwitch(
            reloadGenerations
        );
        var thirdPermit = factory
            .revalidateAndBeginCreation(
                readyPreflight(switchedGenerations)
            )
            .orElseThrow();
        var third = factory.create(thirdPermit, FakeOwner::new);
        factory.retire(
            third.ownerHandleOptional().orElseThrow(),
            RetirementReason.CLOSE
        );
        factory.close();
    }

    @Test
    void foreignPermitIsRejectedBeforeConstructorRuns() {
        NativeTerrainBackendSelector firstSelector =
            nativeSelector();
        NativeTerrainBackendSelector secondSelector =
            nativeSelector();
        var firstFactory =
            new ExclusiveNativeWorldResourceFactory(firstSelector);
        var secondFactory =
            new ExclusiveNativeWorldResourceFactory(secondSelector);
        var firstPermit = firstFactory.beginCreation();
        var secondPermit = secondFactory.beginCreation();
        int[] foreignConstructions = {0};

        assertThrows(
            IllegalArgumentException.class,
            () -> secondFactory.create(
                firstPermit,
                generation -> {
                    foreignConstructions[0]++;
                    return new FakeOwner(generation);
                }
            )
        );
        assertEquals(0, foreignConstructions[0]);

        var firstCreated = firstFactory.create(
            firstPermit,
            FakeOwner::new
        );
        var secondCreated = secondFactory.create(
            secondPermit,
            FakeOwner::new
        );
        firstFactory.retire(
            firstCreated.ownerHandleOptional().orElseThrow(),
            RetirementReason.CLOSE
        );
        secondFactory.retire(
            secondCreated.ownerHandleOptional().orElseThrow(),
            RetirementReason.CLOSE
        );
    }

    @Test
    void mismatchedOwnerGenerationIsCleanedBeforeFallback() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        OwnerGeneration wrong = new OwnerGeneration(
            permit.generation().serial() + 1L,
            permit.generation().plannedGenerations()
        );
        FakeOwner owner = new FakeOwner(wrong);
        owner.retirements.add(dirty(wrong));
        owner.retirements.add(clean(wrong));

        var result = factory.create(permit, generation -> owner);

        assertEquals(
            CreationOutcome.PRE_PUBLISH_CLEANUP_RETRY_REQUIRED,
            result.outcome()
        );
        OwnerHandle cleanupHandle =
            result.ownerHandleOptional().orElseThrow();
        assertEquals(wrong, cleanupHandle.generation());
        assertNotEquals(
            permit.generation(),
            cleanupHandle.generation()
        );
        assertEquals(0, owner.prepareCalls);
        assertEquals(0, owner.publishCalls);
        assertEquals(1, owner.retireCalls);
        assertEquals(
            RetirementReason.ABORT_BEFORE_PUBLISH,
            owner.lastReason
        );
        assertEquals(
            Phase.QUARANTINED_CLEANUP_REQUIRED,
            selector.phase()
        );

        var retried =
            factory.retryPrePublicationCleanup(cleanupHandle);

        assertEquals(
            CreationOutcome.MOJANG_FALLBACK_REQUIRED,
            retried.outcome()
        );
        assertEquals(2, owner.retireCalls);
        assertEquals(
            SelectedBackend.MOJANG_REFERENCE,
            selector.selection().backend()
        );
    }

    @Test
    void normalReloadRetirementCleanupCanCompleteWithoutQuarantine() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        owner.retirements.add(dirty(permit.generation()));
        owner.retirements.add(clean(permit.generation()));
        var created = factory.create(permit, generation -> owner);
        OwnerHandle handle =
            created.ownerHandleOptional().orElseThrow();

        var first = factory.retire(
            handle,
            RetirementReason.RELOAD
        );
        assertEquals(
            RetirementOutcome.CLEANUP_RETRY_REQUIRED,
            first.outcome()
        );
        assertTrue(factory.snapshot().ownerPresent());
        assertEquals(
            FactoryPhase.RETIREMENT_CLEANUP_PENDING,
            factory.snapshot().phase()
        );
        assertEquals(
            Phase.WORLD_RESOURCES_ACTIVE,
            selector.phase()
        );

        var second = factory.retire(
            handle,
            RetirementReason.RELOAD
        );
        assertEquals(
            RetirementOutcome.RETIRED,
            second.outcome()
        );
        assertFalse(factory.snapshot().ownerPresent());
        assertEquals(
            Phase.WORLD_REVALIDATION_REQUIRED,
            selector.phase()
        );
        assertEquals(
            FactoryPhase.AWAITING_WORLD_REVALIDATION,
            factory.snapshot().phase()
        );
    }

    @Test
    void normalCloseRetirementCleanupCanBeRetried() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        owner.retirements.add(dirty(permit.generation()));
        owner.retirements.add(clean(permit.generation()));
        factory.create(permit, generation -> owner);

        assertThrows(IllegalStateException.class, factory::close);
        assertEquals(
            FactoryPhase.RETIREMENT_CLEANUP_PENDING,
            factory.snapshot().phase()
        );
        assertEquals(
            Phase.WORLD_RESOURCES_ACTIVE,
            selector.phase()
        );

        factory.close();

        assertEquals(FactoryPhase.CLOSED, factory.snapshot().phase());
        assertEquals(Phase.SELECTED, selector.phase());
        assertEquals(2, owner.retireCalls);
    }

    @Test
    void postPublishFailureCleanupRetryRemainsQuarantined() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        owner.retirements.add(dirty(permit.generation()));
        owner.retirements.add(clean(permit.generation()));
        var created = factory.create(permit, generation -> owner);
        OwnerHandle handle =
            created.ownerHandleOptional().orElseThrow();

        factory.signalFailureAfterPublish(
            handle,
            "post-publish-fixture"
        );
        var first = factory.retire(
            handle,
            RetirementReason.RELOAD
        );
        assertEquals(
            RetirementOutcome.CLEANUP_RETRY_REQUIRED,
            first.outcome()
        );
        assertEquals(
            FactoryPhase.QUARANTINED,
            factory.snapshot().phase()
        );
        assertEquals(
            Phase.WORLD_RESOURCES_QUARANTINED,
            selector.phase()
        );

        var second = factory.retire(
            handle,
            RetirementReason.RELOAD
        );
        assertEquals(
            RetirementOutcome.RETIRED_BUT_QUARANTINED,
            second.outcome()
        );
        assertFalse(factory.snapshot().ownerPresent());
        assertEquals(
            FactoryPhase.QUARANTINED,
            factory.snapshot().phase()
        );
        assertEquals(Phase.QUARANTINED, selector.phase());
    }

    @Test
    void constructorThrowableFailsClosedWithoutOwnerPublication() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();

        var result = factory.create(permit, generation -> {
            throw new AssertionError("constructor-error");
        });

        assertEquals(
            CreationOutcome.FAIL_CLOSED_QUARANTINED,
            result.outcome()
        );
        assertFalse(factory.snapshot().ownerPresent());
        assertEquals(
            Phase.QUARANTINED_CLEANUP_REQUIRED,
            selector.phase()
        );
    }

    @Test
    void prepareThrowableCleansBeforeMojangFallback() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        owner.prepareError = new AssertionError("prepare-error");

        var result = factory.create(permit, generation -> owner);

        assertEquals(
            CreationOutcome.MOJANG_FALLBACK_REQUIRED,
            result.outcome()
        );
        assertEquals(1, owner.retireCalls);
        assertEquals(Phase.SELECTED, selector.phase());
    }

    @Test
    void publishThrowableIsCentrallyQuarantined() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        owner.publishError = new AssertionError("publish-error");

        var result = factory.create(permit, generation -> owner);

        assertEquals(
            CreationOutcome.FAIL_CLOSED_QUARANTINED,
            result.outcome()
        );
        assertEquals(
            Phase.WORLD_RESOURCES_QUARANTINED,
            selector.phase()
        );
        factory.retire(
            result.ownerHandleOptional().orElseThrow(),
            RetirementReason.CLOSE
        );
        assertEquals(Phase.QUARANTINED, selector.phase());
    }

    @Test
    void retirementThrowableRetainsOwnerForCleanupRetry() {
        NativeTerrainBackendSelector selector = nativeSelector();
        var factory =
            new ExclusiveNativeWorldResourceFactory(selector);
        var permit = factory.beginCreation();
        FakeOwner owner = new FakeOwner(permit.generation());
        owner.retirementErrors.add(
            new AssertionError("retirement-error")
        );
        var created = factory.create(permit, generation -> owner);
        OwnerHandle handle =
            created.ownerHandleOptional().orElseThrow();

        var first = factory.retire(
            handle,
            RetirementReason.RELOAD
        );

        assertEquals(
            RetirementOutcome.CLEANUP_RETRY_REQUIRED,
            first.outcome()
        );
        assertTrue(factory.snapshot().ownerPresent());
        assertEquals(
            FactoryPhase.RETIREMENT_CLEANUP_PENDING,
            factory.snapshot().phase()
        );
        assertEquals(
            Phase.WORLD_RESOURCES_ACTIVE,
            selector.phase()
        );
        var second = factory.retire(
            handle,
            RetirementReason.RELOAD
        );
        assertEquals(
            RetirementOutcome.RETIRED,
            second.outcome()
        );
        assertEquals(
            FactoryPhase.AWAITING_WORLD_REVALIDATION,
            factory.snapshot().phase()
        );
        assertEquals(
            Phase.WORLD_REVALIDATION_REQUIRED,
            selector.phase()
        );
    }

    private static final class FakeOwner
        implements WorldResourceOwner {
        private final OwnerGeneration generation;
        private final ArrayDeque<CreationCleanupAttestation>
            retirements = new ArrayDeque<>();
        private boolean failPrepare;
        private boolean failPublish;
        private Error prepareError;
        private Error publishError;
        private final ArrayDeque<Error> retirementErrors =
            new ArrayDeque<>();
        private int prepareCalls;
        private int publishCalls;
        private int retireCalls;
        private RetirementReason lastReason;

        private FakeOwner(OwnerGeneration generation) {
            this.generation = generation;
        }

        @Override
        public OwnerGeneration generation() {
            return this.generation;
        }

        @Override
        public void prepare() {
            this.prepareCalls++;
            if (this.prepareError != null) {
                throw this.prepareError;
            }
            if (this.failPrepare) {
                throw new IllegalStateException("prepare-failure");
            }
        }

        @Override
        public void publish() {
            this.publishCalls++;
            if (this.publishError != null) {
                throw this.publishError;
            }
            if (this.failPublish) {
                throw new IllegalStateException("publish-failure");
            }
        }

        @Override
        public CreationCleanupAttestation retire(
            RetirementReason reason
        ) {
            this.retireCalls++;
            this.lastReason = reason;
            Error retirementError = this.retirementErrors.poll();
            if (retirementError != null) {
                throw retirementError;
            }
            CreationCleanupAttestation queued =
                this.retirements.poll();
            return queued == null ? clean(this.generation) : queued;
        }
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
        return readyPreflight(
            NativeTerrainTestFixtures.generations()
        );
    }

    private static Preflight readyPreflight(
        GenerationStamp generations
    ) {
        return new Preflight(
            generations,
            RendererApi.VULKAN,
            readyCapability(generations.device()),
            new ActivationAttestation(
                generations.resources(),
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

    private static GenerationStamp afterReload(
        GenerationStamp previous
    ) {
        return new GenerationStamp(
            previous.device(),
            previous.renderer() + 1L,
            previous.world(),
            previous.resources() + 1L,
            previous.producer() + 1L,
            previous.sectionMesh() + 1L
        );
    }

    private static GenerationStamp afterWorldSwitch(
        GenerationStamp previous
    ) {
        return new GenerationStamp(
            previous.device(),
            previous.renderer() + 1L,
            previous.world() + 1L,
            previous.resources(),
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

    private static CreationCleanupAttestation clean(
        OwnerGeneration generation
    ) {
        return new CreationCleanupAttestation(
            generation.plannedGenerations().device(),
            0L,
            0L,
            0,
            true
        );
    }

    private static CreationCleanupAttestation dirty(
        OwnerGeneration generation
    ) {
        return new CreationCleanupAttestation(
            generation.plannedGenerations().device(),
            1L,
            2L,
            3,
            false
        );
    }
}
