# BlockFrame native Vulkan terrain backend

Status: `EXCLUSIVE_NATIVE_WORLD_FACTORY_BLOCKED_BY_WORLD_ROUTING_AND_TYPED_FRAME_OUTPUT_OWNERSHIP`

Architecture revision: 6  
Repository date: 2026-07-30  
Production default: `MOJANG_REFERENCE`  
Experimental value: `terrainBackend=native-experimental` (restart required)

This is the single binding architecture for the native Vulkan terrain
backend. It replaces the cancelled hybrid Phase-2A.1 direction. BlockFrame
does not adopt finalized Mojang `MeshData`, does not suppress isolated
`UberGpuBuffer.addAllocation` callbacks and does not keep Mojang and
BlockFrame GPU terrain renderers warm at the same time.

Foundation B connects a real post-model-reload census, immutable real section
snapshots, Minecraft/NeoForge model tessellation into BlockFrame-owned
Solid/Cutout payloads, a bounded compiler job system, device-local geometry
pages, a persistently mapped staging pool, batched copies, completion-gated
publication and generation-safe retirement. The title-only Vulkan smoke
compiled and uploaded an actual model fixture without activating a native
world renderer.

Renderer C implements the one shared quad index table, persistent
device-local GPU Scene, conservative GPU frustum culling, indirect-count
command generation, V2 Solid/Cutout pipelines and a native submission owner.
Phase 2A.1F adds the single backend-neutral
`ExclusiveNativeWorldResourceFactory`, an irreversible publish boundary,
retryable pre-publish and fence-retirement cleanup, post-publish quarantine,
fresh native world/resource revalidation, a complete logical frame-output
ABI, exact collision-free Surface IDs and opt-in ownership counters at the
real BlockFrame and Mojang work entry points.

Those contracts deliberately do not manufacture a concrete native
`LevelRenderer` owner. The current production preflight attests exclusive
world routing, stored frame outputs/typed captures and the controlled fixture
as unavailable. Therefore both the default configuration and the current
`native-experimental` request create only Mojang terrain resources. This is
fail-closed behavior, not a partial promotion or a completed live factory.

The detailed Foundation-B evidence is in
`FOUNDATION_B_GEOMETRY_OWNER_UPLOAD_EVIDENCE.md`.
Phase `RENDERER_C_PERSISTENT_GPU_SCENE_FRUSTUM_INDIRECT_V1` implementation,
benchmark and blocked-live-gate evidence is in
`RENDERER_C_PERSISTENT_GPU_SCENE_FRUSTUM_INDIRECT_V1_EVIDENCE.md`.
The Phase-2A.1F owner/output audit and exact blocker are in
`PHASE_2A_1F_EXCLUSIVE_NATIVE_WORLD_RESOURCE_FACTORY_V1_EVIDENCE.md`.

## Superseded paths and immutable NO_GO evidence

`PHASE_2A_1E_GPU_VISIBILITY_COMPACTION_V1` is cancelled.

| Experiment | Result | SHA-256 |
| --- | --- | --- |
| metadata cache V12 | no draw reduction and no render-main CPU benefit | `7c5d7329b2a0366ff3ebfc39b3494bdb09ed1c755ef1d99e4727f7160d09dc88` |
| GPU scene V16 canonical gate | CPU-visible scan remained; Presented FPS `-5.1857%`, p50 `+3.9602%`, p95 `+10.5884%`; image hashes differed | `7bd0673333eb8800ea44f2755a06248cd41c283f419c8a4aea1ac8f76575366a` |
| canonical V16 report | `GPU_SCENE_PERFORMANCE_NO_GO` | `bc209ff6bef25190c3d0d31f654787d83faa3eaec840ef36dca250746c5f7d09` |
| earlier V14 experiment | owner/indirect experiment, NO_GO | `e2c1e3bc63797189b54dda419f877a7c79dc27b0f966c1575437630b4572ea3d` |

The four V16 MixinExtras wrappers remain source evidence but are absent from
`nvidia_dlss.mixins.json`:

