package de.morau.nvidiadlss;

import net.minecraft.network.chat.Component;

public final class DlssStatus {
    public enum State {
        STARTING,
        READY,
        RESTART_REQUIRED,
        UNAVAILABLE,
        ERROR
    }

    private static volatile State state = State.STARTING;
    private static volatile String detail = "NVIDIA-Komponente wird geladen";

    private DlssStatus() {}

    public static State state() { return state; }
    public static String detail() { return detail; }
    public static boolean ready() { return state == State.READY; }

    public static void ready(String message) { state = State.READY; detail = message; }
    public static void unavailable(String message) { state = State.UNAVAILABLE; detail = message; }
    public static void error(String message) { state = State.ERROR; detail = message; }
    public static void restartRequired() {
        state = State.RESTART_REQUIRED;
        detail = "Neustart erforderlich / restart required";
    }
    public static void clearRestartRequired() {
        if (state == State.RESTART_REQUIRED) {
            state = State.UNAVAILABLE;
            detail = "DLSS disabled by configuration";
        }
    }

    public static Component tooltip() {
        if (VivecraftCompat.isVrRunning()) return Component.translatable("options.nvidia_dlss.tooltip.vr");
        if (state == State.READY) return Component.translatable("options.nvidia_dlss.tooltip.ready").append("\n").append(detail);
        if (state == State.RESTART_REQUIRED) {
            return Component.translatable(
                "options.nvidia_dlss.tooltip.restart_required"
            );
        }
        if (state == State.STARTING) return Component.translatable("options.nvidia_dlss.tooltip.starting");
        return Component.translatable("options.nvidia_dlss.tooltip.unavailable", detail);
    }
}
