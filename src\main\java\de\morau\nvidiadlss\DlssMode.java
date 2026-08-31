package de.morau.nvidiadlss;

import net.minecraft.network.chat.Component;

public enum DlssMode {
    OFF("off", 0),
    QUALITY("quality", 1),
    BALANCED("balanced", 2),
    PERFORMANCE("performance", 3),
    DLAA("dlaa", 4),
    ULTRA_PERFORMANCE("ultra_performance", 5);

    private final String id;
    private final int nativeId;

    DlssMode(String id, int nativeId) {
        this.id = id;
        this.nativeId = nativeId;
    }

    public String id() { return this.id; }
    public int nativeId() { return this.nativeId; }
    public Component label() { return Component.translatable("options.nvidia_dlss." + this.id); }

    public static DlssMode byId(String id) {
        for (DlssMode mode : values()) if (mode.id.equalsIgnoreCase(id)) return mode;
        return OFF;
    }
}