- `OpaqueSolidGpuSceneLevelRendererMixin`
- `OpaqueSolidGpuSceneRenderGroupMixin`
- `OpaqueSolidGpuSceneRenderSectionMixin`
- `OpaqueSolidGpuSceneUberBufferMixin`

They cannot transform production classes, so no
`Operation.call(Object...)` wrapper from that experiment remains in the
normal terrain warm path. `OpaqueSolidGpuScenePolicy.ownerHooksEnabled()` is
unconditionally false, and the archived negotiator/runtime are not registered
by bootstrap or mod entry point.

The finalized Mojang payload remains documented NO_GO evidence:

- `SectionCompiler` merges block, fluid and NeoForge additional geometry by
  layer before producing `MeshData`;
- `SectionRenderDispatcher` closes that payload after staging;
- Mojang publication depends on both upload callbacks, `checkSectionMesh`,
  `setSectionMesh`, replacement and `copyLock`;
- suppressing one allocation callback cannot transfer that lifecycle safely.

That boundary is not used by the new backend for productive ownership.

## Exact Mojang source contract

The source-contract tests are pinned to:

| Artifact | SHA-256 |
| --- | --- |
| `build/moddev/artifacts/minecraft-patched-26.2.0.13-beta-sources.jar` | `1e30221d789a4b4b75ea9497319c8998a87463afefb542cd28139324b314390c` |
| `build/moddev/artifacts/minecraft-patched-26.2.0.13-beta.jar` | `11b1d7d5b2e9d8bae2ad9c8276fbe1311e4b93a08aff8e29ae522d559a1ebf6a` |

`Minecraft` constructs `LevelRenderer` after `GameRenderer`.
`LevelRenderer.invalidateCompiledGeometry` then constructs Mojang's
`SectionCompiler`, `SectionRenderDispatcher` and `ViewArea`.

`LevelRenderer` construction itself creates no section resources and occurs
before the completed model census. The restart-required configuration is
latched once, but the atomic backend selection now occurs at the first
post-census `LevelRenderer.invalidateCompiledGeometry` boundary:

1. `LevelExtractor.setLevel` sets the level and requests reset/invalidation.
2. `LevelExtractor.extract` resets render data and calls
   `LevelRenderer.invalidateCompiledGeometry`.
3. `LevelRendererMixin.blockframe$createSelectedTerrainWorldResources`
   asks `NativeTerrainBackendFoundation.selectAtFirstWorldResourceBoundary`.
4. A typed Mojang selection receives a reference-only permit and calls the
   original exactly once.
5. A Native selection is forbidden from entering that reference method.

The removed `demoteUnavailableNativeFactory` path can no longer briefly
select Native and then silently invoke Mojang. A future complete Native owner
must use `ExclusiveNativeWorldResourceFactory`; post-publish failures cannot
replay Mojang in the same frame.

## Former final-payload blockers

| Former blocker | Foundation-B resolution | Remaining work |
| --- | --- | --- |
| `PROVENANCE_BLOCKED` | `MinecraftTerrainAssetCensusAdapter`, `MinecraftTerrainSectionSnapshot`, `MinecraftTerrainModelAdapter` and `BlockFrameSectionCompiler` operate before any mixed Mojang payload. Real Solid/Cutout quads retain blockstate/model, RenderType, sprite/material, shader ABI, light, AO, tint, UV, exact normal, bounds and generation provenance. | Dynamic BlockEntity-backed models, unknown custom tint resolvers and unproven NeoForge extra geometry remain typed unsupported. |
| `PUBLICATION_BLOCKED` | `NativeTerrainGeometryOwner` owns budgeted device-local pages and staging, records copies into Mojang's existing command stream, waits only through zero-timeout completion polling, atomically publishes complete batches and retires only after GPU completion. The shared index table, persistent Scene and indirect buffers use that same owner. | No remaining device/VMA/staging ownership ambiguity; live world publication still awaits the exclusive native factory. |
| `CAPABILITY_SUBMISSION_BLOCKED` | `NativeTerrainDeviceCapabilityNegotiator` is independent of V16 and runs before device creation. Renderer C implements compute visibility, indirect-count generation, render-pass encoding and no-replay submission while preserving Mojang device/queue/allocator ownership. | Logical exclusive-factory and frame-output contracts now exist. Concrete `LevelRenderer`/`LevelExtractor` routing, MRT resource ownership, typed capture and actual main-pass draw/image gates remain missing. |

