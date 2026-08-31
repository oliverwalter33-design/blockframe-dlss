# BlockFrame DLSS 0.3.18 – Changelog (English)

## Removed

- Removed forced 16x anisotropic filtering. BlockFrame now preserves the original or user-selected anisotropy within the device limit.
- Removed the previous Cutout-terrain exclusion from the DLSS mip correction.
- Removed global per-frame Streamline tagging and the volatile `eOnlyValidNow` clone path.
- Replaced the R8_UNORM history mask with an RGBA8_UNORM mask fully supported by Streamline 2.12.
- Removed duplicate BlockFrame native-terrain pipeline registration when Sodium is active.
- Removed diagnostic image bindings and diagnostic branches from the normal release motion shader.
- Removed the raw full-detail Bricks distance extension and replaced it with an efficient far-mesh path.
- Stale sampler and cache generations are now discarded completely during size, mode and resource changes.

## Fixed

- Terrain mip bias now uses the actual render and output dimensions: original sampler bias plus `log2(renderWidth / outputWidth) - 1`.
- Arbitrary window sizes, live resizing, minimize/restore and DLSS/DLAA mode changes now always use the correct viewport, render size and mip bias.
- Vulkan samplers are cloned completely. Filters, address modes, LOD limits, comparison mode, border color and anisotropy are preserved; only the calculated `mipLodBias` is adjusted.
- Distant Solid and alpha-tested Cutout terrain detail now appears correctly under DLSS/DLAA for the supported Minecraft, Sodium, Milkshade and BlockFrame pipelines.
- Local-player disocclusion history handling was corrected. A tightly scoped `BiasCurrentColorHint` mask reduces trails on moving body parts, especially feet and shoes.
- Streamline resources are supplied locally with `eValidUntilEvaluate`, eliminating the faulty volatile input-clone path.
- Vulkan buffer-device-address promotion and `shaderStorageImageWriteWithoutFormat` negotiation were corrected.
- Release/diagnostic motion descriptor accounting and GPU-safe sampler/cache-generation cleanup were corrected.
- Distant Bricks `composite_block` geometry no longer disappears at the normal 64-block block-entity-renderer limit. A cached far mesh now continues through Minecraft's effective render distance.
- Roofs, facades, bars and other structures built from Bricks microblocks remain visible across longer distances.

## Added

- An exact block-atlas and pipeline allowlist containing five full Solid-terrain IDs and four full Cutout-terrain IDs.
- A device- and generation-scoped sampler cache keyed by render/output dimensions, DLSS mode, preset, reload epoch and device generation.
- An RGBA8_UNORM `BiasCurrentColorHint` texture for local-player disocclusions, including Vulkan/Streamline lifecycle and resize support.
- Previous-frame projected rectangles for articulated player parts and a world-pixel-only history-rejection gate.
- An exactly pinned Bricks 1.0.1 compatibility module for the composite-block renderer.
- Cached Bricks far meshes with component-safe greedy merging. Material, silhouette, direction, texture-atlas region, UV range, UV rotation and translucency are preserved.
- Frame-wide Bricks batching by texture and translucency, camera-relative placement, immutable NeoForge custom-geometry submissions and far-to-near translucent-quad ordering.
- Automatic far-mesh cache invalidation whenever Bricks updates its visible-face data.
- Expanded artifact, UV, lifecycle, resize, shader, Vulkan and regression tests.

## Compatibility

- Minecraft `26.2`
- NeoForge `26.2.0.57`
- Sodium `0.9.1+mc26.2`, including NEAREST and RGSS paths
- Milkshade Solid and Cutout terrain pipelines
- Bricks `1.0.1` composite-block renderer
- NVIDIA DLSS `310.7.0` and Streamline `2.12.0`

