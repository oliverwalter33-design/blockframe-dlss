package de.morau.blockframe.benchmark.phase2a0b;

import java.util.Arrays;

/**
 * Single inventory of every Phase 2A.0B fail-closed preflight gate and the
 * two MEASURE invariants exercised by the offline state machine.
 */
public final class Phase2a0bGateInventory {
    public enum Availability {
        OFFLINE_AND_LIVE,
        LIVE_ONLY
    }

    public enum GateId {
        INITIALIZATION_ACTIVE_RUN,
        INITIALIZATION_RENDER_THREAD,
        INITIALIZATION_WORLD_CONTEXT,
        ACTIVE_RUN_CONTRACT,
        REPLAY_MODE,
        PROTECTED_WORLD,
        RUN_PATHS,
        SCENE_MANIFEST_HASH,
        FIXTURE_HASH,
        GOLDEN_INVENTORY,
        MOD_PROFILE_HASH,
        CONFIG_RECEIPT,
        START_PROFILE_HASH,
        SCENE_SET,
        BLOCKFRAME_IDENTITY,
        BLOCKFRAME_VERSION,
        BLOCKFRAME_JAR_HASH,
        LOADER_VERSIONS,
        BACKEND,
        GPU,
        DRIVER,
        CPU_TOPOLOGY,
        CPU_AFFINITY,
        REPLAY_OWNER,
        LOADED_RUN_COPY,
        DIMENSION,
        RENDER_DISTANCE,
        SIMULATION_DISTANCE,
        FRAMEBUFFER,
        WINDOW_MODE,
        FRAME_PACING,
        WEATHER,
        CREATIVE_CAMERA,
        NATIVE_BASELINE_OFF,
        THREAD_CPU_BOUNDARIES,
        MEASURE_FILE_IO,
        MEASURE_THREAD_SCAN
    }

    public record Gate(
        GateId id,
        String owner,
        String input,
        String expected,
        String dataType,
        String normalization,
        String timing,
        String ioOrLock,
        String failureStatus,
        Availability availability
    ) {
    }