The late-hook architectural dead end is removed. Compatibility and renderer
work remain, so the native backend cannot activate.

## One mutually exclusive backend

| Value | Requested backend | Current production result |
| --- | --- | --- |
| absent or `mojang` | `MOJANG_REFERENCE` | Mojang, without creating native world terrain resources |
| `native-experimental` | `BLOCKFRAME_NATIVE_EXPERIMENTAL` | Mojang, with typed rejection for incomplete exclusive world routing, frame-output/capture ABI, fixture and shader/material preflight |
| any other value | invalid | Mojang with `CONFIGURATION_INVALID` evidence |

The choice is restart-bound and sealed once per process before world terrain
resources. There is no `auto` value and no in-process promotion. A native
selection requires all of:

- Vulkan and a generation-matched capability attestation;
- a complete asset/RenderType census for every category used by the active
  world and mod/resource profile;
- matching `TerrainMeshProducerABI` version and supported vertex/index ABI;
- complete material, shader and render-pass ABI;
- sufficient RAM and VRAM admission budgets;
- Safe Start inactive and no native-backend quarantine;
- complete GPU Scene, visibility, command and submission ownership.

The selector issues one opaque creation permit. Failed preflight creates no
native world object. Failed native construction may fall back to Mojang
before world entry only after cleanup attests zero native owners, leases,
jobs and publications. Otherwise selection enters
`QUARANTINED_CLEANUP_REQUIRED`.

This explicitly rejects a per-section hybrid between two active GPU terrain
renderers.

## Device capability negotiation before `vkCreateDevice`

`NativeTerrainDeviceCapabilityNegotiator` is called through
`DlssBootstrap.configureDeviceCapabilities` at the existing
`VulkanBackendMixin` hook immediately before Mojang's private
`VulkanBackend.createDevice(...)` helper.

Mojang retains the device, queue, allocator, native stack, feature set,
extension set and final `vkCreateDevice` call. The negotiator:

- queries Vulkan 1.2 `VkPhysicalDeviceFeatures2` with Vulkan 1.1/1.2 feature
  structures in a stack-lived `pNext` chain;
- queries properties, queue topology and all required limits;
- requires compute, storage/indirect usage, `multiDrawIndirect`,
  `drawIndirectCount`, `shaderDrawParameters` and the portable descriptor
  indexing baseline;
- records buffer-device-address support but does not require or enable it;
- requests no extension name for Vulkan-1.2-core baseline features;
- adds supported feature tokens transactionally without duplicates;
- removes only its own additions if the transaction fails;
- publishes a device-generation-bound immutable attestation;
- invalidates the attestation on device replacement or close.

If native is not requested, the coordinator avoids the extra native probe.
OpenGL never reaches the Vulkan hook and requests nothing. Query, feature,
limit, duplicate or mutation failure preserves Mojang device creation.
High-end extensions remain unrequested.

## Permanent ABI and publication contracts

The single backend-neutral ABI family remains:

- `TerrainMeshProducerABI`
- `TerrainGeometryOwnershipTransaction`
- `TerrainSubmissionBoundary`

No competing foundation or late Mojang-payload adapter was created.
`TerrainGeometryOwnershipTransaction.PayloadOwnershipPermit` is explicitly
not authority to suppress a Mojang upload.

### Exclusive world factory contract

`ExclusiveNativeWorldResourceFactory` is the one transaction owner for a
future concrete Native world owner. It binds a single-use selector permit to
one owner generation and enforces:

- constructor and preparation remain private before atomic `publish`;
- complete pre-publish cleanup is required before a Mojang rebuild;
- incomplete pre-publish cleanup retains the original permit and owner for
  bounded retry;
- the call to `publish` is the irreversible boundary, including an uncertain
  exception from that call;
- every post-publish failure quarantines Native, pauses ownership progress and
  forbids same-frame or in-place Mojang replay;
