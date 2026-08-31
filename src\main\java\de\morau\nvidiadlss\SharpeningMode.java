package de.morau.nvidiadlss;

import net.minecraft.network.chat.Component;

public enum SharpeningMode {
    OFF("off"),
    AUTO("auto"),
    MANUAL("manual");

    private final String id;

    SharpeningMode(String id) { this.id = id; }

    public String id() { return this.id; }
    public Component label() { return Component.translatable("options.nvidia_dlss.sharpening." + this.id); }

    public static SharpeningMode byId(String id) {
        for (SharpeningMode value : values()) if (value.id.equalsIgnoreCase(id)) return value;
        return AUTO;
    }
}
