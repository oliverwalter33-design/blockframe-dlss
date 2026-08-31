package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SafeStartScreenControllerTest {
    @Test
    void unavailableFirstTitleConsumesOnlyTheProcessLocalScreenChance() {
        FakeAccess access = new FakeAccess();
        SafeStartScreenController controller =
            new SafeStartScreenController(access);
        Object firstTitle = new Object();

        assertFalse(controller.offerOnTitleScreen(firstTitle));
        access.available = true;
        assertFalse(controller.offerOnTitleScreen(firstTitle));
        assertFalse(controller.offerOnTitleScreen(new Object()));

        assertEquals(1, access.availabilityChecks);
        assertEquals(0, access.markCalls);
        assertEquals(
            SafeStartScreenController.OfferState.NO_OFFER,
            controller.state()
        );
    }

    @Test
    void sameFirstTitleMayRebuildWithoutMarkingEventTwice() {
        FakeAccess access = new FakeAccess();
        access.available = true;
        SafeStartScreenController controller =
            new SafeStartScreenController(access);
        Object firstTitle = new Object();

        assertTrue(controller.offerOnTitleScreen(firstTitle));
        assertTrue(controller.offerOnTitleScreen(firstTitle));
        assertFalse(controller.offerOnTitleScreen(new Object()));

        assertEquals(1, access.availabilityChecks);
        assertEquals(1, access.markCalls);
        assertEquals(
            SafeStartScreenController.OfferState.OFFERED,
            controller.state()
        );
    }

    @Test
    void ignoringAnOfferDoesNotQueueDeclineOrMutateAnythingElse() {
        FakeAccess access = new FakeAccess();
        access.available = true;
        SafeStartScreenController controller =
            new SafeStartScreenController(access);

        assertTrue(controller.offerOnTitleScreen(new Object()));

        assertEquals(0, access.queueCalls);
        assertEquals(0, access.declineCalls);
        assertEquals(
            SafeStartScreenController.OfferState.OFFERED,
            controller.state()
        );
    }

    @Test
    void explicitAcceptanceQueuesOnlyTheNextProcessAndOnlyOnce() {
        FakeAccess access = new FakeAccess();
        access.available = true;
        access.queueResult = true;
        SafeStartScreenController controller =
            new SafeStartScreenController(access);

        assertFalse(controller.acceptOffer());
        assertTrue(controller.offerOnTitleScreen(new Object()));
        assertTrue(controller.acceptOffer());
        assertFalse(controller.acceptOffer());
        assertFalse(controller.declineOffer());

        assertEquals(1, access.queueCalls);
        assertEquals(0, access.declineCalls);
        assertEquals(
            SafeStartScreenController.OfferState.ACCEPTED,
            controller.state()
        );
    }

    @Test
    void explicitDeclineCannotLaterQueueTheSameOffer() {
        FakeAccess access = new FakeAccess();
        access.available = true;
        SafeStartScreenController controller =
            new SafeStartScreenController(access);

        assertTrue(controller.offerOnTitleScreen(new Object()));
        assertTrue(controller.declineOffer());
        assertFalse(controller.declineOffer());
        assertFalse(controller.acceptOffer());

        assertEquals(1, access.declineCalls);
        assertEquals(0, access.queueCalls);
        assertEquals(
            SafeStartScreenController.OfferState.DECLINED,
            controller.state()
        );
    }

    @Test
    void accessFailuresFailOpenWithoutRepeatingOrCrashing() {
        FakeAccess unavailable = new FakeAccess();
        unavailable.availabilityFailure =
            new IllegalStateException("cached state unavailable");
        SafeStartScreenController unavailableController =
            new SafeStartScreenController(unavailable);
        assertFalse(
            unavailableController.offerOnTitleScreen(new Object())
        );
        assertFalse(
            unavailableController.offerOnTitleScreen(new Object())
        );
        assertEquals(1, unavailable.availabilityChecks);

        FakeAccess marking = new FakeAccess();
        marking.available = true;
        marking.markFailure =
            new IllegalStateException("persistence unavailable");
        SafeStartScreenController markingController =
            new SafeStartScreenController(marking);
        assertFalse(
            markingController.offerOnTitleScreen(new Object())
        );
        assertFalse(markingController.acceptOffer());
        assertEquals(1, marking.markCalls);

        FakeAccess queuing = new FakeAccess();
        queuing.available = true;
        queuing.queueFailure =
            new IllegalStateException("queue unavailable");
        SafeStartScreenController queuingController =
            new SafeStartScreenController(queuing);
        assertTrue(queuingController.offerOnTitleScreen(new Object()));
        assertFalse(queuingController.acceptOffer());
        assertFalse(queuingController.acceptOffer());
        assertEquals(1, queuing.queueCalls);
    }

    @Test
    void offerButtonBoundsRemainInsideNormalAndConstrainedScreens() {
        for (int[] size : new int[][] {
            {854, 480},
            {320, 240},
            {120, 80}
        }) {
            SafeStartScreenController.Bounds bounds =
                SafeStartScreenController.buttonBounds(
                    size[0],
                    size[1]
                );
            assertTrue(bounds.x() >= 0);
            assertTrue(bounds.y() >= 0);
            assertTrue(bounds.width() > 0);
            assertEquals(20, bounds.height());
            assertTrue(bounds.x() + bounds.width() <= size[0]);
            assertTrue(bounds.y() + bounds.height() <= size[1]);
        }
    }

    @Test
    void sourceUsesOnlyCachedRuntimeContractAndClientScreenEvents()
        throws Exception {
        String source = Files.readString(
            Path.of(System.getProperty("blockframe.projectDir"))
                .resolve(
                    "src/main/java/de/morau/nvidiadlss/"
                        + "SafeStartScreenController.java"
                ),
            StandardCharsets.UTF_8
        );

        assertTrue(source.contains("ScreenEvent.Init.Post"));
        assertTrue(source.contains("instanceof TitleScreen"));
        assertTrue(source.contains("event.addListener(offer);"));
        assertTrue(
            source.contains(
                "BlockframeRuntime.safeStartOfferAvailable()"
            )
        );
        assertTrue(
            source.contains(
                "BlockframeRuntime.queueSafeStartForNextRun()"
            )
        );
        assertFalse(source.contains("DlssConfig"));
        assertFalse(source.contains("EngineConfig"));
        assertFalse(source.contains("setMode("));
        assertFalse(source.contains(".save("));
        assertFalse(source.contains("JOptionPane"));
        assertFalse(source.contains("java.awt"));
        assertFalse(source.contains("Thread"));
        assertFalse(source.contains("Vulkan"));
    }

    private static final class FakeAccess
        implements SafeStartScreenController.SafeStartAccess {
        boolean available;
        boolean queueResult;
        int availabilityChecks;
        int markCalls;
        int queueCalls;
        int declineCalls;
        RuntimeException availabilityFailure;
        RuntimeException markFailure;
        RuntimeException queueFailure;
        RuntimeException declineFailure;

        @Override
        public boolean safeStartOfferAvailable() {
            this.availabilityChecks++;
            if (this.availabilityFailure != null) {
                throw this.availabilityFailure;
            }
            return this.available;
        }

        @Override
        public void markSafeStartOffered() {
            this.markCalls++;
            if (this.markFailure != null) {
                throw this.markFailure;
            }
        }

        @Override
        public boolean queueSafeStartForNextRun() {
            this.queueCalls++;
            if (this.queueFailure != null) {
                throw this.queueFailure;
            }
            return this.queueResult;
        }

        @Override
        public void declineSafeStart() {
            this.declineCalls++;
            if (this.declineFailure != null) {
                throw this.declineFailure;
            }
        }
    }
}