- normal fence-delayed retirement is retryable without being mislabeled as a
  permanent backend fault;
- Reload and world switch require fresh resource/world generations;
- only a previously active Native permit may request Native revalidation, so
  a process that selected Mojang cannot promote in-process;
- Close cannot declare success while CPU bytes, GPU bytes or resources remain
  owned.

The factory is backend-neutral and creates no second device, allocator,
queue, geometry owner or scheduler. It is tested with synthetic owner
transactions, but no concrete Minecraft/Vulkan `WorldResourceOwner`
implementation currently satisfies the production attestations.

### Frame/output ABI V1

`NativeTerrainFrameOutputAbi.VERSION == 1` defines the logical all-or-nothing
frame publication contract:

| Semantic | Format/contract |
| --- | --- |
| Color | stored `RGBA8_UNORM`, post-lighting/fog LDR |
| Depth | stored `D32_FLOAT`, reversed-Z, clear `0.0` |
| Motion | stored `RG16_FLOAT`, current-to-previous unjittered output pixels, top-left origin, invalid sentinel `65504` |
| World normal | stored `RGBA16_SNORM`, normalized world XYZ, W=1 valid and cleared W=0 background |
| Camera normal | exact derived inverse-transpose semantic; no duplicate full-resolution attachment |
| Surface | stored `R32_UINT`, zero invalid/background |
| Exposure | typed `AUTO_TONEMAPPED_LDR`; no invented numeric exposure |
| Temporal metadata | current/previous unjittered matrices, output extent, `CameraBlockPos=floor(camera)` plus `CameraOffset=floor(camera)-camera` in `[-1,0]`, current/previous jitter, reset epoch and frame/scene/world/renderer/resource/device generations |

Color/Depth/WorldNormal/Surface must become valid atomically after the terrain
pass. Motion becomes valid only after its real producer completes. Temporal
history commits only when the complete frame publishes; an unpublished or
failed frame cannot replace prior history. Teleport, Resize, Reload, world,
renderer, device and scene-generation changes carry typed reset reasons.

`NativeTerrainSurfaceIdRegistry` assigns collision-free positive IDs to the
full `MaterialBinding + ShaderContract` key. IDs are generation- and
first-observation-local; cross-process captures must normalize through the
captured registry table rather than compare raw integers. Solid retains normal
alpha, while accepted Cutout uses threshold `0.5` and marker `254/255`.

This ABI currently has no GPU resource owner. Mojang's chunk pass binds one
Color plus one Depth target, the Native fragment shader writes only Color,
and the existing debug capture is RGBA8-only. Accordingly no stored Motion,
Normal or Surface output is marked production-ready.

### Vertex ABI V2

The first real census found explicit NeoForge baked normals on 39,677 of
47,035 observed model/render-type combinations. Preserving those normals
required a proven ABI extension; retaining the earlier 28-byte-only payload
would have been an incorrect approximation.

`TerrainMeshProducerABI.VERSION == 2` defines a 32-byte vertex:

| Offset | Bytes | Field | Contract |
| --- | ---: | --- | --- |
| 0 | 12 | position | exact finite `float x/y/z`, including baked model rotation/offset |
| 12 | 4 | color | packed baked color containing model color, tint and AO |
| 16 | 8 | atlas UV | exact sprite-atlas `float u/v` |
| 24 | 4 | light | exact packed block/sky lightmap value |
| 28 | 4 | normal | exact NeoForge packed SNORM8x3 normal; when the source explicitly marks normal unspecified, the documented baked-quad face normal is packed |

No value is fabricated. A zero/unresolved or malformed explicit normal is
rejected. Existing Solid/Cutout contracts require the explicit-normal
semantic. Color, Depth, Motion, Normal, Material and generation/reset output
requirements remain part of the future shader contract; Foundation B makes
no image-output claim.

The producer currently uses `SHARED_SEQUENTIAL_QUADS` with UINT16 indices.
Every descriptor is bounded to one four-vertex/six-index quad, so base plus
maximum local index cannot exceed 65,535. A 16,385-quad fixture proves that
65,540 vertices do not wrap because descriptors split before that boundary.
Renderer C may merge ranges only with the same proven bound or an explicit
UINT32 ABI; it may not reduce geometry.

