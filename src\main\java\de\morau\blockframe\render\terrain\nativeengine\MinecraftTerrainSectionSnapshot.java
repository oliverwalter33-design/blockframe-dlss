package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.model.data.ModelData;
import org.jspecify.annotations.Nullable;

/**
 * Immutable one-block-halo source snapshot for the real model adapter.
 *
 * <p>Capture is restricted to the owning world thread. All later model,
 * culling, AO, light, biome-tint and ModelData reads are served from fixed
 * arrays. A query beyond the proven halo or for an unknown ColorResolver is
 * recorded and invalidates compilation instead of consulting the live
 * world.</p>
 */
public final class MinecraftTerrainSectionSnapshot
    implements BlockAndTintGetter, AutoCloseable {
    public enum FailureReason {
        WRONG_CAPTURE_THREAD,
        RAM_BUDGET_REJECTED,
        CAPTURE_FAILED,
        SIZE_OVERFLOW
    }

    public record CaptureResult(
        MinecraftTerrainSectionSnapshot snapshot,
        FailureReason failureReason,
        String detail
    ) {
        public CaptureResult {
            detail = Objects.requireNonNull(detail, "detail");
            if ((snapshot == null) == (failureReason == null)) {
                throw new IllegalArgumentException(
                    "capture must contain exactly one outcome"
                );
            }
        }

        public boolean successful() {
            return this.snapshot != null;
        }

        public Optional<MinecraftTerrainSectionSnapshot>
        snapshotOptional() {
            return Optional.ofNullable(this.snapshot);
        }
    }

    public static final int HALO = 1;
    public static final int CORE_SIZE = 16;
    public static final int SNAPSHOT_SIZE = CORE_SIZE + HALO * 2;
    public static final int CELL_COUNT =
        SNAPSHOT_SIZE * SNAPSHOT_SIZE * SNAPSHOT_SIZE;
    private static final int ESTIMATED_REFERENCE_BYTES = 8;
    private static final long FIXED_ARRAY_OVERHEAD_BYTES = 512L;

    private final GenerationStamp generations;
    private final SectionIdentity section;
    private final Digest censusDigest;
    private final int minimumX;
    private final int minimumY;
    private final int minimumZ;
    private final int levelMinimumY;
    private final int levelHeight;
    private final CardinalLighting cardinalLighting;
    private final BlockState[] blockStates;
    private final FluidState[] fluidStates;
    private final ModelData[] modelData;
    private final byte[] blockLight;
    private final byte[] skyLight;
    private final int[] grassTint;
    private final int[] foliageTint;
    private final int[] dryFoliageTint;
    private final int[] waterTint;
    private final NativeTerrainSnapshotPool storagePool;
    private NativeTerrainSnapshotPool.Storage pooledStorage;
    private final MemoryBudgetManager budgets;
    private final long ramLease;
    private final long estimatedBytes;
    private final long captureNanos;
    private volatile boolean unsupportedQuery;
    private volatile boolean closed;

    private MinecraftTerrainSectionSnapshot(
        GenerationStamp generations,
        SectionIdentity section,
        Digest censusDigest,
        int minimumX,
        int minimumY,
        int minimumZ,
        int levelMinimumY,
        int levelHeight,
        CardinalLighting cardinalLighting,
        BlockState[] blockStates,
        FluidState[] fluidStates,
        ModelData[] modelData,
        byte[] blockLight,
        byte[] skyLight,
        int[] grassTint,
        int[] foliageTint,
        int[] dryFoliageTint,
        int[] waterTint,
        NativeTerrainSnapshotPool storagePool,
        NativeTerrainSnapshotPool.Storage pooledStorage,
        MemoryBudgetManager budgets,
        long ramLease,
        long estimatedBytes,
        long captureNanos
    ) {
        this.generations = generations;
        this.section = section;
        this.censusDigest = censusDigest;
        this.minimumX = minimumX;
        this.minimumY = minimumY;
        this.minimumZ = minimumZ;
        this.levelMinimumY = levelMinimumY;
        this.levelHeight = levelHeight;
        this.cardinalLighting = cardinalLighting;
        this.blockStates = blockStates;
        this.fluidStates = fluidStates;
        this.modelData = modelData;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.grassTint = grassTint;
        this.foliageTint = foliageTint;
        this.dryFoliageTint = dryFoliageTint;
        this.waterTint = waterTint;
        this.storagePool = storagePool;
        this.pooledStorage = pooledStorage;
        this.budgets = budgets;
        this.ramLease = ramLease;
        this.estimatedBytes = estimatedBytes;
        this.captureNanos = captureNanos;
    }

    public static CaptureResult capture(
        RenderSectionRegion source,
        SectionPos sectionPosition,
        GenerationStamp generations,
        SectionIdentity section,
        Digest censusDigest,
        MemoryBudgetManager budgets,
        long maximumSnapshotBytes,
        boolean owningWorldThread
    ) {
        return capture(
            (BlockAndTintGetter)Objects.requireNonNull(source, "source"),
            sectionPosition,
            generations,
            section,
            censusDigest,
            budgets,
            maximumSnapshotBytes,
            owningWorldThread,
            null
        );
    }

    public static CaptureResult capture(
        RenderSectionRegion source,
        SectionPos sectionPosition,
        GenerationStamp generations,
        SectionIdentity section,
        Digest censusDigest,
        MemoryBudgetManager budgets,
        long maximumSnapshotBytes,
        boolean owningWorldThread,
        NativeTerrainSnapshotPool storagePool
    ) {
        return capture(
            (BlockAndTintGetter)Objects.requireNonNull(source, "source"),
            sectionPosition,
            generations,
            section,
            censusDigest,
            budgets,
            maximumSnapshotBytes,
            owningWorldThread,
            Objects.requireNonNull(storagePool, "storagePool")
        );
    }

    static CaptureResult capture(
        BlockAndTintGetter source,
        SectionPos sectionPosition,
        GenerationStamp generations,
        SectionIdentity section,
        Digest censusDigest,
        MemoryBudgetManager budgets,
        long maximumSnapshotBytes,
        boolean owningWorldThread
    ) {
        return capture(
            source,
            sectionPosition,
            generations,
            section,
            censusDigest,
            budgets,
            maximumSnapshotBytes,
            owningWorldThread,
            null
        );
    }

    static CaptureResult capture(
        BlockAndTintGetter source,
        SectionPos sectionPosition,
        GenerationStamp generations,
        SectionIdentity section,
        Digest censusDigest,
        MemoryBudgetManager budgets,
        long maximumSnapshotBytes,
        boolean owningWorldThread,
        NativeTerrainSnapshotPool storagePool
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sectionPosition, "sectionPosition");
        Objects.requireNonNull(generations, "generations");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(censusDigest, "censusDigest")
            .requireKnown("censusDigest");
        Objects.requireNonNull(budgets, "budgets");
        if (!owningWorldThread) {
            return failure(
                FailureReason.WRONG_CAPTURE_THREAD,
                "section snapshot capture is restricted to the world thread"
            );
        }
        if (maximumSnapshotBytes <= 0L) {
            throw new IllegalArgumentException(
                "maximumSnapshotBytes must be positive"
            );
        }

        long estimated;
        try {
            long references = Math.multiplyExact(
                (long)CELL_COUNT,
                3L * ESTIMATED_REFERENCE_BYTES
            );
            long primitiveArrays = Math.multiplyExact(
                (long)CELL_COUNT,
                2L + 4L * Integer.BYTES
            );
            estimated = Math.addExact(
                FIXED_ARRAY_OVERHEAD_BYTES,
                Math.addExact(references, primitiveArrays)
            );
        } catch (ArithmeticException error) {
            return failure(
                FailureReason.SIZE_OVERFLOW,
                "snapshot accounting overflow"
            );
        }
        if (estimated > maximumSnapshotBytes) {
            return failure(
                FailureReason.RAM_BUDGET_REJECTED,
                "snapshot exceeds configured per-section limit"
            );
        }
        long lease = budgets.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.TERRAIN,
            estimated
        );
        if (lease == 0L) {
            return failure(
                FailureReason.RAM_BUDGET_REJECTED,
                "RAM budget rejected immutable section snapshot"
            );
        }

        long started = System.nanoTime();
        NativeTerrainSnapshotPool.Storage storage = null;
        try {
            storage = storagePool == null
                ? new NativeTerrainSnapshotPool.Storage()
                : storagePool.acquire();
            BlockState[] states = storage.blockStates;
            FluidState[] fluids = storage.fluidStates;
            ModelData[] models = storage.modelData;
            byte[] block = storage.blockLight;
            byte[] sky = storage.skyLight;
            int[] grass = storage.grassTint;
            int[] foliage = storage.foliageTint;
            int[] dryFoliage = storage.dryFoliageTint;
            int[] water = storage.waterTint;
            int minimumX = sectionPosition.minBlockX() - HALO;
            int minimumY = sectionPosition.minBlockY() - HALO;
            int minimumZ = sectionPosition.minBlockZ() - HALO;
            BlockPos.MutableBlockPos position =
                new BlockPos.MutableBlockPos();
            int cursor = 0;
            for (int z = 0; z < SNAPSHOT_SIZE; z++) {
                for (int y = 0; y < SNAPSHOT_SIZE; y++) {
                    for (int x = 0; x < SNAPSHOT_SIZE; x++) {
                        position.set(
                            minimumX + x,
                            minimumY + y,
                            minimumZ + z
                        );
                        BlockState state =
                            source.getBlockState(position);
                        states[cursor] = state;
                        fluids[cursor] = source.getFluidState(position);
                        models[cursor] = source.getModelData(position);
                        block[cursor] = checkedLight(
                            source.getBrightness(
                                LightLayer.BLOCK,
                                position
                            )
                        );
                        sky[cursor] = checkedLight(
                            source.getBrightness(
                                LightLayer.SKY,
                                position
                            )
                        );
                        grass[cursor] = source.getBlockTint(
                            position,
                            BiomeColors.GRASS_COLOR_RESOLVER
                        );
                        foliage[cursor] = source.getBlockTint(
                            position,
                            BiomeColors.FOLIAGE_COLOR_RESOLVER
                        );
                        dryFoliage[cursor] = source.getBlockTint(
                            position,
                            BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER
                        );
                        water[cursor] = source.getBlockTint(
                            position,
                            BiomeColors.WATER_COLOR_RESOLVER
                        );
                        cursor++;
                    }
                }
            }
            return new CaptureResult(
                new MinecraftTerrainSectionSnapshot(
                    generations,
                    section,
                    censusDigest,
                    minimumX,
                    minimumY,
                    minimumZ,
                    source.getMinY(),
                    source.getHeight(),
                    source.cardinalLighting(),
                    states,
                    fluids,
                    models,
                    block,
                    sky,
                    grass,
                    foliage,
                    dryFoliage,
                    water,
                    storagePool,
                    storagePool == null ? null : storage,
                    budgets,
                    lease,
                    estimated,
                    System.nanoTime() - started
                ),
                null,
                ""
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            if (storagePool != null && storage != null) {
                storagePool.release(storage);
            }
            budgets.release(lease);
            return failure(
                FailureReason.CAPTURE_FAILED,
                error.getClass().getSimpleName()
            );
        }
    }

    public GenerationStamp generations() {
        requireOpen();
        return this.generations;
    }

    public SectionIdentity section() {
        requireOpen();
        return this.section;
    }

    public Digest censusDigest() {
        requireOpen();
        return this.censusDigest;
    }

    public long estimatedBytes() {
        return this.estimatedBytes;
    }

    public long captureNanos() {
        return this.captureNanos;
    }

    public boolean validFor(GenerationStamp current) {
        return !this.closed
            && !this.unsupportedQuery
            && this.generations.equals(
                Objects.requireNonNull(current, "current")
            );
    }

    public boolean unsupportedQueryObserved() {
        return this.unsupportedQuery;
    }

    public int coreMinimumX() {
        return this.minimumX + HALO;
    }

    public int coreMinimumY() {
        return this.minimumY + HALO;
    }

    public int coreMinimumZ() {
        return this.minimumZ + HALO;
    }

    @Override
    public BlockState getBlockState(BlockPos position) {
        int index = index(position);
        if (index < 0) {
            this.unsupportedQuery = true;
            return Blocks.AIR.defaultBlockState();
        }
        return this.blockStates[index];
    }

    @Override
    public FluidState getFluidState(BlockPos position) {
        int index = index(position);
        if (index < 0) {
            this.unsupportedQuery = true;
            return Blocks.AIR.defaultBlockState().getFluidState();
        }
        return this.fluidStates[index];
    }

    @Override
    public ModelData getModelData(BlockPos position) {
        int index = index(position);
        if (index < 0) {
            this.unsupportedQuery = true;
            return ModelData.EMPTY;
        }
        return this.modelData[index];
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos position) {
        int index = index(position);
        if (index < 0) {
            this.unsupportedQuery = true;
            return 0;
        }
        return Byte.toUnsignedInt(
            layer == LightLayer.SKY
                ? this.skyLight[index]
                : this.blockLight[index]
        );
    }

    @Override
    public int getRawBrightness(
        BlockPos position,
        int skyDarkening
    ) {
        return Math.max(
            this.getBrightness(LightLayer.BLOCK, position),
            this.getBrightness(LightLayer.SKY, position)
                - skyDarkening
        );
    }

    @Override
    public int getBlockTint(
        BlockPos position,
        ColorResolver resolver
    ) {
        int index = index(position);
        if (index < 0) {
            this.unsupportedQuery = true;
            return -1;
        }
        if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
            return this.grassTint[index];
        }
        if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
            return this.foliageTint[index];
        }
        if (resolver == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) {
            return this.dryFoliageTint[index];
        }
        if (resolver == BiomeColors.WATER_COLOR_RESOLVER) {
            return this.waterTint[index];
        }
        this.unsupportedQuery = true;
        return -1;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return this.cardinalLighting;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        /*
         * ModelBlockRenderer and LightCoordsUtil dispatch through the
         * overridden brightness methods. Returning EMPTY prevents a hidden
         * connection to the live ClientLevel.
         */
        return LevelLightEngine.EMPTY;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos position) {
        /*
         * Foundation B deliberately snapshots no mutable BlockEntity.
         * A model that asks for one therefore exceeds this immutable input
         * contract and must be rejected after tessellation rather than being
         * silently compiled as though the BlockEntity did not exist.
         */
        this.unsupportedQuery = true;
        return null;
    }

    @Override
    public int getHeight() {
        return this.levelHeight;
    }

    @Override
    public int getMinY() {
        return this.levelMinimumY;
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        if (!this.budgets.release(this.ramLease)) {
            throw new IllegalStateException(
                "snapshot RAM lease could not be released"
            );
        }
        NativeTerrainSnapshotPool.Storage storage =
            this.pooledStorage;
        if (storage != null) {
            this.storagePool.release(storage);
            this.pooledStorage = null;
        }
        this.closed = true;
    }

    private int index(BlockPos position) {
        requireOpen();
        int x = position.getX() - this.minimumX;
        int y = position.getY() - this.minimumY;
        int z = position.getZ() - this.minimumZ;
        if (
            x < 0
                || x >= SNAPSHOT_SIZE
                || y < 0
                || y >= SNAPSHOT_SIZE
                || z < 0
                || z >= SNAPSHOT_SIZE
        ) {
            return -1;
        }
        return x + y * SNAPSHOT_SIZE
            + z * SNAPSHOT_SIZE * SNAPSHOT_SIZE;
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                "section snapshot is closed"
            );
        }
    }

    private static byte checkedLight(int light) {
        if (light < 0 || light > 15) {
            throw new IllegalArgumentException(
                "light value is outside [0,15]"
            );
        }
        return (byte)light;
    }

    private static CaptureResult failure(
        FailureReason reason,
        String detail
    ) {
        return new CaptureResult(null, reason, detail);
    }
}
