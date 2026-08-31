# BlockFrame DLSS

[![Source provenance](https://github.com/oliverwalter33-design/blockframe-dlss/actions/workflows/source-provenance.yml/badge.svg)](https://github.com/oliverwalter33-design/blockframe-dlss/actions/workflows/source-provenance.yml)
[![Native reproducibility](https://github.com/oliverwalter33-design/blockframe-dlss/actions/workflows/native-reproducibility.yml/badge.svg)](https://github.com/oliverwalter33-design/blockframe-dlss/actions/workflows/native-reproducibility.yml)

BlockFrame DLSS is an experimental client-side NeoForge mod that integrates NVIDIA DLSS Super Resolution and DLAA into Minecraft 26.2's native Vulkan renderer.

> **Experimental:** image quality, compatibility and performance can vary by GPU, driver, resolution, resource pack, shader pack and other rendering mods.

> **Official source:** Modrinth ownership and artifact correspondence are documented in [MODRINTH-OWNERSHIP.md](MODRINTH-OWNERSHIP.md). The exact 0.3.16 source tree is preserved on [`release/0.3.16-neoforge-26.2`](https://github.com/oliverwalter33-design/blockframe-dlss/tree/release/0.3.16-neoforge-26.2). Every native DLL and SPIR-V file in both Modrinth versions is mapped to its checked-in source or exact official NVIDIA release in [MODRINTH-BINARY-PROVENANCE.md](MODRINTH-BINARY-PROVENANCE.md).

## Download

Player-ready builds are published on [CurseForge](https://www.curseforge.com/minecraft/mc-mods/blockframe-dlss). Do not install a JAR produced from an incomplete source checkout without the required native runtime files.

## Features

- DLAA plus DLSS Quality, Balanced and Performance modes.
- NVIDIA-reported optimal render sizes for each output resolution.
- Reconstructed camera motion vectors and tracked transforms for moving entities.
- Temporal jitter, history resets and reversed-Z depth handling.
- World-only texture LOD bias and anisotropic filtering without changing GUI or font samplers.
- Optional NVIDIA NIS sharpening after reconstruction and before the native-resolution HUD.
- Safe fallback when DLSS is unavailable.
- SHA-256-validated, size-bounded persistent extraction of the immutable native runtime.
- F8 diagnostics overlay and F9 same-frame capture for image-quality reports.
- Cached state for every current optional BlockFrame feature, bounded
  last-known-good/run-state recovery and an explicit next-launch Safe Start
  that never rewrites normal settings.
- Vivecraft may remain installed, but DLSS/DLAA is disabled while active VR rendering is detected.

## Requirements

- Minecraft Java Edition 26.2
- NeoForge 26.2 (tested with 26.2.0.23-beta)
- Java 25
- Windows x64
- Minecraft's native Vulkan backend
- A supported NVIDIA RTX GPU and a current NVIDIA driver

The controls are available under **Options -> Video Settings -> DLSS**. The compact mode selector exposes Off, Quality, Balanced, Performance, DLAA and Ultra (4K); its tooltip reports the selected mode and bundled runtime versions. Sodium is not a build or runtime dependency; benchmark comparisons use a separate installation.

## Building the source

Run source checks with Java 25 and Gradle 9.2.1:

```powershell
gradle clean test
```

This source-only publication intentionally omits the binary
`gradle-wrapper.jar`. The required Gradle version remains pinned in
`gradle/wrapper/gradle-wrapper.properties`; use an installed Gradle 9.2.1 or
regenerate the standard wrapper locally with that version.

The build has no Sodium compile dependency and does not bundle Sodium source.
When Sodium 0.9.1 is present, an optional `@Pseudo` compatibility mixin marks
only its exact half-alpha terrain cutout pipeline for BlockFrame's DLSS
transparency hint. Without Sodium, that mixin is not applied. Sodium remains a
separately installed integration/benchmark component, never a bundled runtime.

### Building a distributable JAR

Native SDK binaries are deliberately not tracked in Git. To create a working release JAR:

1. Download the official NVIDIA Streamline 2.12.0 release and point the staging script at its production `bin\x64` directory. Do not use the `development` directory.
2. Install Zig, the Vulkan SDK and Java 25. Set `STREAMLINE_INCLUDE` to Streamline's `include` directory. `VULKAN_SDK` and `JAVA_HOME` may provide the other include/tool paths.
3. Stage the signed NVIDIA runtime and build the project's JNI bridge and compute shader.
4. Run the Gradle build.

```powershell
.\scripts\stage-nvidia-runtime.ps1 -StreamlineBin 'C:\path\to\streamline\bin\x64'
.\native\build-native.ps1
gradle clean test build
.\scripts\verify-native-provenance.ps1 -JarPath '.\build\libs\blockframe-dlss-0.3.18-neoforge-26.2.jar'
```

`build` intentionally fails when a required native runtime file is absent. The finished JAR is written to `build\libs`.
The final verification command recognizes either published Modrinth JAR by
SHA-256 and checks every native binary entry against its version-specific
review manifest. For a new release, add its JAR and native-entry hashes to the
verification script; `-ExpectedJarSha256` can additionally pin an invocation
to one expected artifact.

The public native-reproducibility workflow additionally rebuilds the
project-owned outputs from pinned tools and headers. Both shaders reproduce
byte-for-byte. The 0.3.18 bridge reproduces outside 20 structurally identified
run-dependent PE/CodeView metadata bytes; the exact scope and normalized hash
are documented in
[MODRINTH-BINARY-PROVENANCE.md](MODRINTH-BINARY-PROVENANCE.md#exact-scope-of-the-0318-bridge-rebuild-check).

The native build script also accepts explicit `-StreamlineInclude`, `-VulkanInclude`, `-JniInclude`, `-ZigExecutable` and `-GlslcExecutable` parameters.

## BlockFrame native cache

On the Vulkan DLSS-readiness path, the ten immutable Windows x64 DLL/license
resources are materialized under `cache/blockframe/immutable-v1`. The build
embeds a canonical key and SHA-256 manifest; every persistent hit revalidates
the exact file set, sizes and hashes before the JNI bridge is loaded.
Streamline logs remain separate under `logs/blockframe-streamline`.

`config/blockframe-engine.properties` may set a positive byte limit:

```properties
cache.maxBytes=268435456
```

The default is 256 MiB. Missing, malformed, zero or negative values restore
that safe default. If the cache filesystem or atomic directory move is
unavailable, BlockFrame uses a separately verified temporary result rather
than an unsafe non-atomic entry. This first cache slice contains no world,
registry, loot, block-entity, model, shader or pipeline state. See
`CACHE_FORMAT.md` for the exact format and limitations.

The native-only five-cold-entry/five-warm-entry microbenchmark is:

```powershell
gradle benchmarkNativeCache --no-daemon --rerun-tasks
```

## Optional feature switches and Safe Start

BlockFrame tracks exactly 13 current optional features. F8 displays their
cached requested, supported, enabled, effective, fallback/quarantine, reason
and client/device generation state without reading a file or querying Vulkan.
Those lines are the last sparse state publication, not a per-frame sample. In
particular, the stability counter is republished at lifecycle transitions and
world-frame 1/120; frames 2 through 119 reuse the cached publication.
Missing or invalid Boolean settings use the existing enabled default:

| Stable feature ID | Configuration | Default | Takes effect | Disabled/failure fallback |
| --- | --- | --- | --- | --- |
| `render.dlss_mode` | `config/voxellift.properties`: `mode` | `off` | live through the existing mode control | Mojang native-resolution rendering |
| `render.entity_motion_scratch` | `render.entityMotionScratchEnabled` | `true` | process restart | complete legacy motion collector |
| `render.entity_history_native_experimental` | `entityHistoryBackend` | `heap` | process restart | heap, then legacy |
| `render.transform_scratch` | `render.transformScratchEnabled` | `true` | process restart | existing legacy/fresh transform path |
| `vulkan.shader_setup_pool` | `vulkan.shaderSetupPoolEnabled` | `true` | process restart | direct setup allocation |
| `vulkan.material_sampler_cache` | `vulkan.materialSamplerCacheEnabled` | `true` | process restart | original Mojang sampler |
| `render.outline_pose_reuse` | `render.outlinePoseReuseEnabled` | `true` | process restart | fresh `PoseStack` before submission |
| `diagnostics.frame_profiler` | `profiler.enabled` | `true` | process restart | no-op/unavailable diagnostics |
| `diagnostics.gpu_breadcrumbs` | `diagnostics.gpuBreadcrumbsEnabled` | `true` | process restart | no breadcrumb ring |
| `diagnostics.physical_memory` | `diagnostics.physicalMemoryTelemetryEnabled` | `true` | process restart | cached disabled state; no OS/driver query |
| `diagnostics.debug_labels` | `diagnostics.debugLabelsEnabled` | `true` | process restart | no-op labels |
| `diagnostics.tracy_correlation` | `diagnostics.tracyCorrelationEnabled` | `true` | process restart | no-op Tracy adapters |
| `diagnostics.device_fault` | `diagnostics.deviceFaultEnabled` | `true` | process restart | no extension/function/capture |

All keys except `mode` and `entityHistoryBackend` are in
`config/blockframe-engine.properties`. Native entity history is never selected
by default. There are no switches for future phases or the rejected BlockFrame
staging ring. If the current `voxellift.properties` file is absent, the legacy
`config/nvidia_dlss.properties` may still supply a read-only compatibility
snapshot; loading it does not migrate or write a file. Only the existing
explicit settings save writes the current file. Safe Start never calls it.
F8's `config-owner` field names that canonical file/key contract; it does not
claim whether the current value came from that file, the read-only legacy
compatibility snapshot or a default.

A cold start with `mode=off` does not materialize the Native Cache, load the
JNI bridge, initialize Streamline or request Streamline Vulkan requirements.
Because Vulkan instance/device requirements can only be negotiated before
device creation, switching from a process that started in OFF to DLSS/DLAA
requires a restart. The UI reports that boundary instead of attempting a
partially initialized live activation. Safe Start uses the same zero-bootstrap
path and does not rewrite the normal configuration.

Run state is separate from normal configuration:

```text
config/blockframe-state/run-state-a.bfrs
config/blockframe-state/run-state-b.bfrs
config/blockframe-state/run-state.lock
```

The two slots are each limited to 64 KiB, SHA-256 checked and written through
fixed same-directory temporary files. On filesystems with atomic replacement,
BlockFrame reports `ATOMIC`; otherwise it reports
`RECOVERABLE_TWO_SLOT` and keeps the other valid generation as recovery.
Corruption, truncation, a future schema, permission/write failure or a lock
conflict disables persistence or leaves it read-only without blocking
Minecraft.

A normal run becomes last-known-good only after backend and feature
initialization, the first successful world frame and 120 consecutive successful
world frames. An incomplete exit is recorded only as an unclean previous run,
not as proof that BlockFrame crashed. A clean marker requires the real normal
client stopping/stopped lifecycle, normal `Minecraft.close` return and
successful BlockFrame/DLSS cleanup. A confirmed failure may still have a clean
marker; its error is not erased.

After a matching confirmed failure or incomplete pre-stability run, the title
screen may offer Safe Start once for that event. Accepting explicitly queues a
one-shot for the next process because the title screen appears after Vulkan
device creation. Ignoring or declining changes nothing. The next process
temporarily disables the 13 optional features and uses the Mojang, heap,
legacy, direct, original-sampler and no-op fallbacks listed above. It never
saves or rewrites `voxellift.properties` or
`blockframe-engine.properties`, changes no world/player data, content,
particles or view/simulation distance, and does not automatically declare the
original failure fixed.

Physical RAM/VRAM telemetry remains diagnostic only. While enabled, frames
perform a cheap due check, but real OS and eligible driver queries are at least
one second apart. F8 only reads the cached result. The known 48-byte allocation
per actual device-local aggregation remains a final audit candidate and drives
no eviction, quality or scheduler decision.

The reload hook observes only NeoForge's successful
`ClientResourceLoadFinishedEvent`. A completed initial load or F3+t reload
revalidates and restarts the stability window; an abort before that finish
event is not observable through the current source contract and is not claimed
as a tested reset.

The following Phase-1A.12 checkpoint is retained as historical evidence for
the older 0.3.14 candidate; it is not the current release-hardening result.
That checkpoint passed 77 suites / 486 tests with zero failures, errors or
skips. Its clean artifact was
`blockframe-dlss-0.3.14-neoforge-26.2.jar`, 33,169,623 bytes, SHA-256
`3C1C559EFE6879570C40D6EFBD2E24686267108F97B7D20DDF1644DD905610B9`.
The cached-read benchmark and its limits are reported in `BENCHMARKS.md`; it
does not establish Minecraft FPS, frame time, RSS, VRAM or a speedup.

## Optional Vulkan device-fault diagnostics

BlockFrame requests `VK_EXT_device_fault` only on Vulkan and only when both
the extension and its `deviceFault` feature are advertised. It is optional,
never replaces Minecraft's device, queue, allocator or debug ownership, and
does nothing under OpenGL. The default is enabled for capability negotiation;
developers can disable it explicitly:

```properties
diagnostics.deviceFaultEnabled=false
```

The function is resolved only for the current logical-device generation.
BlockFrame queries fault details only after a real `VK_ERROR_DEVICE_LOST`;
there is no per-frame polling, background thread or intentionally induced
device loss. F8 displays cached state only. Capture is bounded, and vendor
binary data is neither requested nor persisted.

## Experimental entity-history backend

Entity motion history uses the fixed heap backend by default. Missing or
invalid configuration also keeps that default:

```properties
entityHistoryBackend=heap
```

For controlled diagnostics only, the native backend can be selected explicitly:

```properties
entityHistoryBackend=native-experimental
```

The experimental chain is Native -> Heap -> Legacy; the normal chain is
Heap -> Legacy. Native is not recommended for normal play: across the current
fresh-JVM 128-to-49,152-entity matrix it remains 1.6166x-2.8374x slower than
heap, with no measured allocation or GC advantage. This switch changes no
entity count, content, view distance, particles or rendering-quality setting.

## Reporting problems

Include the following information in an issue:

- GPU model and NVIDIA driver version
- selected DLSS/DLAA mode and output resolution
- active resource and shader packs
- whether Sodium or Vivecraft is installed
- `latest.log`
- an F9 capture when the problem is a visual artifact

Transparent/cutout vegetation, particles, animated textures and some object-animation paths do not always provide complete per-pixel motion data. Flicker or ghosting can therefore remain. Shader-provided TAA, TAAU, FXAA, SMAA, sharpening or internal upscaling may conflict with DLSS/DLAA.

## Project identity

The public project name is **BlockFrame DLSS**. The internal NeoForge mod ID and existing configuration namespace remain `voxellift` for compatibility with already published builds and user configuration files.

## License

Original Java, JNI and shader integration code is licensed under the [MIT License](LICENSE). NVIDIA binaries and materials are not covered by MIT and remain under the separate license texts included with release JARs. Before redistributing a build, review `META-INF/nvidia/NOTICE.txt` and the accompanying NVIDIA license files inside the JAR. NVIDIA's RTX SDK license also requires prior notification to NVIDIA before a commercial release that incorporates DLSS or NGX.

This software uses NVIDIA Streamline and NVIDIA DLSS. BlockFrame DLSS is an independent community project and is not affiliated with, sponsored by or endorsed by NVIDIA, Mojang Studios, Microsoft, NeoForge, CaffeineMC or CurseForge. NVIDIA, NVIDIA DLSS, NVIDIA RTX and GeForce RTX are trademarks or registered trademarks of NVIDIA Corporation; other product names and trademarks belong to their respective owners.

