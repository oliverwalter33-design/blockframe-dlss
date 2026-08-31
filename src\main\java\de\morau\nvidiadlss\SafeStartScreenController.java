package de.morau.nvidiadlss;

import de.morau.blockframe.core.BlockframeRuntime;
import java.lang.ref.WeakReference;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-only, once-per-process Safe-Start offer on the first title screen.
 *
 * <p>The current process is never reconfigured. Explicit acceptance only
 * queues a one-shot Safe Start for the next process; ignoring the button keeps
 * normal operation unchanged.</p>
 */
@EventBusSubscriber(
    modid = NvidiaDlssMod.MOD_ID,
    value = Dist.CLIENT
)
public final class SafeStartScreenController {
    private static final Logger LOGGER =
        LoggerFactory.getLogger("blockframe-safe-start-screen");
    private static final SafeStartScreenController PRODUCTION =
        productionController();

    private final SafeStartAccess access;
    private OfferState state = OfferState.WAITING_FOR_FIRST_TITLE;
    private WeakReference<Object> offeredTitleScreen =
        new WeakReference<>(null);

    SafeStartScreenController(SafeStartAccess access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @SubscribeEvent
    public static void onScreenInitialized(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        if (!PRODUCTION.offerOnTitleScreen(titleScreen)) {
            return;
        }

        Bounds bounds = buttonBounds(
            titleScreen.width,
            titleScreen.height
        );
        Button offer = Button.builder(
            Component.translatable("blockframe.safe_start.offer"),
            button -> {
                button.active = false;
                button.visible = false;
                openConfirmation(titleScreen);
            }
        )
            .bounds(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height()
            )
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "blockframe.safe_start.offer.tooltip"
                    )
                )
            )
            .build();
        event.addListener(offer);
    }

    synchronized boolean offerOnTitleScreen(Object titleScreenIdentity) {
        Objects.requireNonNull(
            titleScreenIdentity,
            "titleScreenIdentity"
        );
        if (this.state == OfferState.OFFERED) {
            return this.offeredTitleScreen.get() == titleScreenIdentity;
        }
        if (this.state != OfferState.WAITING_FOR_FIRST_TITLE) {
            return false;
        }
        this.state = OfferState.NO_OFFER;

        try {
            if (!this.access.safeStartOfferAvailable()) {
                return false;
            }
            this.access.markSafeStartOffered();
            this.offeredTitleScreen =
                new WeakReference<>(titleScreenIdentity);
            this.state = OfferState.OFFERED;
            return true;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Safe-Start-Angebot konnte nicht vorbereitet werden",
                exception
            );
            return false;
        }
    }

    synchronized boolean acceptOffer() {
        if (this.state != OfferState.OFFERED) {
            return false;
        }
        this.state = OfferState.ACCEPTED;
        try {
            return this.access.queueSafeStartForNextRun();
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Safe Start konnte nicht fuer den naechsten Prozess "
                    + "vorgemerkt werden",
                exception
            );
            return false;
        }
    }

    synchronized boolean declineOffer() {
        if (this.state != OfferState.OFFERED) {
            return false;
        }
        this.state = OfferState.DECLINED;
        try {
            this.access.declineSafeStart();
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Safe-Start-Ablehnung konnte nicht gespeichert werden",
                exception
            );
        }
        return true;
    }

    synchronized OfferState state() {
        return this.state;
    }

    static Bounds buttonBounds(int screenWidth, int screenHeight) {
        int availableWidth = Math.max(1, screenWidth - 8);
        int width = Math.min(200, availableWidth);
        int x = Math.max(0, (screenWidth - width) / 2);
        int preferredY = screenHeight / 4 + 32 + 5 * 24;
        int y = Math.max(
            0,
            Math.min(preferredY, Math.max(0, screenHeight - 24))
        );
        return new Bounds(x, y, width, Button.DEFAULT_HEIGHT);
    }

    private static SafeStartScreenController productionController() {
        return new SafeStartScreenController(
            new SafeStartAccess() {
                @Override
                public boolean safeStartOfferAvailable() {
                    return BlockframeRuntime.safeStartOfferAvailable();
                }

                @Override
                public void markSafeStartOffered() {
                    BlockframeRuntime.markSafeStartOffered();
                }

                @Override
                public boolean queueSafeStartForNextRun() {
                    return BlockframeRuntime.queueSafeStartForNextRun();
                }

                @Override
                public void declineSafeStart() {
                    BlockframeRuntime.declineSafeStart();
                }
            }
        );
    }

    private static void openConfirmation(TitleScreen titleScreen) {
        Minecraft minecraft = titleScreen.getMinecraft();
        minecraft.gui.setScreen(
            new ConfirmScreen(
                accepted -> {
                    if (!accepted) {
                        PRODUCTION.declineOffer();
                        minecraft.gui.setScreen(titleScreen);
                        return;
                    }

                    boolean queued = PRODUCTION.acceptOffer();
                    minecraft.gui.setScreen(
                        new AlertScreen(
                            () -> minecraft.gui.setScreen(titleScreen),
                            Component.translatable(
                                queued
                                    ? "blockframe.safe_start.queued.title"
                                    : "blockframe.safe_start.failed.title"
                            ),
                            Component.translatable(
                                queued
                                    ? "blockframe.safe_start.queued.message"
                                    : "blockframe.safe_start.failed.message"
                            ),
                            CommonComponents.GUI_BACK,
                            true
                        )
                    );
                },
                Component.translatable(
                    "blockframe.safe_start.confirm.title"
                ),
                Component.translatable(
                    "blockframe.safe_start.confirm.message"
                ),
                Component.translatable(
                    "blockframe.safe_start.confirm.accept"
                ),
                Component.translatable(
                    "blockframe.safe_start.confirm.decline"
                )
            )
        );
    }

    interface SafeStartAccess {
        boolean safeStartOfferAvailable();

        void markSafeStartOffered();

        boolean queueSafeStartForNextRun();

        void declineSafeStart();
    }

    enum OfferState {
        WAITING_FOR_FIRST_TITLE,
        NO_OFFER,
        OFFERED,
        ACCEPTED,
        DECLINED
    }

    record Bounds(int x, int y, int width, int height) {
    }
}