    private static final Gate[] GATES = {
        gate(GateId.INITIALIZATION_ACTIVE_RUN, "RenderReadinessState", "active-run presence", "regular file cached at mod bootstrap", "boolean", "one bootstrap read", "mod lifecycle before render callbacks", "no render-callback file stat or lock", Availability.LIVE_ONLY),
        gate(GateId.INITIALIZATION_RENDER_THREAD, "RenderReadinessState", "first real GameRenderer.render HEAD callback thread ID", "generation-bound render owner candidate", "long", "bind from callback; no name/global search", "first render callback", "none", Availability.LIVE_ONLY),
        gate(GateId.INITIALIZATION_WORLD_CONTEXT, "RenderReadinessState", "client level/player/initialized camera in one callback", "all present in the same render callback", "primitive readiness mask", "direct callback context", "before one owner publication", "no wait, polling or internal deadline", Availability.LIVE_ONLY),
        gate(GateId.ACTIVE_RUN_CONTRACT, "Phase2a0bPreflight", "active-run manifest", "schema 1 and complete fields", "versioned manifest", "typed at parse boundary", "pre-owner", "one manifest read", Availability.OFFLINE_AND_LIVE),
        gate(GateId.REPLAY_MODE, "Phase2a0bPreflight", "mode", "REPLAY_SUITE", "MeasurementMode", "trim plus Locale.ROOT", "pre-owner", "none after parse", Availability.OFFLINE_AND_LIVE),
        gate(GateId.PROTECTED_WORLD, "Phase2a0bPreflight", "run copy", "neither Golden nor Stadt Bau", "Windows path/name", "case-insensitive only on Windows", "pre-owner", "none", Availability.OFFLINE_AND_LIVE),
        gate(GateId.RUN_PATHS, "Phase2a0bPreflight", "instance/run/repository paths", "absolute descendants of expected roots", "Path", "absolute normalize; Windows comparison where applicable", "pre-owner", "metadata only", Availability.OFFLINE_AND_LIVE),
        gate(GateId.SCENE_MANIFEST_HASH, "Phase2a0bPreflight", "scene manifest SHA-256", "captured full hash", "Sha256", "canonical lowercase hex", "pre-owner", "one manifest read", Availability.OFFLINE_AND_LIVE),
        gate(GateId.FIXTURE_HASH, "Phase2a0bPreflight", "Golden SHA-256", "captured full hash", "Sha256", "canonical lowercase hex", "pre-owner", "launcher scans; runtime consumes audit", Availability.OFFLINE_AND_LIVE),
        gate(GateId.GOLDEN_INVENTORY, "FixtureRunManager", "Golden count/bytes/hash", "115 / 55962095 / pinned hash", "inventory tuple", "ordinal paths and exact bytes", "pre-owner", "launcher/runtime static fixture scan", Availability.OFFLINE_AND_LIVE),
        gate(GateId.MOD_PROFILE_HASH, "Phase2a0bPreflight", "staged mod profile SHA-256", "captured full hash", "Sha256", "canonical lowercase hex", "pre-owner", "launcher scan; runtime consumes audit", Availability.OFFLINE_AND_LIVE),
        gate(GateId.CONFIG_RECEIPT, "ConfigTransactionReceipt", "immutable external receipt", "APPLIED_VERIFIED and exact hashes/instance", "versioned receipt", "typed fields; Windows path semantics", "pre-owner exactly once", "one receipt read; no config lock/hash", Availability.OFFLINE_AND_LIVE),
        gate(GateId.START_PROFILE_HASH, "Phase2a0bPreflight", "semantic/start profile SHA-256", "captured full hash", "Sha256", "canonical lowercase hex", "pre-owner", "none after receipt", Availability.OFFLINE_AND_LIVE),
        gate(GateId.SCENE_SET, "Phase2a0bPreflight", "scene IDs/order/readiness", "four pinned SceneId values", "SceneId[]", "trim plus Locale.ROOT at parse", "pre-owner", "none after scene-manifest read", Availability.OFFLINE_AND_LIVE),
        gate(GateId.BLOCKFRAME_IDENTITY, "Phase2a0bPreflight", "loaded mod ID/code-source filename/location", "voxellift / exact 0.3.14 JAR / instance mods", "typed artifact identity", "trim once; Windows path semantics", "pre-owner", "NeoForge metadata only", Availability.OFFLINE_AND_LIVE),
        gate(GateId.BLOCKFRAME_VERSION, "Phase2a0bPreflight", "release/metadata version", "0.3.14 / 0.3.14-neoforge-26.2", "ArtifactVersion", "trim once", "pre-owner", "NeoForge metadata only", Availability.OFFLINE_AND_LIVE),
        gate(GateId.BLOCKFRAME_JAR_HASH, "Phase2a0bPreflight", "loaded production JAR SHA-256", "pinned full hash", "Sha256", "canonical lowercase hex", "pre-owner", "one loaded-code-source hash", Availability.OFFLINE_AND_LIVE),
        gate(GateId.LOADER_VERSIONS, "Phase2a0bPreflight", "Minecraft/NeoForge/harness versions", "26.2 / 26.2.0.23-beta / 1.0.0", "ArtifactVersion tuple", "trim once", "pre-owner", "NeoForge metadata only", Availability.OFFLINE_AND_LIVE),
        gate(GateId.BACKEND, "Phase2a0bPreflight", "Mojang backendName", "Backend.VULKAN", "Backend", "trim plus Locale.ROOT once", "pre-owner", "one cached device-info read", Availability.OFFLINE_AND_LIVE),
        gate(GateId.GPU, "Phase2a0bPreflight", "Mojang device name", "NVIDIA GeForce RTX 4090", "typed runtime-profile field", "trim once", "pre-owner", "same cached device-info read", Availability.OFFLINE_AND_LIVE),
        gate(GateId.DRIVER, "Phase2a0bPreflight", "Mojang driver info", "contains NVIDIA 610.74", "typed runtime-profile field", "trim once", "pre-owner", "same cached device-info read", Availability.OFFLINE_AND_LIVE),
        gate(GateId.CPU_TOPOLOGY, "Phase2a0bPreflight", "CPU model/physical/logical/JVM", "9800X3D / 8 / 16 / 16", "CpuTopology", "probe once", "pre-owner", "one pre-replay OS query", Availability.OFFLINE_AND_LIVE),
        gate(GateId.CPU_AFFINITY, "Phase2a0bPreflight", "process affinity logical count", "16", "int", "probe once", "pre-owner", "same pre-replay OS query", Availability.OFFLINE_AND_LIVE),
        gate(GateId.REPLAY_OWNER, "Phase2a0bPreflight", "published owner count", "exactly one after every earlier gate", "int", "none", "last pre-owner gate", "none", Availability.OFFLINE_AND_LIVE),
        gate(GateId.LOADED_RUN_COPY, "Phase2a0bRuntime", "integrated-server world path", "prepared physical run copy", "Path", "absolute normalize; Windows comparison", "WORLD_WAIT before scene work", "server path metadata", Availability.LIVE_ONLY),
        gate(GateId.DIMENSION, "Phase2a0bRuntime", "loaded dimension", "scene dimension", "resource identifier", "exact", "WORLD_WAIT before warmup", "none", Availability.LIVE_ONLY),
        gate(GateId.RENDER_DISTANCE, "Phase2a0bRuntime", "render distance", "scene value", "int", "none", "WORLD_WAIT before warmup", "none", Availability.LIVE_ONLY),
        gate(GateId.SIMULATION_DISTANCE, "Phase2a0bRuntime", "simulation distance", "scene value", "int", "none", "WORLD_WAIT before warmup", "none", Availability.LIVE_ONLY),
        gate(GateId.FRAMEBUFFER, "Phase2a0bRuntime", "framebuffer width/height", "scene value", "int pair", "none", "WORLD_WAIT before warmup", "none", Availability.LIVE_ONLY),
        gate(GateId.WINDOW_MODE, "Phase2a0bRuntime", "fullscreen state", "scene WindowMode", "boolean/enum", "typed at manifest parse", "WORLD_WAIT before warmup", "none", Availability.LIVE_ONLY),
        gate(GateId.FRAME_PACING, "Phase2a0bRuntime", "vsync/FPS limit", "scene values", "boolean/int", "none", "WORLD_WAIT before warmup", "none", Availability.LIVE_ONLY),
        gate(GateId.WEATHER, "Phase2a0bRuntime", "weather contract", "CLEAR", "closed scene value", "typed at manifest parse", "WORLD_WAIT before warmup", "none", Availability.LIVE_ONLY),
        gate(GateId.CREATIVE_CAMERA, "Phase2a0bRuntime", "player instabuild", "true", "boolean", "none", "WORLD_WAIT before warmup", "none", Availability.LIVE_ONLY),
        gate(GateId.NATIVE_BASELINE_OFF, "Phase2a0bRuntime", "loaded DLSS mode", "OFF", "DlssMode", "enum name once", "WORLD_WAIT before warmup", "reflective cached config read; no file I/O", Availability.LIVE_ONLY),
        gate(GateId.THREAD_CPU_BOUNDARIES, "ThreadCpuWindow", "ThreadMXBean snapshots", "exactly two per MEASURE window", "counter", "none", "MEASURE boundaries", "no file I/O", Availability.OFFLINE_AND_LIVE),
        gate(GateId.MEASURE_FILE_IO, "Phase2a0bRuntime", "file I/O calls", "zero in MEASURE", "counter/invariant", "none", "MEASURE", "forbidden", Availability.OFFLINE_AND_LIVE),
        gate(GateId.MEASURE_THREAD_SCAN, "ThreadCpuWindow", "thread discovery calls", "zero per frame", "counter/invariant", "none", "MEASURE frames", "ThreadMXBean only at boundaries", Availability.OFFLINE_AND_LIVE)
    };

    private Phase2a0bGateInventory() {
    }

    public static Gate[] all() {
        return GATES.clone();
    }

    public static Gate require(GateId id) {
        return Arrays.stream(GATES)
            .filter(gate -> gate.id() == id)
            .findFirst()
            .orElseThrow();
    }

    private static Gate gate(
        GateId id,
        String owner,
        String input,
        String expected,
        String dataType,
        String normalization,
        String timing,
        String ioOrLock,
        Availability availability
    ) {
        return new Gate(
            id,
            owner,
            input,
            expected,
            dataType,
            normalization,
            timing,
            ioOrLock,
            "FAILED_CLOSED",
            availability
        );
    }
}
