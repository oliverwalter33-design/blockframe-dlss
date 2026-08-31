package de.morau.blockframe.core.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStateStoreTest {
    private static final String FINGERPRINT = "b".repeat(64);
    private static final String DLSS_ON_FINGERPRINT = "c".repeat(64);
    private static final String SHARPENED_FINGERPRINT = "d".repeat(64);
    private static final String DLSS_OFF_FINGERPRINT = "e".repeat(64);
    private static final long REQUESTED = 0x7L;
    private static final long NORMAL = 0x3L;
    private static final long SAFE = 0x0L;
    private static final long DLSS_MASK = FeatureId.DLSS_MODE.mask();

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshStartPublishesThenNormalStableRunClosesCleanly()
        throws Exception {
        Path root = this.temporaryDirectory.resolve("fresh");
        UUID runId;
        UUID lkgId;
        try (RunStateStore store = RunStateStore.open(root, identity())) {
            assertEquals(
                RunStatePersistenceStatus.READ_WRITE,
                store.persistenceStatus()
            );
            assertEquals(RunPhase.STARTING, store.snapshot().phase());
            assertEquals(1L, store.snapshot().runGeneration());
            assertEquals(1L, store.snapshot().commitGeneration());
            assertFalse(store.snapshot().cleanShutdown());
            assertEquals(
                RunStatePublicationMode.ATOMIC,
                store.publicationMode()
            );
            runId = store.snapshot().runId();

            markStable(store, NORMAL);
            assertEquals(RunPhase.STABLE, store.snapshot().phase());
            assertNotNull(store.snapshot().lastKnownGood());
            lkgId = store.snapshot().lastKnownGood().runId();
            assertEquals(runId, lkgId);
            assertTrue(store.markCleanShutdown(NORMAL));
            assertEquals(
                RunPhase.CLEAN_SHUTDOWN,
                store.snapshot().phase()
            );
            assertTrue(store.snapshot().cleanShutdown());
        }

        try (RunStateStore next = RunStateStore.open(root, identity())) {
            assertEquals(2L, next.snapshot().runGeneration());
            assertEquals(
                RunPhase.CLEAN_SHUTDOWN,
                next.snapshot().previousRun().phase()
            );
            assertTrue(next.snapshot().previousRun().cleanShutdown());
            assertEquals(lkgId, next.snapshot().lastKnownGood().runId());
            assertFalse(next.safeStartOfferAvailable());
        }
    }

    @Test
    void closeAloneNeverWritesCleanAndIncompleteRunCreatesOneOffer()
        throws Exception {
        Path root = this.temporaryDirectory.resolve("abrupt-before");
        UUID firstRun;
        RunStateStore first = RunStateStore.open(root, identity());
        firstRun = first.snapshot().runId();
        first.close();
        assertEquals(
            RunStatePersistenceStatus.CLOSED,
            first.persistenceStatus()
        );
        assertFalse(first.snapshot().cleanShutdown());

        try (RunStateStore next = RunStateStore.open(root, identity())) {
            assertEquals(RunPhase.UNCLEAN, next.snapshot().previousRun().phase());
            assertFalse(next.snapshot().previousRun().cleanShutdown());
            assertEquals(
                firstRun,
                next.snapshot().safeStart().candidateEvent()
            );
            assertTrue(next.safeStartOfferAvailable());
            assertTrue(next.offerSafeStart());
            assertFalse(next.offerSafeStart());
            assertFalse(next.safeStartOfferAvailable());
        }
    }

    @Test
    void stableButUncleanRunKeepsLkgWithoutSafeStartAccusation()
        throws Exception {
        Path root = this.temporaryDirectory.resolve("abrupt-after");
        UUID lkgId;
        try (RunStateStore first = RunStateStore.open(root, identity())) {
            markStable(first, NORMAL);
            lkgId = first.snapshot().lastKnownGood().runId();
        }

        try (RunStateStore next = RunStateStore.open(root, identity())) {
            assertEquals(RunPhase.UNCLEAN, next.snapshot().previousRun().phase());
            assertNull(next.snapshot().safeStart().candidateEvent());
            assertFalse(next.safeStartOfferAvailable());
            assertEquals(lkgId, next.snapshot().lastKnownGood().runId());
        }
    }

    @Test
    void deviceRecreationRequiresANewWindowAndPreservesPriorLkg()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("device-recreation");
        try (RunStateStore store = RunStateStore.open(root, identity())) {
            markStable(store, NORMAL);
            RunStateRecord.LastKnownGood firstLkg =
                store.snapshot().lastKnownGood();
            assertNotNull(firstLkg);

            assertTrue(
                store.markDeviceReinitializing(
                    RunBackend.VULKAN,
                    0L
                )
            );
            assertEquals(
                RunPhase.INITIALIZING,
                store.snapshot().phase()
            );
            assertEquals(
                RunCheckpoint.BACKEND_INITIALIZED,
                store.snapshot().checkpoint()
            );
            assertEquals(firstLkg, store.snapshot().lastKnownGood());
            assertTrue(store.markActiveFeaturesPublished(0L));
            assertTrue(store.markFirstWorldFrame(NORMAL));
            assertTrue(store.markStable(NORMAL));
            assertEquals(RunPhase.STABLE, store.snapshot().phase());
            assertEquals(
                store.snapshot().runId(),
                store.snapshot().lastKnownGood().runId()
            );
            assertTrue(
                store.snapshot().lastKnownGood().commitGeneration()
                    > firstLkg.commitGeneration()
            );
        }
    }

    @Test
    void stableLifecycleBoundaryRequiresExactNewFrameWindow()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("stability-revalidation");
        WorldFrameStabilityTracker tracker =
            new WorldFrameStabilityTracker(120);
        Object world = new Object();
        try (RunStateStore store = RunStateStore.open(root, identity())) {
            markStable(store, NORMAL);
            RunStateRecord.LastKnownGood priorLkg =
                store.snapshot().lastKnownGood();
            for (int frame = 1; frame <= 120; frame++) {
                tracker.observeSuccessfulFrame(world);
            }
            assertTrue(tracker.tracksWorld(world));

            assertTrue(store.markStabilityRevalidating(NORMAL));
            tracker.clearWorld();
            assertEquals(
                RunPhase.INITIALIZING,
                store.snapshot().phase()
            );
            assertEquals(
                RunCheckpoint.ACTIVE_FEATURES_PUBLISHED,
                store.snapshot().checkpoint()
            );
            assertEquals(priorLkg, store.snapshot().lastKnownGood());
            long revalidationCommit =
                store.snapshot().commitGeneration();
            assertFalse(store.markStabilityRevalidating(NORMAL));
            assertEquals(
                revalidationCommit,
                store.snapshot().commitGeneration()
            );

            assertEquals(
                WorldFrameStabilityTracker.Transition.FIRST_WORLD_FRAME,
                tracker.observeSuccessfulFrame(world)
            );
            assertTrue(store.markFirstWorldFrame(NORMAL));
            assertEquals(1, tracker.consecutiveFrames());
            for (int frame = 2; frame <= 119; frame++) {
                assertEquals(
                    WorldFrameStabilityTracker.Transition.NONE,
                    tracker.observeSuccessfulFrame(world)
                );
            }
            assertEquals(119, tracker.consecutiveFrames());
            assertEquals(
                RunPhase.INITIALIZING,
                store.snapshot().phase()
            );
            assertEquals(priorLkg, store.snapshot().lastKnownGood());

            assertEquals(
                WorldFrameStabilityTracker.Transition
                    .STABILITY_WINDOW_COMPLETE,
                tracker.observeSuccessfulFrame(world)
            );
            assertTrue(store.markStable(NORMAL));
            assertEquals(120, tracker.consecutiveFrames());
            assertTrue(
                store.snapshot().lastKnownGood().commitGeneration()
                    > priorLkg.commitGeneration()
            );
        }
    }

    @Test
    void unfinishedRevalidationReopensUncleanWithHistoricalLkg()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("revalidation-unclean");
        UUID runId;
        RunStateRecord.LastKnownGood lkg;
        RunStateStore store = RunStateStore.open(root, identity());
        markStable(store, NORMAL);
        runId = store.snapshot().runId();
        lkg = store.snapshot().lastKnownGood();
        assertTrue(store.markStabilityRevalidating(NORMAL));
        assertTrue(store.markFirstWorldFrame(NORMAL));
        store.close();

        try (RunStateStore next = RunStateStore.open(root, identity())) {
            assertEquals(
                RunPhase.UNCLEAN,
                next.snapshot().previousRun().phase()
            );
            assertEquals(runId, next.snapshot().previousRun().runId());
            assertFalse(next.snapshot().previousRun().cleanShutdown());
            assertEquals(lkg, next.snapshot().lastKnownGood());
            assertEquals(
                runId,
                next.snapshot().safeStart().candidateEvent()
            );
        }
    }

    @Test
    void titleUnloadRevalidationCanStillCloseCleanly()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("revalidation-title-clean");
        RunStateRecord.LastKnownGood lkg;
        try (RunStateStore store = RunStateStore.open(root, identity())) {
            markStable(store, NORMAL);
            lkg = store.snapshot().lastKnownGood();
            assertTrue(store.markStabilityRevalidating(NORMAL));
            assertTrue(store.markCleanShutdown(NORMAL));
        }

        try (RunStateStore next = RunStateStore.open(root, identity())) {
            assertEquals(
                RunPhase.CLEAN_SHUTDOWN,
                next.snapshot().previousRun().phase()
            );
            assertTrue(next.snapshot().previousRun().cleanShutdown());
            assertEquals(lkg, next.snapshot().lastKnownGood());
            assertNull(next.snapshot().safeStart().candidateEvent());
        }
    }

    @Test
    void failedFrameOnSameWorldRequiresExactNewWindow()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("failed-frame-revalidation");
        WorldFrameStabilityTracker tracker =
            new WorldFrameStabilityTracker(120);
        Object world = new Object();
        try (RunStateStore store = RunStateStore.open(root, identity())) {
            markStable(store, NORMAL);
            for (int frame = 1; frame <= 120; frame++) {
                tracker.observeSuccessfulFrame(world);
            }
            RunStateRecord.LastKnownGood priorLkg =
                store.snapshot().lastKnownGood();

            assertTrue(store.markStabilityRevalidating(NORMAL));
            tracker.resetWindow();
            assertTrue(tracker.tracksWorld(world));
            assertEquals(
                WorldFrameStabilityTracker.Transition.FIRST_WORLD_FRAME,
                tracker.observeSuccessfulFrame(world)
            );
            assertTrue(store.markFirstWorldFrame(NORMAL));
            for (int frame = 2; frame <= 119; frame++) {
                assertEquals(
                    WorldFrameStabilityTracker.Transition.NONE,
                    tracker.observeSuccessfulFrame(world)
                );
            }
            assertEquals(119, tracker.consecutiveFrames());
            assertEquals(priorLkg, store.snapshot().lastKnownGood());
            assertEquals(
                WorldFrameStabilityTracker.Transition
                    .STABILITY_WINDOW_COMPLETE,
                tracker.observeSuccessfulFrame(world)
            );
            assertTrue(store.markStable(NORMAL));
            assertEquals(120, tracker.consecutiveFrames());
        }
    }

    @Test
    void liveOffOnSharpenAndOnOffRebaseUseTheCurrentIdentity()
        throws Exception {
        Path root = this.temporaryDirectory.resolve("live-rebase");
        RunStateIdentity initialOff = liveIdentity(
            FINGERPRINT,
            REQUESTED & ~DLSS_MASK,
            NORMAL & ~DLSS_MASK
        );
        RunStateIdentity dlssOn = liveIdentity(
            DLSS_ON_FINGERPRINT,
            REQUESTED,
            NORMAL
        );
        RunStateIdentity sharpened = liveIdentity(
            SHARPENED_FINGERPRINT,
            REQUESTED,
            NORMAL
        );
        RunStateIdentity finalOff = liveIdentity(
            DLSS_OFF_FINGERPRINT,
            REQUESTED & ~DLSS_MASK,
            NORMAL & ~DLSS_MASK
        );

        UUID runId;
        long runGeneration;
        UUID failureId;
        try (RunStateStore store = RunStateStore.open(root, initialOff)) {
            markStable(store, NORMAL & ~DLSS_MASK);
            RunStateRecord.LastKnownGood initialLkg =
                store.snapshot().lastKnownGood();
            runId = store.snapshot().runId();
            runGeneration = store.snapshot().runGeneration();

            assertTrue(
                store.rebaseIdentity(
                    dlssOn,
                    NORMAL & ~DLSS_MASK
                )
            );
            assertEquals(runId, store.snapshot().runId());
            assertEquals(
                runGeneration,
                store.snapshot().runGeneration()
            );
            assertEquals(
                DLSS_ON_FINGERPRINT,
                store.snapshot().configFingerprint()
            );
            assertEquals(REQUESTED, store.snapshot().requestedFeatureMask());
            assertEquals(
                NORMAL & ~DLSS_MASK,
                store.snapshot().effectiveFeatureMask()
            );
            assertEquals(RunPhase.INITIALIZING, store.snapshot().phase());
            assertEquals(
                RunCheckpoint.ACTIVE_FEATURES_PUBLISHED,
                store.snapshot().checkpoint()
            );
            assertEquals(initialLkg, store.snapshot().lastKnownGood());

            assertTrue(store.markFirstWorldFrame(NORMAL));
            assertTrue(store.markStable(NORMAL));
            RunStateRecord.LastKnownGood dlssLkg =
                store.snapshot().lastKnownGood();
            assertEquals(
                DLSS_ON_FINGERPRINT,
                dlssLkg.configFingerprint()
            );

            assertTrue(store.rebaseIdentity(sharpened, NORMAL));
            assertEquals(
                SHARPENED_FINGERPRINT,
                store.snapshot().configFingerprint()
            );
            assertEquals(dlssLkg, store.snapshot().lastKnownGood());
            assertEquals(
                RunCheckpoint.ACTIVE_FEATURES_PUBLISHED,
                store.snapshot().checkpoint()
            );
            assertTrue(store.markFirstWorldFrame(NORMAL));
            assertTrue(store.markStable(NORMAL));
            RunStateRecord.LastKnownGood sharpenedLkg =
                store.snapshot().lastKnownGood();
            assertEquals(
                SHARPENED_FINGERPRINT,
                sharpenedLkg.configFingerprint()
            );

            assertTrue(
                store.rebaseIdentity(
                    finalOff,
                    NORMAL & ~DLSS_MASK
                )
            );
            assertEquals(
                DLSS_OFF_FINGERPRINT,
                store.snapshot().configFingerprint()
            );
            assertEquals(
                REQUESTED & ~DLSS_MASK,
                store.snapshot().requestedFeatureMask()
            );
            assertEquals(
                NORMAL & ~DLSS_MASK,
                store.snapshot().effectiveFeatureMask()
            );
            assertEquals(sharpenedLkg, store.snapshot().lastKnownGood());
            assertEquals(runId, store.snapshot().runId());
            assertEquals(
                runGeneration,
                store.snapshot().runGeneration()
            );

            failureId = store.snapshot().runId();
            assertTrue(
                store.markFailed(
                    ConfirmedRunError.BLOCKFRAME_ERROR,
                    "live-config-test",
                    NORMAL & ~DLSS_MASK
                )
            );
            assertTrue(
                store.markCleanShutdown(NORMAL & ~DLSS_MASK)
            );
        }

        try (RunStateStore next = RunStateStore.open(root, finalOff)) {
            assertEquals(
                RunPhase.FAILED,
                next.snapshot().previousRun().phase()
            );
            assertTrue(next.snapshot().previousRun().cleanShutdown());
            assertEquals(
                failureId,
                next.snapshot().safeStart().candidateEvent()
            );
            assertEquals(
                failureId,
                next.snapshot().lastConfirmedFailure().runId()
            );
            assertTrue(next.safeStartOfferAvailable());
        }
    }

    @Test
    void failedRebaseSwitchesInMemoryIdentityAndStopsAllLaterWrites()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("failed-live-rebase");
        NthWriteDeniedIo io = new NthWriteDeniedIo(4);
        RunStateIdentity initialOff = liveIdentity(
            FINGERPRINT,
            REQUESTED & ~DLSS_MASK,
            NORMAL & ~DLSS_MASK
        );
        RunStateIdentity dlssOn = liveIdentity(
            DLSS_ON_FINGERPRINT,
            REQUESTED,
            NORMAL
        );
        RunStateStore store = RunStateStore.open(
            root,
            initialOff,
            io,
            () -> new UUID(0L, 204L)
        );
        assertTrue(
            store.markInitializing(
                RunBackend.VULKAN,
                NORMAL & ~DLSS_MASK
            )
        );
        assertTrue(
            store.markActiveFeaturesPublished(
                NORMAL & ~DLSS_MASK
            )
        );

        assertFalse(
            store.rebaseIdentity(
                dlssOn,
                NORMAL & ~DLSS_MASK
            )
        );
        assertEquals(
            RunStatePersistenceStatus.READ_ONLY_IO_FAILURE,
            store.persistenceStatus()
        );
        assertEquals(
            DLSS_ON_FINGERPRINT,
            store.snapshot().configFingerprint()
        );
        assertEquals(REQUESTED, store.snapshot().requestedFeatureMask());
        assertEquals(
            RunCheckpoint.ACTIVE_FEATURES_PUBLISHED,
            store.snapshot().checkpoint()
        );

        assertFalse(
            store.markFailed(
                ConfirmedRunError.DEVICE_LOSS,
                "vk.device-lost",
                NORMAL & ~DLSS_MASK
            )
        );
        assertFalse(
            store.markCleanShutdown(NORMAL & ~DLSS_MASK)
        );
        assertEquals(
            DLSS_ON_FINGERPRINT,
            store.snapshot().configFingerprint()
        );
        assertEquals(4, io.writes);
        store.close();

        try (RunStateStore reopened = RunStateStore.open(root, dlssOn)) {
            assertEquals(
                RunPhase.UNCLEAN,
                reopened.snapshot().previousRun().phase()
            );
            assertNull(reopened.snapshot().safeStart().candidateEvent());
            assertNull(reopened.snapshot().lastConfirmedFailure());
        }
    }

    @Test
    void requestedMaskIsPartOfSafeStartIdentity() throws Exception {
        Path root =
            this.temporaryDirectory.resolve("requested-mask-identity");
        RunStateIdentity original = liveIdentity(
            FINGERPRINT,
            REQUESTED,
            NORMAL
        );
        try (RunStateStore failed = RunStateStore.open(root, original)) {
            assertTrue(
                failed.markFailed(
                    ConfirmedRunError.BLOCKFRAME_ERROR,
                    "requested-mask-test",
                    NORMAL
                )
            );
            assertTrue(failed.markCleanShutdown(NORMAL));
        }

        RunStateIdentity differentRequest = liveIdentity(
            FINGERPRINT,
            REQUESTED & ~DLSS_MASK,
            NORMAL & ~DLSS_MASK
        );
        try (
            RunStateStore next =
                RunStateStore.open(root, differentRequest)
        ) {
            assertNull(next.snapshot().safeStart().candidateEvent());
            assertFalse(next.safeStartOfferAvailable());
        }
    }

    @Test
    void startingRebasePreservesFailureAndSafeStartMarkers()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("starting-live-rebase");
        createFailedEvent(root);
        RunStateIdentity sharpened = liveIdentity(
            SHARPENED_FINGERPRINT,
            REQUESTED,
            NORMAL
        );
        try (RunStateStore store = RunStateStore.open(root, identity())) {
            assertTrue(store.offerSafeStart());
            RunStateRecord.SafeStartState safe =
                store.snapshot().safeStart();
            RunStateRecord.ConfirmedFailure failure =
                store.snapshot().lastConfirmedFailure();
            UUID runId = store.snapshot().runId();
            long runGeneration = store.snapshot().runGeneration();

            assertTrue(store.rebaseIdentity(sharpened, NORMAL));
            assertEquals(runId, store.snapshot().runId());
            assertEquals(
                runGeneration,
                store.snapshot().runGeneration()
            );
            assertEquals(safe, store.snapshot().safeStart());
            assertEquals(
                failure,
                store.snapshot().lastConfirmedFailure()
            );
            assertEquals(RunPhase.STARTING, store.snapshot().phase());
            assertEquals(
                RunCheckpoint.PROCESS_STARTED,
                store.snapshot().checkpoint()
            );
        }
    }

    @Test
    void confirmedFailedRunCanCloseCleanWithoutLosingFailure()
        throws Exception {
        Path root = this.temporaryDirectory.resolve("failed-clean");
        UUID failedId;
        try (RunStateStore failed = RunStateStore.open(root, identity())) {
            failedId = failed.snapshot().runId();
            assertTrue(
                failed.markFailed(
                    ConfirmedRunError.DEVICE_LOSS,
                    "vk.device_lost",
                    NORMAL
                )
            );
            assertTrue(failed.markCleanShutdown(NORMAL));
            assertEquals(RunPhase.FAILED, failed.snapshot().phase());
            assertTrue(failed.snapshot().cleanShutdown());
            assertEquals(
                ConfirmedRunError.DEVICE_LOSS,
                failed.snapshot().currentError()
            );
        }

        try (RunStateStore next = RunStateStore.open(root, identity())) {
            assertEquals(RunPhase.FAILED, next.snapshot().previousRun().phase());
            assertTrue(next.snapshot().previousRun().cleanShutdown());
            assertEquals(
                ConfirmedRunError.DEVICE_LOSS,
                next.snapshot().previousRun().error()
            );
            assertEquals(
                failedId,
                next.snapshot().lastConfirmedFailure().runId()
            );
            assertEquals(
                failedId,
                next.snapshot().safeStart().candidateEvent()
            );
            assertTrue(next.safeStartOfferAvailable());
        }
    }

    @Test
    void terminalFailureRejectsRebaseAndKeepsOriginalIdentityAtClean()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("failed-rebase-terminal");
        RunStateIdentity initialOff = liveIdentity(
            FINGERPRINT,
            REQUESTED & ~DLSS_MASK,
            NORMAL & ~DLSS_MASK
        );
        UUID failedId;
        try (RunStateStore store = RunStateStore.open(root, initialOff)) {
            failedId = store.snapshot().runId();
            assertTrue(
                store.markFailed(
                    ConfirmedRunError.DEVICE_LOSS,
                    "vk.device_lost",
                    NORMAL & ~DLSS_MASK
                )
            );
            RunStateRecord.ConfirmedFailure failure =
                store.snapshot().lastConfirmedFailure();
            RunStateIdentity replacement = liveIdentity(
                DLSS_ON_FINGERPRINT,
                REQUESTED,
                NORMAL
            );

            assertFalse(
                store.rebaseIdentity(
                    replacement,
                    NORMAL & ~DLSS_MASK
                )
            );
            assertEquals(RunPhase.FAILED, store.snapshot().phase());
            assertEquals(
                ConfirmedRunError.DEVICE_LOSS,
                store.snapshot().currentError()
            );
            assertEquals(
                "vk.device_lost",
                store.snapshot().currentErrorContext()
            );
            assertEquals(
                FINGERPRINT,
                store.snapshot().configFingerprint()
            );
            assertEquals(
                REQUESTED & ~DLSS_MASK,
                store.snapshot().requestedFeatureMask()
            );
            assertEquals(
                failure,
                store.snapshot().lastConfirmedFailure()
            );

            long currentRegistryMask = NORMAL;
            assertTrue(
                (
                    currentRegistryMask
                        & ~store.snapshot().requestedFeatureMask()
                ) != 0L
            );
            assertTrue(
                store.markCleanShutdown(
                    store.snapshot().effectiveFeatureMask()
                )
            );
            assertEquals(RunPhase.FAILED, store.snapshot().phase());
            assertTrue(store.snapshot().cleanShutdown());
            assertEquals(
                FINGERPRINT,
                store.snapshot().configFingerprint()
            );
            assertEquals(
                failure,
                store.snapshot().lastConfirmedFailure()
            );
        }

        try (RunStateStore next = RunStateStore.open(root, initialOff)) {
            assertEquals(
                RunPhase.FAILED,
                next.snapshot().previousRun().phase()
            );
            assertTrue(next.snapshot().previousRun().cleanShutdown());
            assertEquals(
                failedId,
                next.snapshot().safeStart().candidateEvent()
            );
        }
    }

    @Test
    void failureAndCleanUseCurrentMaskWithoutChangingStableLkg()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("current-lifecycle-mask");
        try (RunStateStore store = RunStateStore.open(root, identity())) {
            markStable(store, NORMAL);
            RunStateRecord.LastKnownGood lkg =
                store.snapshot().lastKnownGood();
            assertEquals(NORMAL, lkg.effectiveFeatureMask());

            long reducedMask = NORMAL & ~DLSS_MASK;
            assertTrue(
                store.markFailed(
                    ConfirmedRunError.BLOCKFRAME_ERROR,
                    "feature-quarantined",
                    reducedMask
                )
            );
            assertEquals(
                reducedMask,
                store.snapshot().effectiveFeatureMask()
            );
            assertEquals(lkg, store.snapshot().lastKnownGood());

            assertTrue(store.markCleanShutdown(0L));
            assertEquals(0L, store.snapshot().effectiveFeatureMask());
            assertEquals(lkg, store.snapshot().lastKnownGood());
        }
    }

    @Test
    void lateWriteFailuresNeverTurnInMemoryClaimsIntoDurableState()
        throws Exception {
        Path failedRoot =
            this.temporaryDirectory.resolve("late-failed-write");
        RunStateStore failed = RunStateStore.open(
            failedRoot,
            identity(),
            new NthWriteDeniedIo(2),
            () -> new UUID(0L, 201L)
        );
        assertFalse(
            failed.markFailed(
                ConfirmedRunError.DEVICE_LOSS,
                "vk.device_lost",
                NORMAL
            )
        );
        assertEquals(RunPhase.FAILED, failed.snapshot().phase());
        assertEquals(
            RunStatePersistenceStatus.READ_ONLY_IO_FAILURE,
            failed.persistenceStatus()
        );
        failed.close();
        try (
            RunStateStore reopened =
                RunStateStore.open(failedRoot, identity())
        ) {
            assertEquals(
                RunPhase.UNCLEAN,
                reopened.snapshot().previousRun().phase()
            );
            assertNull(reopened.snapshot().lastConfirmedFailure());
        }

        Path stableRoot =
            this.temporaryDirectory.resolve("late-stable-write");
        RunStateStore stable = RunStateStore.open(
            stableRoot,
            identity(),
            new NthWriteDeniedIo(5),
            () -> new UUID(0L, 202L)
        );
        assertTrue(stable.markInitializing(RunBackend.VULKAN, NORMAL));
        assertTrue(stable.markActiveFeaturesPublished(NORMAL));
        assertTrue(stable.markFirstWorldFrame(NORMAL));
        assertFalse(stable.markStable(NORMAL));
        assertEquals(RunPhase.STABLE, stable.snapshot().phase());
        assertNotNull(stable.snapshot().lastKnownGood());
        stable.close();
        try (
            RunStateStore reopened =
                RunStateStore.open(stableRoot, identity())
        ) {
            assertNull(reopened.snapshot().lastKnownGood());
            assertEquals(
                RunPhase.UNCLEAN,
                reopened.snapshot().previousRun().phase()
            );
        }

        Path cleanRoot =
            this.temporaryDirectory.resolve("late-clean-write");
        RunStateStore clean = RunStateStore.open(
            cleanRoot,
            identity(),
            new NthWriteDeniedIo(6),
            () -> new UUID(0L, 203L)
        );
        markStable(clean, NORMAL);
        assertFalse(clean.markCleanShutdown(NORMAL));
        assertTrue(clean.snapshot().cleanShutdown());
        clean.close();
        try (
            RunStateStore reopened =
                RunStateStore.open(cleanRoot, identity())
        ) {
            assertEquals(
                RunPhase.UNCLEAN,
                reopened.snapshot().previousRun().phase()
            );
            assertFalse(
                reopened.snapshot().previousRun().cleanShutdown()
            );
            assertNotNull(reopened.snapshot().lastKnownGood());
        }
    }

    @Test
    void corruptAndTruncatedNewestSlotsEachRecoverTheOlderValidSlot()
        throws Exception {
        for (boolean truncate : new boolean[] {false, true}) {
            Path root = this.temporaryDirectory.resolve(
                truncate ? "truncated-newest" : "corrupt-newest"
            );
            UUID priorId;
            RunStateStore first = RunStateStore.open(root, identity());
            priorId = first.snapshot().runId();
            assertTrue(first.markInitializing(RunBackend.VULKAN, NORMAL));
            first.close();

            Path newest = root.resolve(RunStateStore.SLOT_B_FILE);
            byte[] valid = Files.readAllBytes(newest);
            if (truncate) {
                Files.write(
                    newest,
                    java.util.Arrays.copyOf(valid, valid.length / 2)
                );
            } else {
                valid[valid.length / 3] ^= 1;
                Files.write(newest, valid);
            }

            try (RunStateStore recovered = RunStateStore.open(
                root,
                identity()
            )) {
                assertEquals(
                    RunStatePersistenceStatus.READ_WRITE,
                    recovered.persistenceStatus()
                );
                assertEquals(2L, recovered.snapshot().runGeneration());
                assertEquals(
                    priorId,
                    recovered.snapshot().previousRun().runId()
                );
                assertEquals(
                    RunPhase.UNCLEAN,
                    recovered.snapshot().previousRun().phase()
                );
                assertEquals(
                    RunStateRecord.CURRENT_SCHEMA_VERSION,
                    RunStateCodec.decode(
                        Files.readAllBytes(newest)
                    ).schemaVersion()
                );
            }
        }
    }

    @Test
    void emptyOrBothCorruptSlotsDisablePersistenceReadOnly()
        throws Exception {
        Path emptyRoot = this.temporaryDirectory.resolve("empty");
        Files.createDirectories(emptyRoot);
        Files.write(
            emptyRoot.resolve(RunStateStore.SLOT_A_FILE),
            new byte[0]
        );
        try (RunStateStore empty = RunStateStore.open(emptyRoot, identity())) {
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_CORRUPT_STATE,
                empty.persistenceStatus()
            );
        }

        Path badRoot = this.temporaryDirectory.resolve("both-bad");
        Files.createDirectories(badRoot);
        Files.writeString(
            badRoot.resolve(RunStateStore.SLOT_A_FILE),
            "not-state\n"
        );
        Files.writeString(
            badRoot.resolve(RunStateStore.SLOT_B_FILE),
            "also-not-state\n"
        );
        try (RunStateStore bad = RunStateStore.open(badRoot, identity())) {
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_CORRUPT_STATE,
                bad.persistenceStatus()
            );
        }
    }

    @Test
    void futureFormatIsNeverOverwritten() throws Exception {
        Path root = this.temporaryDirectory.resolve("future");
        Files.createDirectories(root);
        Path slot = root.resolve(RunStateStore.SLOT_A_FILE);
        byte[] future = (
            "magic=blockframe-run-state\n"
                + "schema-version=99\n"
                + "writer-version=1\n"
                + "future-data=preserve-me\n"
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(slot, future);

        try (RunStateStore store = RunStateStore.open(root, identity())) {
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_FUTURE_FORMAT,
                store.persistenceStatus()
            );
            assertFalse(store.markInitializing(RunBackend.VULKAN, NORMAL));
        }
        assertArrayEquals(future, Files.readAllBytes(slot));
        assertFalse(Files.exists(root.resolve(RunStateStore.SLOT_B_FILE)));
    }

    @Test
    void unknownSchemaFailsReadOnlyWithoutRewritingInput() throws Exception {
        Path root = this.temporaryDirectory.resolve("unknown-schema");
        Files.createDirectories(root);
        Path slot = root.resolve(RunStateStore.SLOT_A_FILE);
        byte[] unknown = (
            "magic=blockframe-run-state\n"
                + "schema-version=0\n"
                + "writer-version=1\n"
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(slot, unknown);

        try (RunStateStore store = RunStateStore.open(root, identity())) {
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_CORRUPT_STATE,
                store.persistenceStatus()
            );
        }
        assertArrayEquals(unknown, Files.readAllBytes(slot));
    }

    @Test
    void equalCommitGenerationAndGenerationOverflowFailReadOnly()
        throws Exception {
        Path equalRoot = this.temporaryDirectory.resolve("equal");
        Files.createDirectories(equalRoot);
        Files.write(
            equalRoot.resolve(RunStateStore.SLOT_A_FILE),
            RunStateCodec.encode(rawRecord(2L, 8L, 10L))
        );
        Files.write(
            equalRoot.resolve(RunStateStore.SLOT_B_FILE),
            RunStateCodec.encode(rawRecord(3L, 9L, 10L))
        );
        try (RunStateStore equal = RunStateStore.open(
            equalRoot,
            identity()
        )) {
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_AMBIGUOUS_GENERATION,
                equal.persistenceStatus()
            );
        }

        Path overflowRoot = this.temporaryDirectory.resolve("overflow");
        Files.createDirectories(overflowRoot);
        Files.write(
            overflowRoot.resolve(RunStateStore.SLOT_A_FILE),
            RunStateCodec.encode(
                rawRecord(4L, Long.MAX_VALUE, Long.MAX_VALUE)
            )
        );
        try (RunStateStore overflow = RunStateStore.open(
            overflowRoot,
            identity()
        )) {
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_GENERATION_OVERFLOW,
                overflow.persistenceStatus()
            );
        }
    }

    @Test
    void unavailableAtomicMoveUsesValidatedOlderSlotPublication()
        throws Exception {
        Path root = this.temporaryDirectory.resolve("non-atomic");
        AtomicUnsupportedIo io = new AtomicUnsupportedIo();
        try (RunStateStore store = RunStateStore.open(
            root,
            identity(),
            io,
            () -> new UUID(0L, 77L)
        )) {
            assertEquals(
                RunStatePersistenceStatus.READ_WRITE,
                store.persistenceStatus()
            );
            assertEquals(
                RunStatePublicationMode.RECOVERABLE_TWO_SLOT,
                store.publicationMode()
            );
            assertTrue(store.markInitializing(RunBackend.VULKAN, NORMAL));
            assertEquals(
                RunStatePublicationMode.RECOVERABLE_TWO_SLOT,
                store.publicationMode()
            );
            RunStateRecord a = RunStateCodec.decode(
                Files.readAllBytes(root.resolve(RunStateStore.SLOT_A_FILE))
            );
            RunStateRecord b = RunStateCodec.decode(
                Files.readAllBytes(root.resolve(RunStateStore.SLOT_B_FILE))
            );
            assertNotEquals(a.commitGeneration(), b.commitGeneration());
            assertEquals(2L, Math.max(
                a.commitGeneration(),
                b.commitGeneration()
            ));
        }
    }

    @Test
    void permissionFailureDisablesPersistenceButLeavesUsableState() {
        RunStateStore store = RunStateStore.open(
            this.temporaryDirectory.resolve("denied"),
            identity(),
            new WriteDeniedIo(),
            () -> new UUID(0L, 88L)
        );
        assertEquals(
            RunStatePersistenceStatus.READ_ONLY_IO_FAILURE,
            store.persistenceStatus()
        );
        assertEquals(RunPhase.STARTING, store.snapshot().phase());
        assertFalse(store.markInitializing(RunBackend.VULKAN, NORMAL));
        assertEquals(RunPhase.INITIALIZING, store.snapshot().phase());
        store.close();
    }

    @Test
    void uncheckedProviderWriteDeleteAndMoveFailuresStayFailOpen() {
        assertUncheckedLifecycleFailure(
            "unchecked-write",
            new NthUncheckedWriteIo()
        );
        assertUncheckedLifecycleFailure(
            "unchecked-delete",
            new NthUncheckedDeleteIo()
        );
        assertUncheckedLifecycleFailure(
            "unchecked-move",
            new NthUncheckedMoveIo()
        );
    }

    @Test
    void uncheckedProviderOpenAndReadFailuresReturnUsableStores() {
        assertUncheckedOpenFailure(
            "unchecked-ensure",
            new UncheckedEnsureIo()
        );
        assertUncheckedOpenFailure(
            "unchecked-lock",
            new UncheckedLockIo()
        );
        assertUncheckedOpenFailure(
            "unchecked-read",
            new UncheckedReadIo()
        );
    }

    @Test
    void fixedTempCleanupFailureAlsoFallsBackReadOnly() {
        RunStateStore store = RunStateStore.open(
            this.temporaryDirectory.resolve("cleanup-denied"),
            identity(),
            new CleanupDeniedIo(),
            () -> new UUID(0L, 89L)
        );
        assertEquals(
            RunStatePersistenceStatus.READ_ONLY_IO_FAILURE,
            store.persistenceStatus()
        );
        assertEquals(RunPhase.STARTING, store.snapshot().phase());
        store.close();
    }

    @Test
    void lockConflictIsNonBlockingAndNeverReadsSlots() {
        LockConflictIo io = new LockConflictIo();
        long started = System.nanoTime();
        try (RunStateStore store = RunStateStore.open(
            this.temporaryDirectory.resolve("fake-lock"),
            identity(),
            io,
            () -> new UUID(0L, 99L)
        )) {
            long elapsedMillis =
                (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMillis < 1_000L);
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_LOCK_CONFLICT,
                store.persistenceStatus()
            );
            assertEquals(0, io.reads.get());
            assertFalse(store.safeStartOfferAvailable());
        }
    }

    @Test
    void cachedSnapshotAndStatusReadsPerformNoFilesystemOperation() {
        CountingIo io = new CountingIo();
        try (RunStateStore store = RunStateStore.open(
            this.temporaryDirectory.resolve("cached-hotpath"),
            identity(),
            io,
            () -> new UUID(0L, 100L)
        )) {
            int operationsAfterOpen = io.operations.get();
            RunStateRecord expected = store.snapshot();
            for (int index = 0; index < 10_000; index++) {
                assertEquals(expected, store.snapshot());
                store.persistenceStatus();
                store.publicationMode();
                store.safeStartActive();
                store.safeStartOfferAvailable();
            }
            assertEquals(operationsAfterOpen, io.operations.get());
        }
    }

    @Test
    void realSameJvmLockConflictReturnsImmediately() {
        Path root = this.temporaryDirectory.resolve("real-lock");
        try (
            RunStateStore owner = RunStateStore.open(root, identity());
            RunStateStore contender = RunStateStore.open(root, identity())
        ) {
            assertEquals(
                RunStatePersistenceStatus.READ_WRITE,
                owner.persistenceStatus()
            );
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_LOCK_CONFLICT,
                contender.persistenceStatus()
            );
        }
    }

    @Test
    void safeStartDeclineAndCurrentRunActivationAreExplicitAndOneShot()
        throws Exception {
        Path declinedRoot = this.temporaryDirectory.resolve("declined");
        createFailedEvent(declinedRoot);
        try (RunStateStore declined = RunStateStore.open(
            declinedRoot,
            identity()
        )) {
            assertTrue(declined.offerSafeStart());
            assertTrue(declined.declineSafeStart());
            assertFalse(declined.declineSafeStart());
            assertFalse(declined.queueSafeStartForNextRun());
            assertFalse(declined.activateSafeStartForCurrentRun());
            assertEquals(NORMAL, declined.snapshot().effectiveFeatureMask());
        }

        Path activeRoot = this.temporaryDirectory.resolve("active-now");
        createFailedEvent(activeRoot);
        try (RunStateStore active = RunStateStore.open(
            activeRoot,
            identity()
        )) {
            assertTrue(active.offerSafeStart());
            assertTrue(active.activateSafeStartForCurrentRun());
            assertTrue(active.safeStartActive());
            assertEquals(SAFE, active.snapshot().effectiveFeatureMask());
            assertTrue(active.markInitializing(RunBackend.VULKAN, SAFE));
            assertFalse(active.activateSafeStartForCurrentRun());
        }
    }

    @Test
    void queuedSafeStartConsumesOncePreservesFailureAndNeverPromotesLkg()
        throws Exception {
        Path root = this.temporaryDirectory.resolve("safe-lkg");
        Path userConfiguration = this.temporaryDirectory.resolve(
            "blockframe-engine.properties"
        );
        byte[] originalConfiguration =
            "render.entityMotionScratchEnabled=true\n".getBytes(
                java.nio.charset.StandardCharsets.UTF_8
            );
        Files.write(userConfiguration, originalConfiguration);
        UUID baselineLkg;
        try (RunStateStore baseline = RunStateStore.open(root, identity())) {
            markStable(baseline, NORMAL);
            baselineLkg = baseline.snapshot().lastKnownGood().runId();
            assertTrue(baseline.markCleanShutdown(NORMAL));
        }

        UUID failureId;
        try (RunStateStore failure = RunStateStore.open(root, identity())) {
            failureId = failure.snapshot().runId();
            assertTrue(
                failure.markFailed(
                    ConfirmedRunError.BLOCKFRAME_ERROR,
                    "renderer.publish",
                    NORMAL
                )
            );
            assertTrue(failure.markCleanShutdown(NORMAL));
        }

        try (RunStateStore decision = RunStateStore.open(root, identity())) {
            assertTrue(decision.offerSafeStart());
            assertTrue(decision.queueSafeStartForNextRun());
            assertFalse(decision.queueSafeStartForNextRun());
            assertTrue(decision.markCleanShutdown(NORMAL));
        }

        try (RunStateStore safe = RunStateStore.open(root, identity())) {
            assertTrue(safe.safeStartActive());
            assertEquals(SAFE, safe.snapshot().effectiveFeatureMask());
            markStable(safe, SAFE);
            assertEquals(
                baselineLkg,
                safe.snapshot().lastKnownGood().runId()
            );
            assertEquals(
                failureId,
                safe.snapshot().lastConfirmedFailure().runId()
            );
            assertTrue(safe.markCleanShutdown(SAFE));
        }

        try (RunStateStore normal = RunStateStore.open(root, identity())) {
            assertFalse(normal.safeStartActive());
            assertEquals(NORMAL, normal.snapshot().effectiveFeatureMask());
            assertEquals(
                baselineLkg,
                normal.snapshot().lastKnownGood().runId()
            );
            assertEquals(
                failureId,
                normal.snapshot().lastConfirmedFailure().runId()
            );
            assertFalse(normal.safeStartOfferAvailable());
        }
        assertArrayEquals(
            originalConfiguration,
            Files.readAllBytes(userConfiguration)
        );
    }

    @Test
    void queuedSafeStartWinsOverANewerFailureAtConsumption()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("queued-before-new-failure");
        UUID queuedEvent;
        try (RunStateStore first = RunStateStore.open(root, identity())) {
            queuedEvent = first.snapshot().runId();
            assertTrue(
                first.markFailed(
                    ConfirmedRunError.BLOCKFRAME_ERROR,
                    "queued-event",
                    NORMAL
                )
            );
            assertTrue(first.markCleanShutdown(NORMAL));
        }

        UUID newerFailure;
        RunStateStore decision = RunStateStore.open(root, identity());
        assertEquals(
            queuedEvent,
            decision.snapshot().safeStart().candidateEvent()
        );
        assertTrue(decision.offerSafeStart());
        assertTrue(decision.queueSafeStartForNextRun());
        newerFailure = decision.snapshot().runId();
        assertTrue(
            decision.markFailed(
                ConfirmedRunError.DEVICE_LOSS,
                "newer-event",
                NORMAL
            )
        );
        decision.close();

        try (RunStateStore safe = RunStateStore.open(root, identity())) {
            RunStateRecord.SafeStartState state =
                safe.snapshot().safeStart();
            assertTrue(state.active());
            assertEquals(queuedEvent, state.candidateEvent());
            assertEquals(queuedEvent, state.queuedEvent());
            assertEquals(queuedEvent, state.consumedEvent());
            assertEquals(
                newerFailure,
                safe.snapshot().previousRun().runId()
            );
            assertEquals(
                RunPhase.FAILED,
                safe.snapshot().previousRun().phase()
            );
            assertEquals(
                newerFailure,
                safe.snapshot().lastConfirmedFailure().runId()
            );
            markStable(safe, SAFE);
            assertTrue(safe.markCleanShutdown(SAFE));
        }

        try (RunStateStore later = RunStateStore.open(root, identity())) {
            assertFalse(later.safeStartActive());
            assertNull(later.snapshot().safeStart().candidateEvent());
            assertFalse(later.safeStartOfferAvailable());
            assertEquals(
                newerFailure,
                later.snapshot().lastConfirmedFailure().runId()
            );
        }
    }

    @Test
    void consumedSafeStartNeverLeaksAcrossConfigurationIdentity()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("consumed-identity-boundary");
        try (RunStateStore first = RunStateStore.open(root, identity())) {
            assertTrue(
                first.markFailed(
                    ConfirmedRunError.BLOCKFRAME_ERROR,
                    "identity-event",
                    NORMAL
                )
            );
            assertTrue(first.markCleanShutdown(NORMAL));
        }
        RunStateStore decision = RunStateStore.open(root, identity());
        assertTrue(decision.offerSafeStart());
        assertTrue(decision.queueSafeStartForNextRun());
        assertTrue(
            decision.markFailed(
                ConfirmedRunError.DEVICE_LOSS,
                "newer-identity-event",
                NORMAL
            )
        );
        decision.close();
        try (RunStateStore safe = RunStateStore.open(root, identity())) {
            assertTrue(safe.safeStartActive());
            markStable(safe, SAFE);
            assertTrue(safe.markCleanShutdown(SAFE));
        }

        RunStateIdentity different = liveIdentity(
            SHARPENED_FINGERPRINT,
            REQUESTED,
            NORMAL
        );
        try (RunStateStore next = RunStateStore.open(root, different)) {
            assertNull(next.snapshot().safeStart().candidateEvent());
            assertFalse(next.safeStartOfferAvailable());
        }
    }

    @Test
    void consumedSafeStartDoesNotReviveHistoricalFailure()
        throws Exception {
        Path root =
            this.temporaryDirectory.resolve("no-historical-revival");
        UUID historicalFailure;
        try (RunStateStore failed = RunStateStore.open(root, identity())) {
            historicalFailure = failed.snapshot().runId();
            assertTrue(
                failed.markFailed(
                    ConfirmedRunError.BLOCKFRAME_ERROR,
                    "historical-event",
                    NORMAL
                )
            );
            assertTrue(failed.markCleanShutdown(NORMAL));
        }
        try (RunStateStore declined = RunStateStore.open(root, identity())) {
            assertTrue(declined.offerSafeStart());
            assertTrue(declined.declineSafeStart());
            markStable(declined, NORMAL);
            assertTrue(declined.markCleanShutdown(NORMAL));
        }

        UUID queuedEvent;
        RunStateStore unclean = RunStateStore.open(root, identity());
        queuedEvent = unclean.snapshot().runId();
        unclean.close();
        try (RunStateStore decision = RunStateStore.open(root, identity())) {
            assertEquals(
                queuedEvent,
                decision.snapshot().safeStart().candidateEvent()
            );
            assertTrue(decision.offerSafeStart());
            assertTrue(decision.queueSafeStartForNextRun());
            assertTrue(decision.markCleanShutdown(NORMAL));
        }
        try (RunStateStore safe = RunStateStore.open(root, identity())) {
            assertTrue(safe.safeStartActive());
            assertEquals(
                queuedEvent,
                safe.snapshot().safeStart().consumedEvent()
            );
            markStable(safe, SAFE);
            assertTrue(safe.markCleanShutdown(SAFE));
        }
        try (RunStateStore next = RunStateStore.open(root, identity())) {
            assertNull(next.snapshot().safeStart().candidateEvent());
            assertFalse(next.safeStartOfferAvailable());
            assertEquals(
                historicalFailure,
                next.snapshot().lastConfirmedFailure().runId()
            );
        }
    }

    @Test
    void lkgSurvivesMultiplePartialRunRevisions() throws Exception {
        Path root = this.temporaryDirectory.resolve("lkg-partials");
        UUID lkgId;
        try (RunStateStore stable = RunStateStore.open(root, identity())) {
            markStable(stable, NORMAL);
            lkgId = stable.snapshot().lastKnownGood().runId();
        }
        try (RunStateStore partial = RunStateStore.open(root, identity())) {
            assertEquals(lkgId, partial.snapshot().lastKnownGood().runId());
            assertTrue(partial.markInitializing(RunBackend.VULKAN, NORMAL));
        }
        try (RunStateStore another = RunStateStore.open(root, identity())) {
            assertEquals(lkgId, another.snapshot().lastKnownGood().runId());
            assertEquals(
                RunPhase.UNCLEAN,
                another.snapshot().previousRun().phase()
            );
        }
    }

    private static void markStable(RunStateStore store, long mask) {
        assertTrue(store.markInitializing(RunBackend.VULKAN, mask));
        assertTrue(store.markActiveFeaturesPublished(mask));
        assertTrue(store.markFirstWorldFrame(mask));
        assertTrue(store.markStable(mask));
    }

    private void assertUncheckedLifecycleFailure(
        String directory,
        RunStateIo io
    ) {
        try (RunStateStore store = RunStateStore.open(
            this.temporaryDirectory.resolve(directory),
            identity(),
            io,
            UUID::randomUUID
        )) {
            assertEquals(
                RunStatePersistenceStatus.READ_WRITE,
                store.persistenceStatus()
            );
            assertFalse(
                store.markInitializing(
                    RunBackend.VULKAN,
                    NORMAL
                )
            );
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_IO_FAILURE,
                store.persistenceStatus()
            );
            assertEquals(
                RunPhase.INITIALIZING,
                store.snapshot().phase()
            );
        }
    }

    private void assertUncheckedOpenFailure(
        String directory,
        RunStateIo io
    ) {
        try (RunStateStore store = RunStateStore.open(
            this.temporaryDirectory.resolve(directory),
            identity(),
            io,
            UUID::randomUUID
        )) {
            assertEquals(
                RunStatePersistenceStatus.READ_ONLY_IO_FAILURE,
                store.persistenceStatus()
            );
            assertEquals(RunPhase.STARTING, store.snapshot().phase());
            assertFalse(
                store.markInitializing(
                    RunBackend.VULKAN,
                    NORMAL
                )
            );
            assertEquals(
                RunPhase.INITIALIZING,
                store.snapshot().phase()
            );
        }
    }

    private static void createFailedEvent(Path root) {
        try (RunStateStore failed = RunStateStore.open(root, identity())) {
            assertTrue(
                failed.markFailed(
                    ConfirmedRunError.BLOCKFRAME_ERROR,
                    "renderer.init",
                    NORMAL
                )
            );
            assertTrue(failed.markCleanShutdown(NORMAL));
        }
    }

    private static RunStateIdentity identity() {
        return new RunStateIdentity(
            "1.0.0",
            "1.21.8",
            FINGERPRINT,
            1,
            REQUESTED,
            NORMAL,
            SAFE
        );
    }

    private static RunStateIdentity liveIdentity(
        String fingerprint,
        long requested,
        long effective
    ) {
        return new RunStateIdentity(
            "1.0.0",
            "1.21.8",
            fingerprint,
            1,
            requested,
            effective,
            SAFE
        );
    }

    private static RunStateRecord rawRecord(
        long uuidLeastBits,
        long runGeneration,
        long commitGeneration
    ) {
        return new RunStateRecord(
            1,
            1,
            new UUID(0L, uuidLeastBits),
            runGeneration,
            commitGeneration,
            "1.0.0",
            "1.21.8",
            RunBackend.UNKNOWN,
            FINGERPRINT,
            1,
            REQUESTED,
            NORMAL,
            RunPhase.STARTING,
            RunCheckpoint.PROCESS_STARTED,
            false,
            ConfirmedRunError.NONE,
            RunStateRecord.NO_CONTEXT,
            null,
            null,
            null,
            RunStateRecord.SafeStartState.empty()
        );
    }

    private abstract static class DelegatingIo implements RunStateIo {
        final NioRunStateIo delegate = new NioRunStateIo();

        @Override
        public void ensureDirectory(Path directory) throws IOException {
            this.delegate.ensureDirectory(directory);
        }

        @Override
        public LockHandle tryAcquire(Path lockFile) throws IOException {
            return this.delegate.tryAcquire(lockFile);
        }

        @Override
        public byte[] readBounded(Path path, int maximumBytes)
            throws IOException {
            return this.delegate.readBounded(path, maximumBytes);
        }

        @Override
        public void writeForced(Path path, byte[] content)
            throws IOException {
            this.delegate.writeForced(path, content);
        }

        @Override
        public void atomicReplace(Path source, Path target)
            throws IOException {
            this.delegate.atomicReplace(source, target);
        }

        @Override
        public void replace(Path source, Path target) throws IOException {
            this.delegate.replace(source, target);
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            this.delegate.deleteIfExists(path);
        }
    }

    private static final class AtomicUnsupportedIo extends DelegatingIo {
        @Override
        public void atomicReplace(Path source, Path target)
            throws IOException {
            throw new AtomicMoveNotSupportedException(
                source.toString(),
                target.toString(),
                "injected"
            );
        }
    }

    private static final class WriteDeniedIo extends DelegatingIo {
        @Override
        public void writeForced(Path path, byte[] content)
            throws IOException {
            throw new AccessDeniedException(path.toString());
        }
    }

    private static final class NthWriteDeniedIo extends DelegatingIo {
        private final int deniedWrite;
        private int writes;

        private NthWriteDeniedIo(int deniedWrite) {
            this.deniedWrite = deniedWrite;
        }

        @Override
        public void writeForced(Path path, byte[] content)
            throws IOException {
            this.writes++;
            if (this.writes == this.deniedWrite) {
                throw new AccessDeniedException(path.toString());
            }
            super.writeForced(path, content);
        }
    }

    private static final class NthUncheckedWriteIo
        extends DelegatingIo {
        private int writes;

        @Override
        public void writeForced(Path path, byte[] content)
            throws IOException {
            if (++this.writes == 2) {
                throw new ReadOnlyFileSystemException();
            }
            super.writeForced(path, content);
        }
    }

    private static final class NthUncheckedDeleteIo
        extends DelegatingIo {
        private int deletes;

        @Override
        public void deleteIfExists(Path path) throws IOException {
            if (++this.deletes == 2) {
                throw new UnsupportedOperationException(
                    "injected delete failure"
                );
            }
            super.deleteIfExists(path);
        }
    }

    private static final class NthUncheckedMoveIo
        extends DelegatingIo {
        private int moves;

        @Override
        public void atomicReplace(Path source, Path target)
            throws IOException {
            if (++this.moves == 2) {
                throw new UnsupportedOperationException(
                    "injected move failure"
                );
            }
            super.atomicReplace(source, target);
        }
    }

    private static final class UncheckedEnsureIo extends DelegatingIo {
        @Override
        public void ensureDirectory(Path directory) {
            throw new UnsupportedOperationException(
                "injected ensure failure"
            );
        }
    }

    private static final class UncheckedLockIo extends DelegatingIo {
        @Override
        public LockHandle tryAcquire(Path lockFile) {
            throw new ReadOnlyFileSystemException();
        }
    }

    private static final class UncheckedReadIo extends DelegatingIo {
        @Override
        public byte[] readBounded(Path path, int maximumBytes) {
            throw new UnsupportedOperationException(
                "injected read failure"
            );
        }
    }

    private static final class CleanupDeniedIo extends DelegatingIo {
        @Override
        public void deleteIfExists(Path path) throws IOException {
            throw new AccessDeniedException(path.toString());
        }
    }

    private static final class LockConflictIo extends DelegatingIo {
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public LockHandle tryAcquire(Path lockFile) {
            return null;
        }

        @Override
        public byte[] readBounded(Path path, int maximumBytes)
            throws IOException {
            this.reads.incrementAndGet();
            return super.readBounded(path, maximumBytes);
        }
    }

    private static final class CountingIo extends DelegatingIo {
        private final AtomicInteger operations = new AtomicInteger();

        @Override
        public void ensureDirectory(Path directory) throws IOException {
            this.operations.incrementAndGet();
            super.ensureDirectory(directory);
        }

        @Override
        public LockHandle tryAcquire(Path lockFile) throws IOException {
            this.operations.incrementAndGet();
            return super.tryAcquire(lockFile);
        }

        @Override
        public byte[] readBounded(Path path, int maximumBytes)
            throws IOException {
            this.operations.incrementAndGet();
            return super.readBounded(path, maximumBytes);
        }

        @Override
        public void writeForced(Path path, byte[] content)
            throws IOException {
            this.operations.incrementAndGet();
            super.writeForced(path, content);
        }

        @Override
        public void atomicReplace(Path source, Path target)
            throws IOException {
            this.operations.incrementAndGet();
            super.atomicReplace(source, target);
        }

        @Override
        public void replace(Path source, Path target) throws IOException {
            this.operations.incrementAndGet();
            super.replace(source, target);
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            this.operations.incrementAndGet();
            super.deleteIfExists(path);
        }
    }
}
