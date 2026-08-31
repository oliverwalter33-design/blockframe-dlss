package de.morau.nvidiadlss;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.budget.MemoryBudgetExceededException;
import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;

/**
 * Atomically publishable DLSS auxiliary image set. Construction either owns
 * every image/view and one VRAM lease or rolls all of them back.
 */
final class DlssAuxiliaryResources {
    final GpuTexture motionTexture;
    final GpuTextureView motionView;
    final GpuTexture historyBiasTexture;
    final GpuTextureView historyBiasView;
    final GpuTexture depthDebugTexture;
    final GpuTextureView depthDebugView;
    final GpuTexture motionDebugTexture;
    final GpuTextureView motionDebugView;
    final GpuTexture motionValidityTexture;
    final GpuTextureView motionValidityView;
    final GpuTexture transparencyHintTexture;
    final GpuTextureView transparencyHintView;
    final GpuTexture outputTexture;
    final GpuTextureView outputView;
    final GpuTexture sharpenTexture;
    final GpuTextureView sharpenView;
    final DlssResourceFootprint footprint;

    private final MemoryBudgetManager budgets;
    private long budgetLease;
    private boolean closed;
    private boolean cleanupConfirmed;
    private boolean leaseRetained;

    private DlssAuxiliaryResources(
        GpuTexture motionTexture,
        GpuTextureView motionView,
        GpuTexture historyBiasTexture,
        GpuTextureView historyBiasView,
        GpuTexture depthDebugTexture,
        GpuTextureView depthDebugView,
        GpuTexture motionDebugTexture,
        GpuTextureView motionDebugView,
        GpuTexture motionValidityTexture,
        GpuTextureView motionValidityView,
        GpuTexture transparencyHintTexture,
        GpuTextureView transparencyHintView,
        GpuTexture outputTexture,
        GpuTextureView outputView,
        GpuTexture sharpenTexture,
        GpuTextureView sharpenView,
        DlssResourceFootprint footprint,
        MemoryBudgetManager budgets,
        long budgetLease
    ) {
        this.motionTexture = motionTexture;
        this.motionView = motionView;
        this.historyBiasTexture = historyBiasTexture;
        this.historyBiasView = historyBiasView;
        this.depthDebugTexture = depthDebugTexture;
        this.depthDebugView = depthDebugView;
        this.motionDebugTexture = motionDebugTexture;
        this.motionDebugView = motionDebugView;
        this.motionValidityTexture = motionValidityTexture;
        this.motionValidityView = motionValidityView;
        this.transparencyHintTexture = transparencyHintTexture;
        this.transparencyHintView = transparencyHintView;
        this.outputTexture = outputTexture;
        this.outputView = outputView;
        this.sharpenTexture = sharpenTexture;
        this.sharpenView = sharpenView;
        this.footprint = footprint;
        this.budgets = budgets;
        this.budgetLease = budgetLease;
    }

    static DlssAuxiliaryResources create(
        int lowWidth,
        int lowHeight,
        int outputWidth,
        int outputHeight
    ) {
        boolean developerDiagnostics = DeveloperDiagnostics.enabled();
        DlssResourceFootprint footprint = DlssResourceFootprint.forDimensions(
            lowWidth,
            lowHeight,
            outputWidth,
            outputHeight,
            developerDiagnostics
        );
        MemoryBudgetManager budgets = BlockframeRuntime.memoryBudgets();
        long lease = budgets.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.SHADER_RESOURCES,
            footprint.requestedBytes(),
            footprint.committedBytes(),
            null
        );
        if (lease == 0L) {
            throw new MemoryBudgetExceededException(
                "DLSS resource set rejected by shader VRAM budget (requested="
                    + footprint.requestedBytes()
                    + ", committed="
                    + footprint.committedBytes()
                    + ")"
            );
        }