## Real post-reload census

`NativeTerrainModelManagerMixin` invalidates census/material generations at
`ModelManager.apply` entry and captures at successful return, after model and
resource baking. Capture is generation-bound, read-only, never per-frame and
does not load a world.

`MinecraftTerrainAssetCensusAdapter` enumerates:

- every built-in registered blockstate and its `RenderShape`;
- all baked model classes, including every `WeightedVariants` child and every
  state-selected `MultiPartModel` child through read-only accessors;
- possible quad RenderTypes, vertex formats and sprites/material identities;
- AO, tint, lighting, explicit-normal and ModelData requirements;
- all registered fluid states and fluid-lane requirements;
- active NeoForge additional section geometry as a typed global adapter gap;
- unsupported custom render/shader/tint/model contracts with stable reasons.

Stable summaries exist by blockstate, model class, RenderType and mod
namespace. Reasons include:

- `SUPPORTED_SOLID`
- `SUPPORTED_CUTOUT`
- `NO_STATIC_GEOMETRY`
- `REQUIRES_TRANSLUCENT_LANE`
- `REQUIRES_FLUID_LANE`
- `REQUIRES_DYNAMIC_MODEL_DATA`
- `REQUIRES_MOD_EXTRA_ADAPTER`
- `REQUIRES_CUSTOM_TINT_ADAPTER`
- `UNSUPPORTED_VERTEX_FORMAT`
- `UNSUPPORTED_RENDER_TYPE`
- `UNSUPPORTED_SHADER_ABI`

The successful Vulkan title-smoke's active profile observed:

| Reason | Observations |
| --- | ---: |
| `SUPPORTED_SOLID` | 32,740 |
| `SUPPORTED_CUTOUT` | 6,937 |
| `REQUIRES_TRANSLUCENT_LANE` | 5,976 |
| `NO_STATIC_GEOMETRY` | 1,377 |
| `REQUIRES_FLUID_LANE` | 4 |
| `REQUIRES_MOD_EXTRA_ADAPTER` | 1 |
| Total | 47,035 |

This covered 32,366 blockstates and five registered fluid states in the
tested vanilla/NeoForge/Voxellift development profile. It is not evidence
for every possible foreign-mod or resource-pack profile. Unknown assets keep
the whole production backend on Mojang.

## Immutable real section snapshot

`MinecraftTerrainSectionSnapshot` captures on the permitted world thread and
then provides a fixed immutable `BlockAndTintGetter` to workers. It copies an
18 x 18 x 18 volume: the 16-cube section plus a one-block halo on every side.
The snapshot includes:

- blockstates and fluidstates;
- ModelData values captured at snapshot time;
- block and sky light;
- grass, foliage, dry-foliage and water tint values;
- cardinal lighting and level height contract;
- section bounds/positions;
- device, renderer, world, resource, producer and section generations;
- census/material generation digest and deterministic model seed contract;
- one exact bounded RAM lease.

After capture, workers query no live Level, chunk or BlockEntity. Any query
outside the halo, unknown tint resolver or BlockEntity request sets an
unsupported flag and rejects the entire compiled batch. A model with proven
data demand beyond one block must first extend the snapshot contract; it is
not approximated.

Capture uses indexed arrays and mutable cursor positions only during capture,
avoiding boxed `BlockPos` collections in the compiler hot path. Reload,
world/device generation change, cancellation or stale digest prevents
publication.

## Real Minecraft/NeoForge model adapter

`MinecraftTerrainModelAdapter` uses Minecraft/NeoForge model contracts while
writing only BlockFrame-owned builders and payloads:

- `BlockStateModelSet` and `ModelBlockRenderer`;
- deterministic `BlockState.getSeed(position)`;
- model RenderType selection before each quad write;
- exact face culling against the immutable halo;
- model rotation/offset and blockstate variants;
- AO, tint, light, emissive light where exposed by the used contract;
- exact sprite/material identity and explicit packed normal;
- strict Solid/Cutout lane separation.

