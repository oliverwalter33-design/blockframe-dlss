package de.morau.blockframe.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vertex.StagingBuffer;
import java.nio.ByteBuffer;
import java.util.Objects;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Persistently mapped, fence-owned staging storage for terrain uploads.
 *
 * <p>Each instance owns three bounded GPU buffers through Minecraft's
 * {@link MappableRingBuffer}. Rotating records a fence for the submitted slot;
 * acquiring a slot waits only when that particular slot is reused. No device
 * idle operation is involved.</p>
 */
public final class BlockframeStagingBuffer extends StagingBuffer {
    private static final int BUFFER_USAGE = GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC;

    private final MappableRingBuffer ringBuffer;
    private final TripleUploadRingState state;
    private final Observer observer;
    private GpuBufferSlice.MappedView currentMappedView;
    private GpuBuffer currentGpuBuffer;
    private ByteBuffer currentBuffer;
    private boolean closed;

    private BlockframeStagingBuffer(String name, int requestedBufferSize, Observer observer) {
        int capacityPerSlot = TripleUploadRingState.capacityPerSlot(requestedBufferSize);
        this.observer = Objects.requireNonNull(observer, "observer");
        this.state = new TripleUploadRingState(capacityPerSlot);
        this.ringBuffer = new MappableRingBuffer(() -> name + " blockframe staging buffer", BUFFER_USAGE, capacityPerSlot);

        long acquireStarted = System.nanoTime();
        this.currentGpuBuffer = this.ringBuffer.currentBuffer();
        long acquireNanos = System.nanoTime() - acquireStarted;
        this.currentMappedView = this.currentGpuBuffer.map(false, true);
        this.currentBuffer = this.currentMappedView.data();
        this.observer.slotAcquired(this.state.currentSlot(), acquireNanos, false);
    }

    /**
     * Selects the managed path only when both the engine setting and the
     * backend capability allow persistent mapping. Every other case delegates
     * to Minecraft's factory unchanged.
     */
    public static StagingBuffer create(
        String name,
        GpuDevice gpuDevice,
        int requestedBufferSize,
        boolean managedFrameResourcesEnabled
    ) {
        return create(name, gpuDevice, requestedBufferSize, managedFrameResourcesEnabled, Observer.NONE);
    }

    /**
     * Variant with primitive telemetry callbacks. The observer must not retain
     * mapped buffers; it receives counters only.
     */
    public static StagingBuffer create(
        String name,
        GpuDevice gpuDevice,
        int requestedBufferSize,
        boolean managedFrameResourcesEnabled,
        Observer observer
    ) {
        boolean persistentMapping = gpuDevice.getDeviceInfo().features().persistentMapping();
        if (!TripleUploadRingState.shouldUseManagedPath(managedFrameResourcesEnabled, persistentMapping, requestedBufferSize)) {
            return StagingBuffer.create(name, gpuDevice, requestedBufferSize);
        }

        return new BlockframeStagingBuffer(name, requestedBufferSize, observer);
    }

    @Override
    public StagingBuffer.@Nullable BufferHandle tryAppend(ByteBuffer source) {
        this.ensureOpen();
        int byteCount = source.remaining();
        StagingBuffer.BufferHandle handle = super.tryAppend(source);
        if (handle != null) {
            this.state.recordAppend(byteCount);
            this.observer.bytesAppended(
                this.state.currentSlot(),
                byteCount,
                this.state.usedBytes(),
                this.state.capacityPerSlot(),
                this.state.peakUsedBytes()
            );
        }

        return handle;
    }

    @Override
    protected ByteBuffer getWriteBuffer() {
        this.ensureOpen();
        return this.currentBuffer;
    }

    @Override
    protected void copyTo(CommandEncoder encoder, GpuBuffer destination, long destinationOffset, long stagingOffset, long copySize) {
        this.ensureOpen();
        encoder.copyToBuffer(
            this.currentGpuBuffer.slice(stagingOffset, copySize),
            destination.slice(destinationOffset, copySize)
        );
        this.state.recordCopy(copySize);
        this.observer.bytesCopied(this.state.currentSlot(), copySize, this.state.copiedBytes());
    }

    @Override
    protected void rotateBuffer() {
        this.ensureOpen();
        int previousSlot = this.state.currentSlot();
        this.currentMappedView.close();
        this.ringBuffer.rotate();
        int nextSlot = this.state.rotate();
        this.observer.rotated(previousSlot, nextSlot, this.state.rotations());

        long acquireStarted = System.nanoTime();
        this.currentGpuBuffer = this.ringBuffer.currentBuffer();
        long acquireNanos = System.nanoTime() - acquireStarted;
        this.currentMappedView = this.currentGpuBuffer.map(false, true);
        this.currentBuffer = this.currentMappedView.data();
        this.observer.slotAcquired(nextSlot, acquireNanos, this.state.currentSlotIsBeingReused());
    }

    public int capacityPerSlot() {
        return this.state.capacityPerSlot();
    }

    public int currentSlot() {
        return this.state.currentSlot();
    }

    public long appendedBytes() {
        return this.state.appendedBytes();
    }

    public long copiedBytes() {
        return this.state.copiedBytes();
    }

    public long rotations() {
        return this.state.rotations();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;
        try {
            this.currentMappedView.close();
        } finally {
            this.ringBuffer.close();
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Blockframe staging buffer is closed");
        }
    }

    /**
     * Optional allocation-free telemetry bridge. Implementations should keep
     * each callback cheap because uploads occur on a render hot path.
     */
    public interface Observer {
        Observer NONE = new Observer() {
        };

        default void bytesAppended(int slot, long bytes, long usedBytes, long capacityBytes, long peakUsedBytes) {
        }

        default void bytesCopied(int slot, long bytes, long totalCopiedBytes) {
        }

        default void rotated(int previousSlot, int nextSlot, long totalRotations) {
        }

        default void slotAcquired(int slot, long acquireNanos, boolean reusedSlot) {
        }
    }
}
