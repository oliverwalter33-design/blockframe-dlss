package de.morau.nvidiadlss.nativebridge;

public final class NativeStreamline {
    public static final int CLEANUP_UNCONFIRMED = Integer.MIN_VALUE + 0xBF;

    private NativeStreamline() {}

    public static native int bootstrap(String interposerPath, String pluginPath, String logPath);
    public static native byte[] featureRequirements();
    public static native int setVulkanInfo(long instance, long physicalDevice, long device,
        int graphicsFamily, int graphicsIndex, int computeFamily, int computeIndex);
    public static native int queuePresent(long queue, long presentInfo);
    public static native long optimalSize(int mode, int outputWidth, int outputHeight);
    public static native long evaluate(int viewportId, int frameIndex, int mode,
        int inputWidth, int inputHeight, int outputWidth, int outputHeight,
        long dlssCommandBuffer, long nisCommandBuffer,
        long colorImage, long colorView, long depthImage, long depthView,
        long motionImage, long motionView, long historyBiasImage, long historyBiasView,
        long transparencyHintImage, long transparencyHintView,
        long outputImage, long outputView,
        long sharpenImage, long sharpenView, float sharpness,
        float[] projection, float[] inverseProjection, float[] clipToPrev, float[] prevToClip,
        float cameraX, float cameraY, float cameraZ,
        float upX, float upY, float upZ,
        float rightX, float rightY, float rightZ,
        float forwardX, float forwardY, float forwardZ,
        float nearPlane, float farPlane, float fov, float aspect,
        float jitterX, float jitterY, int auditHintMode, boolean reset);
    public static native int resetViewport(int viewportId);
    public static native String lastMessage();
    public static native String lastDiagnostic();
    public static native int shutdown();
}