The real smoke fixture includes stone with a halo neighbor, smooth-stone
slab, oak stairs, oak fence, cobblestone wall, iron bars, oak leaves,
multi-texture bookshelf and tinted cross-plant. It produced 58 quads and
7,424 vertex bytes with deterministic provenance.

Unknown custom geometry, mutable BlockEntity-dependent geometry, unknown tint
resolver, unsupported shader/vertex format, Fluid, Translucent or
`AddSectionGeometryEvent` output fails closed. NeoForge extra geometry is not
treated as static merely because a callback exists: its raw vertices do not
yet carry the complete material/provenance ABI required by this backend.

## Section compiler and lifecycle

`BlockFrameSectionCompiler` owns all builders, output bytes and descriptors.
It never creates or consumes Mojang `MeshData`. Solid and Cutout use separate
channels from the first byte. Fluid, Translucent, Mod Extra and Unsupported
are classified before writing and cannot leak into a supported payload.

The lifecycle is:

```text
SNAPSHOT -> COMPILING -> COMPILED -> UPLOADING -> PUBLISHED -> ACTIVE
                                                          |
                                                          v
                            RETIRED <- RETIRING <----------+
                                         |
                                         v
                                  CLEANUP_RETRY
```

Control/terminal states include `CANCELLED`, `QUARANTINED` and `CLOSED`.
The state machine carries device, renderer, world, resource, producer and
section generations. It covers Dirty restart, section removal, reload,
resize, world/device replacement, budget/upload failure, cleanup retry and
shutdown. Failed compile/upload never publishes partial geometry.

The global fallback contract intentionally differs from the older same-frame
complete-renderer fallback:

- before activation, any error aborts native start and constructs Mojang;
- after future native activation, a severe backend/device failure pauses
  rendering, quarantines Native, retires safely and rebuilds Mojang or asks
  for safe restart;
- no global same-frame Mojang replay and no permanently warm duplicate GPU
  renderer;
- a local future compile failure retries or uses a native reference build.

## Productive job system and central frame budget

`NativeTerrainJobSystem` is the one bounded compiler scheduler:

- platform threads only;
- a conservative topology estimate when physical-core data is unavailable;
- at least two foreground physical cores reserved where available;
- visible, near and far priority deques;
- complete-job work stealing only;
- bounded queues and generation cancellation;
- monitor/semaphore sleeping, no busy wait;
- no affinity or priority manipulation;
- no Vulkan API exposed to compiler workers;
- joined close with no lingering pool.

`FrameBudgetController` consumes CPU and GPU frame time, p95/p99 pressure,
snapshot/compile/upload backlogs, RAM/VRAM headroom and average job costs. It
only limits worker admission, simultaneous work and per-frame publication.
It never reduces models, quads, content, view distance or quality. SMT
workers require known topology, real excess backlog and available frame
budget.

The real-model smoke reported physical/logical worker limits 6/6 and
exercised one actual compiler worker. Renderer C separately ran three fresh
JVMs each at 1/2/4/6 workers for the BlockFrame V2 payload encoder. Its
median throughput was 16,308.193 / 19,664.994 / 39,365.652 / 39,249.318
sections/s. That matrix excludes Minecraft model tessellation and the client
thread, so real-model scaling and frame impact remain unproven.

## Device-local GeometryOwner, staging and upload

`NativeTerrainGeometryOwner` borrows the current Mojang `VulkanDevice`,
allocator-facing buffer factory, command encoder and submit cadence. It
creates no second VkDevice, global VMA owner, queue, thread or per-section
submission.

It owns:

- typed Vertex/Index device-local pages;
- on-demand page sizing derived from observed p95 payload bytes, alignment,
  configured minimum/maximum page bounds and Vulkan buffer limits;
- stable aligned suballocations with requested/committed/used/peak/free,
  largest-free-range and external-fragmentation accounting;