        ResourceTransaction transaction;
        try {
            transaction = new ResourceTransaction(
                developerDiagnostics ? 16 : 10
            );
        } catch (Throwable error) {
            if (!budgets.release(lease)) {
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS-Lease konnte nach fehlgeschlagenem Transaktionsaufbau nicht freigegeben werden"
                );
            }
            throw error;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            GpuTexture motionTexture = transaction.own(
                device.createTexture(
                    "NVIDIA DLSS / Motion Vectors",
                    15 | DlssRenderer.STORAGE_USAGE,
                    GpuFormat.RG16_FLOAT,
                    lowWidth,
                    lowHeight,
                    1,
                    1
                )
            );
            GpuTextureView motionView = transaction.own(
                device.createTextureView(motionTexture)
            );
            GpuTexture historyBiasTexture = transaction.own(
                device.createTexture(
                    "NVIDIA DLSS / Local Player History Bias",
                    15 | DlssRenderer.STORAGE_USAGE,
                    GpuFormat.RGBA8_UNORM,
                    lowWidth,
                    lowHeight,
                    1,
                    1
                )
            );
            GpuTextureView historyBiasView = transaction.own(
                device.createTextureView(historyBiasTexture)
            );
            GpuTexture depthDebugTexture = null;
            GpuTextureView depthDebugView = null;
            GpuTexture motionDebugTexture = null;
            GpuTextureView motionDebugView = null;
            GpuTexture motionValidityTexture = null;
            GpuTextureView motionValidityView = null;
            if (developerDiagnostics) {
                depthDebugTexture = transaction.own(
                    device.createTexture(
                        "NVIDIA DLSS / Depth Debug",
                        15 | DlssRenderer.STORAGE_USAGE,
                        GpuFormat.RGBA8_UNORM,
                        lowWidth,
                        lowHeight,
                        1,
                        1
                    )
                );
                depthDebugView = transaction.own(
                    device.createTextureView(depthDebugTexture)
                );
                motionDebugTexture = transaction.own(
                    device.createTexture(
                        "NVIDIA DLSS / Motion Debug",
                        15 | DlssRenderer.STORAGE_USAGE,
                        GpuFormat.RGBA8_UNORM,
                        lowWidth,
                        lowHeight,
                        1,
                        1
                    )
                );
                motionDebugView = transaction.own(
                    device.createTextureView(motionDebugTexture)
                );
                motionValidityTexture = transaction.own(
                    device.createTexture(
                        "NVIDIA DLSS / Motion Validity R8_UINT",
                        15 | DlssRenderer.STORAGE_USAGE,
                        GpuFormat.R8_UINT,
                        lowWidth,
                        lowHeight,
                        1,
                        1
                    )
                );
                motionValidityView = transaction.own(
                    device.createTextureView(motionValidityTexture)
                );
            }
            GpuTexture transparencyHintTexture = transaction.own(
                device.createTexture(
                    "NVIDIA DLSS / Transparency Hint",
                    15 | DlssRenderer.STORAGE_USAGE,
                    GpuFormat.RGBA8_UNORM,
                    lowWidth,
                    lowHeight,
                    1,
                    1
                )
            );
            GpuTextureView transparencyHintView = transaction.own(
                device.createTextureView(transparencyHintTexture)
            );
            GpuTexture outputTexture = transaction.own(
                device.createTexture(
                    "NVIDIA DLSS / Output",
                    15 | DlssRenderer.STORAGE_USAGE,
                    GpuFormat.RGBA8_UNORM,
                    outputWidth,
                    outputHeight,
                    1,
                    1
                )
            );
            GpuTextureView outputView = transaction.own(
                device.createTextureView(outputTexture)
            );
            GpuTexture sharpenTexture = transaction.own(
                device.createTexture(
                    "NVIDIA NIS / NVSharpen Output",
                    15 | DlssRenderer.STORAGE_USAGE,
                    GpuFormat.RGBA8_UNORM,
                    outputWidth,
                    outputHeight,
                    1,
                    1
                )
            );
            GpuTextureView sharpenView = transaction.own(
                device.createTextureView(sharpenTexture)
            );

