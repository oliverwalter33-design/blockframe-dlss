package de.morau.blockframe.core.budget;

/**
 * Independent budget categories. A category limit is always subordinate to
 * the global RAM or VRAM limit.
 */
public enum MemoryCategory {
    TERRAIN,
    ENTITIES,
    PARTICLES,
    SHADER_RESOURCES,
    CACHES,
    STAGING,
    DIAGNOSTICS
}