- a bounded, persistently mapped staging buffer under a RAM lease;
- variable aligned staging suballocation and batched buffer copies;
- one bounded in-flight batch with FrameBudgetController backpressure;
- nonblocking zero-timeout fence polling;
- atomic publish only after the existing submission completes;
- CPU payload release only after copied bytes are no longer needed;
- device-local range reuse only after all declared GPU uses complete;
- rollback on partial allocation/copy/fence/generation failure;
- cleanup ownership retention and retry if close/accounting fails.

The Mojang mapped buffer contract used here is host-coherent; therefore the
adapter needs no extra non-coherent flush. A separate transfer queue is not
used. V1 performs no compaction and never moves a live range.

The smoke uploaded 7,424 bytes through a 7,440-byte staging pool into two
typed pages with 12,288 committed and 7,424 used device-local bytes, zero
reported external fragmentation, one upload and one retirement.

The producer has no per-section index payload because it uses
`SHARED_SEQUENTIAL_QUADS`. Renderer C now owns one 196,608-B UINT16 table
through the same GeometryOwner. It uses Mojang's exact quad pattern, covers
16,384 quads and splits before vertex index wrap. `BufferKind.INDEX` and an
optional explicitly sized UINT32 shared region remain typed by the same
page/retirement owner.

## Renderer target

The single final module graph remains:

```text
BlockFrameSectionCompiler -> TerrainMeshProducerABI
             |
             v
TerrainGeometryOwner -> PersistentTerrainGpuScene
             |                    |
             v                    v
TerrainMaterialSystem      GpuTerrainVisibility
             \                    /
              v                  v
             GpuTerrainCommandGeneration
                         |
                         v
              TerrainSubmissionOwner
```

For `NATIVE_SOLID_CUTOUT`, the eventual render-thread warm path may only:

1. publish camera/frustum/jitter/frame/generation constants;
2. encode bounded dirty-scene and visibility compute work;
3. issue bounded material/pipeline-bucket indirect-count draws in Mojang's
   existing Vulkan command stream.

It may not scan all sections/layers, build CPU draw records, read back draw
counts, sort Solid/Cutout, duplicate geometry, submit content twice, allocate
per frame or create an unbounded pool.

Solid may become a conservative HZB occluder only after the correct depth
foundation exists. Cutout and uncertain/new entries draw conservatively.
No LOD, geometry simplification, content or quality reduction is permitted.

## Verification status

Offline coverage includes:

- supported/unsupported capability probes, false features, all limit classes,
  duplicate features, transactional rollback, `pNext`, device replacement
  and close;
- valid/invalid/Safe-Start/quarantine/RAM/VRAM backend preflights;
- exact post-reload census ordering and exhaustive weighted/multipart child
  enumeration;
- immutable snapshot thread, halo, RAM, stale-generation and unsupported
  live-data-query contracts;
- real Solid/Cutout model tessellation plus typed unsupported categories;
- V2 field preservation, malformed normal rejection and high-vertex UINT16
  no-wrap splitting;
- bounded jobs, priority, stealing, cancellation, SMT gate and clean close;
- staging/VRAM low-budget, overflow, copy/fence failure, reload/device
  generation change, publish, retirement and cleanup retry;
- persistent scene stable slots, bounded capacity, dirty update, rollback
  and retirement;
- exact Vulkan frustum planes, conservative first-frame visibility,
  compute-generated indirect commands/counts and no CPU readback;
- compute and expanded graphics SPIR-V compilation;
- exact V2 vertex offsets/formats, reversed depth, culling and Cutout alpha;
- exclusive factory constructor/prepare/publish/retirement transactions,
  pre-publish cleanup retry, post-publish quarantine and no-replay;
- restart-bound post-census selection with a reference-only Mojang permit and
  no Native-to-Mojang demotion hidden inside that boundary;
- complete logical frame-output masks, reset/history generations, typed
  exposure, matrix/normal validation and collision-free Surface IDs;
- opt-in ownership evidence at real BlockFrame compiler/upload/scene/
  compute/submission success points and Mojang compiler/Solid-Cutout heap/
  upload/OPAQUE-submission entry points;
- the scheduler signal/steal regression plus fork-isolated 1/2/4/6-worker
  payload-encoder scaling;
