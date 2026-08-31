package de.morau.nvidiadlss;

import java.lang.reflect.Field;

public final class VivecraftCompat {
    private static boolean checked;
    private static Field vrRunning;

    private VivecraftCompat() {}

    public static boolean isVrRunning() {
        if (!checked) {
            checked = true;
            try {
                Class<?> state = Class.forName("org.vivecraft.client_vr.VRState", false, VivecraftCompat.class.getClassLoader());
                vrRunning = state.getField("VR_RUNNING");
            } catch (ReflectiveOperationException ignored) {
                vrRunning = null;
            }
        }
        if (vrRunning == null) return false;
        try { return vrRunning.getBoolean(null); }
        catch (IllegalAccessException ignored) { return false; }
    }
}
