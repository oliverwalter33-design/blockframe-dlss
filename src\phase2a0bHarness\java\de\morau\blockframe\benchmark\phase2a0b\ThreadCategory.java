package de.morau.blockframe.benchmark.phase2a0b;

/**
 * Stable diagnostic categories. UNKNOWN is intentionally distinct from OTHER
 * so uncertain names are never silently attributed.
 */
public enum ThreadCategory {
    RENDER,
    CLIENT_MAIN,
    INTEGRATED_SERVER,
    MOJANG_WORKER,
    VULKAN_SUBMISSION,
    BLOCKFRAME_WORKER,
    FILE_IO,
    GC,
    JIT,
    OTHER,
    UNKNOWN
}
