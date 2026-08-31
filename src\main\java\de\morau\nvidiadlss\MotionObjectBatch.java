package de.morau.nvidiadlss;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.memory.ReusablePrimitiveArena;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Fixed-capacity primitive-backed batch for the fourteen existing
 * motion-object fields.
 *
 * <p>After successful construction, successful clear, add and packed-write
 * operations reuse stable storage without lists or per-object carrier
 * allocations. Values are retained as doubles and encoded as five packed
 * vec4s.</p>
 */
public final class MotionObjectBatch implements AutoCloseable {
    public static final int FIELD_COUNT = 14;
    public static final int PACKED_FLOAT_COUNT = 20;
    public static final int PACKED_BYTES = PACKED_FLOAT_COUNT * Float.BYTES;

    private static final int MIN_X = 0;
    private static final int MIN_Y = 1;
    private static final int MIN_Z = 2;
    private static final int MAX_X = 3;
    private static final int MAX_Y = 4;
    private static final int MAX_Z = 5;
    private static final int PREVIOUS_X = 6;
    private static final int PREVIOUS_Y = 7;
    private static final int PREVIOUS_Z = 8;
    private static final int CURRENT_X = 9;
    private static final int CURRENT_Y = 10;
    private static final int CURRENT_Z = 11;
    private static final int CURRENT_YAW = 12;
    private static final int PREVIOUS_YAW = 13;

    private final ReusablePrimitiveArena arena;
    private final int capacity;
    private final int doubleOffset;
    private int size;

    private MotionObjectBatch(
        ReusablePrimitiveArena arena,
        int capacity,
        int doubleOffset
    ) {
        this.arena = arena;
        this.capacity = capacity;
        this.doubleOffset = doubleOffset;
    }

    /**
     * Creates an entity-RAM-budgeted batch, or returns {@code null} when its
     * complete fixed arena cannot be reserved or allocated.
     */
    public static MotionObjectBatch tryCreate(
        MemoryBudgetManager budgets,
        int capacity
    ) {
        Objects.requireNonNull(budgets, "budgets");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        int doubleCount = Math.multiplyExact(capacity, FIELD_COUNT);
        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.ENTITIES,
            new ReusablePrimitiveArena.Layout(0, 0, 0, doubleCount, 0)
        );
        if (arena == null) {
            return null;
        }

        try {
            int offset = arena.claimDoubles(doubleCount, Double.BYTES);
            if (offset < 0) {
                throw new IllegalStateException(
                    "motion batch arena does not cover its declared layout"
                );
            }
            return new MotionObjectBatch(arena, capacity, offset);
        } catch (OutOfMemoryError allocationFailure) {
            arena.close();
            return null;
        } catch (RuntimeException | Error creationFailure) {
            arena.close();
            throw creationFailure;
        }
    }

    public boolean add(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        double previousX,
        double previousY,
        double previousZ,
        double currentX,
        double currentY,
        double currentZ,
        float currentYaw,
        float previousYaw
    ) {
        double[] values = this.arena.doubles();
        if (this.size == this.capacity) {
            return false;
        }

        int base = this.doubleOffset + this.size * FIELD_COUNT;
        values[base + MIN_X] = minX;
        values[base + MIN_Y] = minY;
        values[base + MIN_Z] = minZ;
        values[base + MAX_X] = maxX;
        values[base + MAX_Y] = maxY;
        values[base + MAX_Z] = maxZ;
        values[base + PREVIOUS_X] = previousX;
        values[base + PREVIOUS_Y] = previousY;
        values[base + PREVIOUS_Z] = previousZ;
        values[base + CURRENT_X] = currentX;
        values[base + CURRENT_Y] = currentY;
        values[base + CURRENT_Z] = currentZ;
        values[base + CURRENT_YAW] = currentYaw;
        values[base + PREVIOUS_YAW] = previousYaw;
        this.size++;
        return true;
    }

    public void writeObject(int index, ByteBuffer target) {
        double[] values = this.arena.doubles();
        Objects.requireNonNull(target, "target");
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException(
                "motion object index out of range: " + index
            );
        }
        if (target.remaining() < PACKED_BYTES) {
            throw new BufferOverflowException();
        }

        int base = this.doubleOffset + index * FIELD_COUNT;
        target.putFloat((float)values[base + MIN_X]);
        target.putFloat((float)values[base + MIN_Y]);
        target.putFloat((float)values[base + MIN_Z]);
        target.putFloat(0.0F);
        target.putFloat((float)values[base + MAX_X]);
        target.putFloat((float)values[base + MAX_Y]);
        target.putFloat((float)values[base + MAX_Z]);
        target.putFloat(0.0F);
        target.putFloat((float)values[base + PREVIOUS_X]);
        target.putFloat((float)values[base + PREVIOUS_Y]);
        target.putFloat((float)values[base + PREVIOUS_Z]);
        target.putFloat(0.0F);
        target.putFloat((float)values[base + CURRENT_X]);
        target.putFloat((float)values[base + CURRENT_Y]);
        target.putFloat((float)values[base + CURRENT_Z]);
        target.putFloat(0.0F);
        target.putFloat((float)values[base + CURRENT_YAW]);
        target.putFloat((float)values[base + PREVIOUS_YAW]);
        target.putFloat(0.0F);
        target.putFloat(0.0F);
    }

    public void clear() {
        this.arena.doubles();
        this.size = 0;
    }

    public int size() {
        this.arena.doubles();
        return this.size;
    }

    public int capacity() {
        this.arena.doubles();
        return this.capacity;
    }

    @Override
    public void close() {
        this.arena.close();
        this.size = 0;
    }
}