- absence of archived V16 wrappers from production mixin registration.

Live gates:

- Vulkan title-only real census/compile/upload/publish/retire/close:
  `PASSED`
- Vulkan production backend selection remained Mojang: `PASSED`
- Vulkan log contained no logged VUID, validation, Device Loss, UAF or leak
  error: `PASSED_AS_LOG_SCAN` (not a universal proof that every validation
  layer was enabled)
- Minecraft controlled world activation: `NOT_RUN`
- Vulkan Renderer-C compute/indirect execution: `NOT_RUN`
- native Solid/Cutout draw submission: `NOT_RUN`
- exclusive Native world owner active: `NOT_RUN`
- Mojang Solid/Cutout work proven zero under Native ownership: `NOT_RUN`
- typed Color/Depth/Motion/Normal/Surface A/B capture: `NOT_RUN`
- F3+t with native world resources: `NOT_RUN`
- world Save-Title-Reload: `NOT_RUN`
- live in-process device recreation: `NOT_RUN`
- DTC and Greenfield: `NOT_RUN`
- image parity: `NOT_RUN`
- Sodium comparison: `NOT_RUN`
- Minecraft FPS/frame-time comparison: `NOT_RUN`

No image-parity, FPS, Sodium or market-leadership claim follows from the
title-screen smoke or compiler measurements.

## Exact next subphase

The smallest safe next implementation is a concrete
`NativeTerrainWorldPreparationOwner` under the existing
`ExclusiveNativeWorldResourceFactory`. For one controlled fixture it must:

1. enumerate the initial loaded sections without using Mojang's
   `SectionCompiler`;
2. derive a generation-bound world-used-asset attestation from immutable
   snapshots or the exact controlled-fixture manifest and intersect it with
   the complete global census; the general mod/resource profile remains
   fail-closed and is never weakened to ignore Translucent, Fluid or
   Mod-Extra assets;
3. own snapshot jobs, compilation, GeometryOwner upload and GPU-Scene
   publication through the existing owners;
4. wait asynchronously for the complete initial upload/scene generation;
5. expose one atomic ready-to-publish barrier and exact cleanup accounting;
6. remain unpublished until `LevelExtractor` dirty/frustum updates and
   `LevelRenderer` camera/reposition, prepare/compile/upload/occlusion and
   render routing can all target the Native owner.

Suppressing only `invalidateCompiledGeometry` is invalid: Mojang's
`repositionCamera`, `render`, `prepareChunkRenders`, `compileSections`,
dispatcher upload and occlusion graph still dereference `viewArea` and
`sectionRenderDispatcher` or perform the forbidden CPU work. Adding Native
draws while retaining them would recreate the rejected hybrid.

After that prepublication owner/routing gate, a
`NativeTerrainFrameOutputResources` child owner must add and capability-gate
the actual Normal/Surface MRTs, shared Motion/history ownership and typed raw
captures. Only then can the controlled Vulkan fixture, no-Mojang-work proof,
image ABI and lifecycle matrix execute.

Translucent/Fluid/Mod-Extra compatibility cannot begin until the exclusive
Solid/Cutout fixture passes. Those lanes can reuse the existing ownership
architecture, but they cannot be validated on top of a hybrid or non-drawing
backend.

Dynamic BlockEntity-backed models and custom tint/shader contracts still
require explicit immutable adapters. None may be approximated or silently
dropped.

The exact phase status is
`EXCLUSIVE_NATIVE_WORLD_FACTORY_BLOCKED_BY_WORLD_ROUTING_AND_TYPED_FRAME_OUTPUT_OWNERSHIP`,
not `EXCLUSIVE_NATIVE_WORLD_FACTORY_ACTIVE_SOLID_CUTOUT_FIXTURE_PASSED`,
`RENDERER_C_GPU_SCENE_SUBMISSION_READY`,
`RENDERER_C_READY_WITH_IMAGE_GAPS`, `NATIVE_TERRAIN_GO` or a performance
candidate. The transaction/ABI/evidence foundations and Renderer-C modules
are implemented and tested offline, but the concrete world and frame-output
owners remain non-activatable.
