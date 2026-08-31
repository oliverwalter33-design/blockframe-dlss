package de.morau.blockframe.profiler;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.diagnostics.GpuPassDiagnostics;
import de.morau.blockframe.core.diagnostics.GpuPassIdentity;
import de.morau.blockframe.core.memory.BudgetedNativeArena;
import de.morau.nvidiadlss.mixin.GpuDeviceAccessor;
import de.morau.nvidiadlss.mixin.VulkanQueryPoolAccessor;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/**
 * Asynchronous Vulkan frame timestamps with a fixed query ring.
 *
 * <p>The render path never waits for a query. A slot that is still owned by
 * the GPU is skipped and retried on a later frame. Query results use one
 * preallocated native buffer, avoiding {@code OptionalLong[]} allocations in
 * the public query-pool read helper.</p>
 */
public final class VulkanGpuFrameTimer implements AutoCloseable {
    private static final int SLOT_COUNT = 4;
    private static final int QUERIES_PER_SLOT = 2;
    private static final int QUERY_COUNT = SLOT_COUNT * QUERIES_PER_SLOT;
    static final int MAX_UNAVAILABLE_POLLS = 240;
    static final int QUERY_RESULT_SUCCESS = VK12.VK_SUCCESS;
    static final int QUERY_RESULT_NOT_READY = VK12.VK_NOT_READY;
    private static final long RESULT_STRIDE_BYTES = 2L * Long.BYTES;
    private static final int RESULT_FLAGS =
        VK12.VK_QUERY_RESULT_64_BIT | VK12.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT;
    static final long QUERY_RESULT_REQUESTED_BYTES = 4L * Long.BYTES;
    static final long QUERY_RESULT_ALLOCATION_ALIGNMENT = 64L;
    static final long QUERY_RESULT_COMMITTED_BYTES = 64L;

    private final FrameProfiler profiler;
    private final MemoryBudgetManager budgets;
    private final RejectedDeviceGate<GpuDevice> rejectedDevices =
        new RejectedDeviceGate<>();
    private final QueryRingState ringState = new QueryRingState(SLOT_COUNT);

    private @Nullable BudgetedNativeArena queryResultsArena;
    private @Nullable LongBuffer queryResults;
    private @Nullable GpuDevice gpuDevice;
    private @Nullable VulkanDevice device;
    private @Nullable GpuQueryPool queryPool;
    private @Nullable CommandEncoder encoder;
    private long queryPoolHandle;
    private float timestampPeriod;
    private int timestampValidBits;
    private boolean closing;
    private boolean closed;
    private volatile String status = "unavailable: Vulkan device not active";

    public VulkanGpuFrameTimer(
        FrameProfiler profiler,
        MemoryBudgetManager budgets
    ) {
        this.profiler = Objects.requireNonNull(profiler, "profiler");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
    }

    public void beginFrame(@Nullable GpuDevice currentDevice) {
        if (this.closed || this.closing) {
            return;
        }

        if (this.ringState.hasActiveFrame()) {
            this.abortFrame();
        }
        this.rejectedDevices.observe(currentDevice);
        if (this.rejectedDevices.isRejected(currentDevice)) {
            return;
        }

        GpuDeviceBackend backend;
        try {
            backend = currentDevice == null
                ? null
                : ((GpuDeviceAccessor)(Object)currentDevice).blockframe$backend();
        } catch (RuntimeException | LinkageError error) {
            if (currentDevice != null) {
                this.rejectDevice(
                    currentDevice,
                    "unavailable: device setup " + error.getClass().getSimpleName()
                );
            } else {
                this.status =
                    "unavailable: device setup "
                        + error.getClass().getSimpleName();
            }
            return;
        }
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            if (this.gpuDevice != null || this.device != null) {
                this.releaseDeviceResources();
            }
            this.status = "unavailable: Vulkan only";
            return;
        }
        if (
            currentDevice != this.gpuDevice
                || vulkanDevice != this.device
        ) {
            if (!this.configure(currentDevice, vulkanDevice)) {
                return;
            }
        }

        int slot = this.ringState.nextSlot();
        if (this.ringState.isPending(slot) && !this.resolve(slot)) {
            return;
        }

