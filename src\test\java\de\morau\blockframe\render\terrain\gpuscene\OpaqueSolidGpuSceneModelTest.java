package de.morau.blockframe.render.terrain.gpuscene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class OpaqueSolidGpuSceneModelTest {
    @Test
    void ownerEventsPublishAndInvalidateBothFrameCopies() {
        OpaqueSolidGpuSceneModel model =
            new OpaqueSolidGpuSceneModel(4, 2);
        Object section = new Object();
        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.PUBLISHED,
            model.publish(section, token(1, 101L, 0L))
        );
        assertEquals(1, model.snapshot().dirtyFrame0());
        assertEquals(1, model.snapshot().dirtyFrame1());
        ArrayList<Integer> frame0 = new ArrayList<>();
        assertTrue(
            model.drainDirty(
                0,
                (slot, bucket, token) -> {
                    frame0.add(slot);
                    return token != null;
                }
            )
        );
        assertEquals(1, frame0.size());
        assertEquals(0, model.snapshot().dirtyFrame0());
        assertEquals(1, model.snapshot().dirtyFrame1());
        assertTrue(model.invalidateBeforeReplace(section));
        assertFalse(model.appendVisible(section, 1.0F));
        assertTrue(model.removeAfterInvalidation(section));
        assertEquals(0, model.snapshot().entries());
    }

    @Test
    void visibleOnlyCompactionWritesExactCommandsAndBuckets() {
        OpaqueSolidGpuSceneModel model =
            new OpaqueSolidGpuSceneModel(8, 4);
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();
        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.PUBLISHED,
            model.publish(first, token(1, 101L, 0L))
        );
        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.PUBLISHED,
            model.publish(second, token(2, 101L, 0L))
        );
        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.PUBLISHED,
            model.publish(third, token(3, 202L, 303L))
        );
        model.beginVisibilityFrame();
        assertTrue(model.appendVisible(first, 1.0F));
        assertTrue(model.appendVisible(third, 0.5F));
        var compacted = model.compactSyntheticForTest();
        assertEquals(2, compacted.commandCount());
        assertEquals(1, compacted.bucketCounts()[0]);
        assertEquals(1, compacted.bucketCounts()[1]);
        assertEquals(36, compacted.commands()[0].indexCount());
        assertEquals(0, compacted.commands()[0].firstIndex());
        assertEquals(0.5F, compacted.commands()[1].visibility());
    }

    @Test
    void overflowAndUnpublishedVisibilityFailClosed() {
        OpaqueSolidGpuSceneModel capacity =
            new OpaqueSolidGpuSceneModel(1, 2);
        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.PUBLISHED,
            capacity.publish(new Object(), token(1, 10L, 0L))
        );
        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.CAPACITY_OVERFLOW,
            capacity.publish(new Object(), token(2, 10L, 0L))
        );
        OpaqueSolidGpuSceneModel buckets =
            new OpaqueSolidGpuSceneModel(4, 1);
        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.PUBLISHED,
            buckets.publish(new Object(), token(1, 10L, 0L))
        );
        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.BUCKET_OVERFLOW,
            buckets.publish(new Object(), token(2, 20L, 0L))
        );
        buckets.beginVisibilityFrame();
        assertFalse(buckets.appendVisible(new Object(), 1.0F));
    }

    @Test
    void replacementWithoutPreInvalidationIsRejected() {
        OpaqueSolidGpuSceneModel model =
            new OpaqueSolidGpuSceneModel(2, 2);
        Object section = new Object();
        model.publish(section, token(1, 10L, 0L));
        assertThrows(
            IllegalStateException.class,
            () -> model.publish(section, token(2, 10L, 0L))
        );
    }

    @Test
    void inactivePhysicalBucketIsReusedImmediately() {
        OpaqueSolidGpuSceneModel model =
            new OpaqueSolidGpuSceneModel(4, 2);
        Object first = new Object();
        model.publish(first, token(1, 10L, 0L));
        assertTrue(model.bucketActive(0));
        assertTrue(model.invalidateBeforeReplace(first));
        assertFalse(model.bucketActive(0));
        assertTrue(model.removeAfterInvalidation(first));

        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.PUBLISHED,
            model.publish(new Object(), token(2, 20L, 0L))
        );
        assertEquals(1, model.snapshot().buckets());
        assertTrue(model.bucketActive(0));
        assertEquals(20L, model.bucket(0).vertexBufferHandle());
    }

    @Test
    void failedDirtyWriteRetainsWholeSubmitRingTransaction() {
        OpaqueSolidGpuSceneModel model =
            new OpaqueSolidGpuSceneModel(4, 2);
        model.publish(new Object(), token(1, 10L, 0L));
        model.publish(new Object(), token(2, 10L, 0L));
        int[] attempted = {0};
        assertFalse(
            model.drainDirty(
                0,
                (slot, bucket, token) -> ++attempted[0] < 2
            )
        );
        assertEquals(2, model.snapshot().dirtyFrame0());
        int[] retried = {0};
        assertTrue(
            model.drainDirty(
                0,
                (slot, bucket, token) -> {
                    retried[0]++;
                    return true;
                }
            )
        );
        assertEquals(2, retried[0]);
        assertEquals(0, model.snapshot().dirtyFrame0());
    }

    @Test
    void lifecycleClearDirtiesBothCopiesAndResetsBuckets() {
        OpaqueSolidGpuSceneModel model =
            new OpaqueSolidGpuSceneModel(4, 2);
        Object first = new Object();
        Object second = new Object();
        model.publish(first, token(1, 10L, 0L));
        model.publish(second, token(2, 20L, 0L));
        assertEquals(2, model.snapshot().buckets());
        assertTrue(
            model.drainDirty(0, (slot, bucket, token) -> true)
        );
        assertTrue(
            model.drainDirty(1, (slot, bucket, token) -> true)
        );

        model.clearAfterOwnerInvalidation();

        assertEquals(0, model.snapshot().entries());
        assertEquals(0, model.snapshot().buckets());
        assertEquals(2, model.snapshot().dirtyFrame0());
        assertEquals(2, model.snapshot().dirtyFrame1());
        int[] cleared = {0};
        assertTrue(
            model.drainDirty(
                0,
                (slot, bucket, token) -> {
                    assertEquals(null, token);
                    cleared[0]++;
                    return true;
                }
            )
        );
        assertEquals(2, cleared[0]);
        assertEquals(
            OpaqueSolidGpuSceneModel.PublishResult.PUBLISHED,
            model.publish(new Object(), token(3, 30L, 0L))
        );
        assertEquals(1, model.snapshot().buckets());
    }

    private static OpaqueSolidGpuGenerationToken token(
        long sectionGeneration,
        long vertexHandle,
        long indexHandle
    ) {
        boolean custom = indexHandle != 0L;
        return new OpaqueSolidGpuGenerationToken(
            1,
            1,
            1,
            sectionGeneration,
            vertexHandle,
            sectionGeneration,
            custom ? indexHandle : 1,
            sectionGeneration,
            sectionGeneration,
            vertexHandle,
            64,
            128,
            indexHandle,
            custom ? 24 : 0,
            custom ? 72 : 0,
            custom
                ? OpaqueSolidGpuGenerationToken.INDEX_BINDING_CUSTOM
                : OpaqueSolidGpuGenerationToken
                    .INDEX_BINDING_SEQUENTIAL_QUAD,
            1,
            36,
            4,
            9,
            10,
            11
        );
    }
}