            DlssAuxiliaryResources result = new DlssAuxiliaryResources(
                motionTexture,
                motionView,
                historyBiasTexture,
                historyBiasView,
                depthDebugTexture,
                depthDebugView,
                motionDebugTexture,
                motionDebugView,
                motionValidityTexture,
                motionValidityView,
                transparencyHintTexture,
                transparencyHintView,
                outputTexture,
                outputView,
                sharpenTexture,
                sharpenView,
                footprint,
                budgets,
                lease
            );
            transaction.commit();
            return result;
        } catch (Throwable error) {
            transaction.close();
            transaction.attachRollbackFailureTo(error);
            if (transaction.rollbackSucceeded()) {
                if (!budgets.retireAfterGpuUse(lease)) {
                    NvidiaDlssMod.LOGGER.warn(
                        "DLSS-Rollbackbudget konnte nicht in GPU-Retirement wechseln"
                    );
                }
            } else {
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS-Rollback ließ {} Teilressource(n) unbestätigt; Lease bleibt für die Leak-Erkennung aktiv",
                    transaction.rollbackFailures(),
                    transaction.rollbackFailure()
                );
            }
            throw error;
        } finally {
            transaction.close();
        }
    }

    boolean complete() {
        return !this.closed
            && !this.motionTexture.isClosed()
            && !this.motionView.isClosed()
            && !this.historyBiasTexture.isClosed()
            && !this.historyBiasView.isClosed()
            && openOrAbsent(this.depthDebugTexture)
            && openOrAbsent(this.depthDebugView)
            && openOrAbsent(this.motionDebugTexture)
            && openOrAbsent(this.motionDebugView)
            && openOrAbsent(this.motionValidityTexture)
            && openOrAbsent(this.motionValidityView)
            && !this.transparencyHintTexture.isClosed()
            && !this.transparencyHintView.isClosed()
            && !this.outputTexture.isClosed()
            && !this.outputView.isClosed()
            && !this.sharpenTexture.isClosed()
            && !this.sharpenView.isClosed();
    }

    /**
     * Queues all images and views through Mojang's live command encoder. The
     * lease therefore always enters GPU retirement and is completed only
     * after the encoder's destruction queue has drained.
     */
    void close() {
        this.close(false);
    }

    /**
     * Best-effort cleanup for a surrounding allocation whose own partial GPU
     * state cannot be proven reachable. The lease deliberately remains active
     * so budget accounting exposes that uncertainty as a leak.
     */
    void closeRetainingLease() {
        this.close(true);
    }

    /**
     * True only when all physical closes returned normally and the shared
     * target/auxiliary lease entered GPU retirement.
     */
    boolean closeConfirmed() {
        return this.cleanupConfirmed
            && !this.leaseRetained
            && this.budgetLease == 0L;
    }

    private void close(boolean retainLease) {
        if (this.closed) {
            return;
        }
        this.closed = true;
        boolean fullyClosed = true;
        fullyClosed &= close(this.sharpenView);
        fullyClosed &= close(this.sharpenTexture);
        fullyClosed &= close(this.outputView);
        fullyClosed &= close(this.outputTexture);
        fullyClosed &= close(this.transparencyHintView);
        fullyClosed &= close(this.transparencyHintTexture);
        fullyClosed &= close(this.motionValidityView);
        fullyClosed &= close(this.motionValidityTexture);
        fullyClosed &= close(this.motionDebugView);
        fullyClosed &= close(this.motionDebugTexture);
        fullyClosed &= close(this.depthDebugView);
        fullyClosed &= close(this.depthDebugTexture);
        fullyClosed &= close(this.historyBiasView);
        fullyClosed &= close(this.historyBiasTexture);
        fullyClosed &= close(this.motionView);
        fullyClosed &= close(this.motionTexture);
        if (retainLease) {
            this.cleanupConfirmed = fullyClosed;
            this.leaseRetained = true;
            NvidiaDlssMod.LOGGER.warn(
                "DLSS-Hilfsressourcen-Lease bleibt wegen unbestätigter umgebender GPU-Teilkonstruktion konservativ aktiv"
            );
            return;
        }
        if (fullyClosed && this.budgetLease != 0L) {
            if (this.budgets.retireAfterGpuUse(this.budgetLease)) {
                this.budgetLease = 0L;
                this.cleanupConfirmed = true;
            } else {
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS-Hilfsressourcenbudget konnte nicht in GPU-Retirement wechseln"
                );
            }
        } else if (!fullyClosed) {
            this.cleanupConfirmed = false;
            NvidiaDlssMod.LOGGER.warn(
                "DLSS-Hilfsressourcen-Lease bleibt wegen unbestätigtem GPU-Cleanup aktiv"
            );
        }
    }

    private static boolean close(AutoCloseable resource) {
        if (resource == null) {
            return true;
        }
        try {
            resource.close();
            return true;
        } catch (Throwable error) {
            NvidiaDlssMod.LOGGER.warn(
                "DLSS-Teilressource konnte nicht sauber geschlossen werden",
                error
            );
            return false;
        }
    }

    private static boolean openOrAbsent(GpuTexture resource) {
        return resource == null || !resource.isClosed();
    }

    private static boolean openOrAbsent(GpuTextureView resource) {
        return resource == null || !resource.isClosed();
    }
}