        try {
            this.encoder.writeTimestamp(
                this.queryPool,
                slot * QUERIES_PER_SLOT
            );
            this.ringState.begin(slot);
        } catch (RuntimeException | LinkageError error) {
            this.disableCurrentDevice(
                "unavailable: begin timestamp "
                    + error.getClass().getSimpleName()
            );
        }
    }

    public void endFrame() {
        if (this.closed || this.closing) {
            return;
        }
        int slot = this.ringState.finishActive();
        if (slot < 0) {
            return;
        }
        if (this.encoder == null || this.queryPool == null) {
            this.disableCurrentDevice(
                "unavailable: incomplete timestamp timer state"
            );
            return;
        }
        try {
            this.encoder.writeTimestamp(
                this.queryPool,
                slot * QUERIES_PER_SLOT + 1
            );
        } catch (RuntimeException | LinkageError error) {
            this.disableCurrentDevice(
                "unavailable: end timestamp "
                    + error.getClass().getSimpleName()
            );
        }
    }

    /**
     * Abandons a frame whose end timestamp could not be recorded.
     *
     * <p>The half-written pool is queued for destruction instead of reusing a
     * query that may still be referenced by submitted GPU work. This is a
     * transient frame reset, so the same healthy device may create a fresh pool
     * on the next frame.</p>
     */
    public void abortFrame() {
        if (
            this.closed
                || this.closing
                || !this.ringState.abortActive()
        ) {
            return;
        }
        this.resetCurrentDevice("reset: GPU frame aborted");
    }

    /**
     * Releases timer resources before Mojang destroys the Vulkan device.
     *
     * <p>This must be called before {@link VulkanDevice#close()} so
     * {@link GpuQueryPool#close()} can enqueue destruction on the live device
     * encoder.</p>
     */
    public void deviceClosing(VulkanDevice closingDevice) {
        if (
            this.closed
                || this.closing
                || closingDevice != this.device
        ) {
            return;
        }
        GpuDevice closingGpuDevice = this.gpuDevice;
        if (closingGpuDevice != null) {
            this.rejectedDevices.reject(closingGpuDevice);
        }
        this.releaseDeviceResources();
        this.status = "unavailable: Vulkan device closing";
    }

    /**
     * Allows an explicitly reloaded configuration to retry a device whose
     * optional timer setup was rejected.
     *
     * <p>The fixed native result scratch is client-scoped and deliberately
     * survives both this gate reset and Vulkan device recreation.</p>
     */
    public void configurationReloaded() {
        if (this.closed || this.closing) {
            return;
        }
        if (this.rejectedDevices.clear()) {
            this.status = "unavailable: awaiting Vulkan reconfiguration";
        }
    }

    public String status() {
        return this.status;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closing = true;
        this.releaseDeviceResources();

        BudgetedNativeArena arena = this.queryResultsArena;
        if (arena != null) {
            // Never retain a potentially invalid view after physical close.
            this.queryResults = null;
            arena.close();
            this.queryResultsArena = null;
        }

        this.rejectedDevices.clear();
        this.closed = true;
        this.closing = false;
        this.status = "closed";
    }

    private boolean configure(
        GpuDevice gpuDevice,
        VulkanDevice vulkanDevice
    ) {
        this.releaseDeviceResources();
        this.gpuDevice = gpuDevice;
        this.device = vulkanDevice;

        try {
            float period = vulkanDevice.getDeviceInfo().timestampPeriod();
            if (!Float.isFinite(period) || period <= 0.0F) {
                this.disableCurrentDevice(
                    "unavailable: invalid Vulkan timestamp period"
                );
                return false;
            }
            int validBits = queryTimestampValidBits(vulkanDevice);
            if (validBits <= 0) {
                this.disableCurrentDevice(
                    "unavailable: graphics queue has no timestamp bits"
                );
                return false;
            }
            if (this.ensureQueryResultsScratch() == null) {
                this.disableCurrentDevice(
                    "unavailable: timestamp scratch budget/allocation"
                );
                return false;
            }

            GpuQueryPool pool = vulkanDevice.createTimestampQueryPool(QUERY_COUNT);
            this.queryPool = pool;
            this.encoder = gpuDevice.createCommandEncoder();
            this.queryPoolHandle = ((VulkanQueryPoolAccessor)pool).blockframe$queryPoolHandle();
            GpuPassDiagnostics.labelFrameTimestampQueryPool(
                vulkanDevice,
                this.queryPoolHandle
            );
            this.timestampPeriod = period;
            this.timestampValidBits = validBits;
            this.status =
                "active: asynchronous Vulkan timestamps: "
                    + GpuPassIdentity.FRAME.label();
            return true;
        } catch (OutOfMemoryError error) {
            this.disableCurrentDevice(
                "unavailable: timestamp setup "
                    + error.getClass().getSimpleName()
            );
            return false;
        } catch (RuntimeException | LinkageError error) {
            this.disableCurrentDevice(
                "unavailable: device setup " + error.getClass().getSimpleName()
            );
            return false;
        }
    }

    private boolean resolve(int slot) {
        LongBuffer results = this.queryResults;
        VulkanDevice currentDevice = this.device;
        if (results == null || currentDevice == null) {
            this.disableCurrentDevice(
                "unavailable: incomplete timestamp timer state"
            );
            return false;
        }

        results.clear();
        for (int i = 0; i < results.capacity(); i++) {
            results.put(i, 0L);
        }
        int firstQuery = slot * QUERIES_PER_SLOT;
        int result;
        try {
            result = VK12.vkGetQueryPoolResults(
                currentDevice.vkDevice(),
                this.queryPoolHandle,
                firstQuery,
                QUERIES_PER_SLOT,
                results,
                RESULT_STRIDE_BYTES,
                RESULT_FLAGS
            );
        } catch (RuntimeException | LinkageError error) {
            this.disableCurrentDevice(
                "unavailable: query read " + error.getClass().getSimpleName()
            );
            return false;
        }

        boolean complete =
            result == QUERY_RESULT_SUCCESS
                && results.get(1) != 0L
                && results.get(3) != 0L;
        int unavailablePolls = complete
            ? 0
            : this.ringState.noteUnavailable(slot);
        QueryPollDecision decision = queryPollDecision(
            result,
            complete,
            unavailablePolls
        );
        if (decision == QueryPollDecision.RETRY) {
            return false;
        }
        if (decision == QueryPollDecision.DISABLE) {
            String failure = result != QUERY_RESULT_SUCCESS
                    && result != QUERY_RESULT_NOT_READY
                ? "unavailable: vkGetQueryPoolResults=" + result
                : "unavailable: timestamp query stalled after "
                    + unavailablePolls
                    + " polls";
            this.disableCurrentDevice(failure);
            return false;
        }

        long start = results.get(0);
        long end = results.get(2);
        long ticks = timestampDelta(start, end, this.timestampValidBits);
        long nanos = Math.max(0L, Math.round(ticks * (double)this.timestampPeriod));
        this.profiler.recordGpuFrame(nanos);
        this.ringState.resolve(slot);
        return true;
    }

    /**
     * Lazily creates the sole native timestamp-result view.
     *
     * <p>This method is called only after a Vulkan backend and timestamp
     * support have been established. The arena is assigned before the view is
     * initialized so an unexpected view-construction failure still leaves a
     * retained owner that {@link #close()} can retry.</p>
     */
    @Nullable LongBuffer ensureQueryResultsScratch() {
        if (this.closed || this.closing) {
            return null;
        }
        LongBuffer results = this.queryResults;
        if (results != null) {
            return results;
        }
        if (this.queryResultsArena != null) {
            return null;
        }

        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            this.budgets,
            MemoryCategory.STAGING,
            new BudgetedNativeArena.Layout(
                QUERY_RESULT_REQUESTED_BYTES,
                QUERY_RESULT_ALLOCATION_ALIGNMENT
            )
        );
        if (arena == null) {
            return null;
        }
        this.queryResultsArena = arena;

        MemorySegment storage = arena.claim(
            QUERY_RESULT_REQUESTED_BYTES,
            Long.BYTES
        );
        if (storage == null) {
            throw new IllegalStateException(
                "timestamp result arena did not provide its fixed layout"
            );
        }
        results = storage
            .asByteBuffer()
            .order(ByteOrder.nativeOrder())
            .asLongBuffer();
        if (results.capacity() != 4) {
            throw new IllegalStateException(
                "timestamp result view has unexpected capacity"
            );
        }
        this.queryResults = results;
        return results;
    }

    private void releaseDeviceResources() {
        this.ringState.reset();
        GpuQueryPool pool = this.queryPool;
        this.queryPool = null;
        this.encoder = null;
        this.queryPoolHandle = 0L;
        this.timestampPeriod = 0.0F;
        this.timestampValidBits = 0;
        this.device = null;
        this.gpuDevice = null;
        if (pool != null) {
            try {
                pool.close();
            } catch (RuntimeException | LinkageError ignored) {
                // Device teardown may already own destruction.
            }
        }
    }

    private void rejectDevice(GpuDevice rejectedDevice, String failure) {
        this.rejectedDevices.reject(rejectedDevice);
        this.releaseDeviceResources();
        this.status = failure;
    }

    private void disableCurrentDevice(String failure) {
        GpuDevice currentGpuDevice = this.gpuDevice;
        if (currentGpuDevice != null) {
            this.rejectedDevices.reject(currentGpuDevice);
        }
        this.releaseDeviceResources();
        this.status = failure;
    }

    private void resetCurrentDevice(String reason) {
        this.releaseDeviceResources();
        this.status = reason;
    }

    static QueryPollDecision queryPollDecision(
        int result,
        boolean complete,
        int unavailablePolls
    ) {
        if (
            result != QUERY_RESULT_SUCCESS
                && result != QUERY_RESULT_NOT_READY
        ) {
            return QueryPollDecision.DISABLE;
        }
        if (result == QUERY_RESULT_SUCCESS && complete) {
            return QueryPollDecision.READY;
        }
        return unavailablePolls >= MAX_UNAVAILABLE_POLLS
            ? QueryPollDecision.DISABLE
            : QueryPollDecision.RETRY;
    }

    static long timestampDelta(long start, long end, int validBits) {
        if (validBits <= 0 || validBits > Long.SIZE) {
            throw new IllegalArgumentException("validBits must be in [1, 64]");
        }
        if (validBits == Long.SIZE) {
            return end - start;
        }
        long mask = (1L << validBits) - 1L;
        return (end - start) & mask;
    }

    private static int queryTimestampValidBits(VulkanDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(
                device.vkDevice().getPhysicalDevice(),
                count,
                null
            );
            VkQueueFamilyProperties.Buffer properties =
                VkQueueFamilyProperties.calloc(count.get(0), stack);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(
                device.vkDevice().getPhysicalDevice(),
                count,
                properties
            );
            int family = device.graphicsQueue().queueFamilyIndex();
            return family >= 0 && family < properties.capacity()
                ? properties.get(family).timestampValidBits()
                : 0;
        }
    }

    enum QueryPollDecision {
        READY,
        RETRY,
        DISABLE
    }

    static final class RejectedDeviceGate<T> {
        private @Nullable T rejected;

        void observe(@Nullable T current) {
            if (current != this.rejected) {
                this.rejected = null;
            }
        }

        boolean isRejected(@Nullable T candidate) {
            return candidate != null && candidate == this.rejected;
        }

        void reject(T candidate) {
            this.rejected = candidate;
        }

        boolean clear() {
            boolean changed = this.rejected != null;
            this.rejected = null;
            return changed;
        }
    }

    static final class QueryRingState {
        private final boolean[] pending;
        private final int[] unavailablePolls;
        private int nextSlot;
        private int activeSlot = -1;

        QueryRingState(int slotCount) {
            if (slotCount < 1) {
                throw new IllegalArgumentException(
                    "slotCount must be at least one"
                );
            }
            this.pending = new boolean[slotCount];
            this.unavailablePolls = new int[slotCount];
        }

        int nextSlot() {
            return this.nextSlot;
        }

        boolean isPending(int slot) {
            return this.pending[slot];
        }

        boolean hasActiveFrame() {
            return this.activeSlot >= 0;
        }

        void begin(int slot) {
            if (this.activeSlot >= 0) {
                throw new IllegalStateException(
                    "a GPU timestamp frame is already active"
                );
            }
            if (this.pending[slot]) {
                throw new IllegalStateException(
                    "cannot reuse a pending timestamp slot"
                );
            }
            this.pending[slot] = true;
            this.unavailablePolls[slot] = 0;
            this.activeSlot = slot;
            this.nextSlot = (slot + 1) % this.pending.length;
        }

        int finishActive() {
            int slot = this.activeSlot;
            this.activeSlot = -1;
            return slot;
        }

        boolean abortActive() {
            if (this.activeSlot < 0) {
                return false;
            }
            this.activeSlot = -1;
            return true;
        }

        int noteUnavailable(int slot) {
            int polls = this.unavailablePolls[slot];
            if (polls < Integer.MAX_VALUE) {
                polls++;
                this.unavailablePolls[slot] = polls;
            }
            return polls;
        }

        void resolve(int slot) {
            this.pending[slot] = false;
            this.unavailablePolls[slot] = 0;
        }

        void reset() {
            this.nextSlot = 0;
            this.activeSlot = -1;
            for (int i = 0; i < this.pending.length; i++) {
                this.pending[i] = false;
                this.unavailablePolls[i] = 0;
            }
        }
    }
}
