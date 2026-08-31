# Changelog

## 0.3.16-neoforge-26.2 - 2026-08-05

- Promoted the user-approved dev.5 renderer to the 0.3.16 release without
  changing its DLAA, DLSS, temporal-reprojection, third-person or mipmap
  behavior.
- Kept the graphics-menu selector for Off, Quality, Balanced, Performance,
  DLAA and Ultra (4K). Its visible name is now the compact `DLSS`, preventing
  overlap at smaller GUI scales. The tooltip still states the selected mode
  and the bundled NVIDIA DLSS 310.7.0 / Streamline 2.12.0 runtime versions.

- Added a camera-relative static-world reprojection transform. Current and
  previous camera positions are differenced in double precision before the
  small displacement is converted to float, avoiding large-coordinate motion
  collapse without changing the dev.4 articulated-avatar path.
- Publish projection, view rotation and camera-position history atomically
  only after a successful Streamline evaluation. Scratch and allocation
  fallback paths use the same transform and clear their histories together on
  reset, quarantine and device teardown.
- Made RG16F motion writes storage-safe without sentinels, NaN/Infinity or
  component-wise clamping. Exceptional finite vectors are scaled as a whole,
  preserving their direction and recording a separate diagnostic class.
- Kept the own-pixel motion/hint contract: no neighbouring color, depth or
  cutout pixel is borrowed; no BiasCurrentColorHint,
  InvalidDepthMotionHint, sharpening or NIS workaround was added.
- Moved capture, audit, Tracy, GPU-breadcrumb and debug-label callbacks behind
  the single explicit developer-diagnostics startup gate. Normal builds no
  longer apply their diagnostic mixins, while frame-budget, device-close and
  Streamline shutdown lifecycle hooks remain active.
- Compile separate release and developer motion shaders. The release SPIR-V
  omits the three debug image bindings and their per-pixel branches, so normal
  resource sets no longer allocate diagnostic images or descriptor dummies.
  Removed the obsolete `devBiasHint` and `devInvalidDepthMotionHint` run
  switches.
- Restored the complete source suite and the transform benchmark after the
  camera-relative change. The internal benchmark compares equivalent
  reprojection math and retains a zero-allocation gate for the normal scratch
  path. These source/CPU gates are not a claim that the live visual acceptance
  matrix has passed.

## 0.3.15-neoforge-26.2 - 2026-07-30

- Query and deep-copy the authoritative DLSS and NIS Vulkan requirements from
  the pinned Streamline 2.12.0 runtime immediately after `slInit`. Instance
  extensions, device extensions and the supported Vulkan 1.2 feature bits are
  negotiated from that snapshot. Unknown flags/features, unmet OS/driver
  minimums, extra queue requirements, malformed payloads and mismatched
  required resource tags fail closed before device connection.
- Corrected Vulkan promotion handling: `VK_KHR_buffer_device_address` may be
  satisfied by Vulkan 1.2 core, while the distinct
  `VK_EXT_buffer_device_address` requirement must still be advertised and
  enabled explicitly.
- Isolated Streamline evaluation from Mojang command-buffer state. DLSS and
  optional NIS record into separate transient command buffers with explicit
  barriers and ordered submission. JNI returns their results separately, so a
  successful DLSS result remains usable and visible when optional NIS
  sharpening fails; failed or partial buffers are never submitted.
- Split synchronous operation results from asynchronous Streamline diagnostics
  and synchronized both native message channels.
- Made `slFreeResources` mandatory, track successful DLSS/NIS viewport use
  independently and require confirmed cleanup before resize/resource
  replacement. Cleanup and shutdown uncertainty remain fail-closed.
- Removed silent partial motion coverage above 64 moving objects. The complete
  observed set is counted, the encoded set is cleared on overflow and the same
  frame is explicitly history-reset instead of presenting incomplete object
  motion as valid.
- Added temporal resets for world login/logout, player clone/respawn,
  authoritative player/entity teleports, camera-entity/death transitions and
  discontinuities in the effective unjittered world projection. Smooth
  sub-threshold camera/FOV changes remain history-preserving.
- Require an actually acquired Vulkan presentation surface before entering
  the DLSS/NIS frame path. Minimized or otherwise non-presenting frames now
  leave before resource allocation and Streamline evaluation; presentation
  resume rejects the interrupted temporal history. A copied-instance Vulkan
  regression run stayed minimized for more than 75 seconds without the prior
  `presentCommon()` bookkeeping error, then resumed Quality plus NIS and shut
  down cleanly.
- Corrected the documented cold `mode=off` contract: it is a zero-Streamline
  bootstrap and OFF-to-DLSS activation therefore requires a process restart.
- Restored the optional Sodium/Milkshade cutout marker without introducing a
  Sodium compile dependency. The compatibility mixin targets the exact Sodium
  0.9.1 shader-builder overloads and marks only the `ALPHA_CUTOUT == 0.5`
  pipeline; translucent `0.01` remains untouched. F9 captures now show a
  spatially bounded, non-empty transparency hint for this path.
- Added an exact, fail-closed marker rewrite for Minecraft 26.2's existing
  `post/transparency` compositor. It reuses the compositor's existing color
  samples and carries composition/particle ownership in world-target alpha;
  it adds neither a texture sample nor a full-screen pass. The current
  Sodium/Milkshade custom preset did not instantiate that optional compositor
  during live verification, so this is not claimed as general translucency or
  particle parity.
- Added cached 240-frame CPU/GPU p50, p95 and p99 snapshots to F9 debug
  metadata. The read occurs only when a capture is requested.
- Made the hidden Streamline hint audit fail closed for mask types not consumed
  by the pinned DLSS 2.12 plugin. Requested and effective hint types plus the
  compatibility result are recorded instead of claiming a false A/B effect.
- Made Halton jitter use the same unsigned 32-bit frame value as Streamline,
  including the signed Java boundary and the complete `0xFFFFFFFF` wrap.
- Added source/binary SHA-256 provenance for the native bridge and motion
  shader. Release verification now rejects a stale DLL or SPIR-V instead of
  silently packaging it after a source-only change.
- No renderer-quality, image-parity, Milkshade-overhead, FPS or frame-time
  release claim is made until the remaining visual, performance and soak gates
  are complete.

## Unreleased

The Phase-1A.12 notes in this section are retained as historical implementation
evidence. Where their former normal-`mode=off` bootstrap contract conflicts
with the 0.3.15 section above, the 0.3.15 zero-bootstrap/restart contract is
authoritative.

- Added the Phase-1A.12 central cached inventory for exactly 13 current
  optional features: DLSS mode, entity motion scratch, native-experimental
  history, transform scratch, shader-setup pool, material-sampler cache,
  outline pose reuse, frame profiler, GPU breadcrumbs, physical-memory
  telemetry, Debug Utils labels, Tracy correlation and Device Fault. No
  future-phase or rejected-staging feature was invented.
- Added current-owner switches with documented defaults and process-restart
  boundaries. Only the existing DLSS mode remains live. Disabled or failed
  consumers use their existing Mojang-native, heap/legacy, direct,
  original-sampler, fresh-`PoseStack` or no-op fallback. The DLSS switch gates
  productive targets, evaluation and scratch, but normal cold `mode=off`
  deliberately retains Native-Cache/Streamline readiness before
  `vkCreateDevice`: Streamline manual Vulkan hooking requires `slInit` before
  device creation and the existing UI permits live OFF-to-ON on that device.
  Only Safe Start is zero-bootstrap. This exception remains an explicit
  acceptance issue rather than being hidden behind the per-owner wording.
- Added bounded run-state files
  `config/blockframe-state/run-state-{a,b}.bfrs`, fixed same-directory
  temporaries and `run-state.lock`. Each slot is at most 64 KiB, canonical
  UTF-8/LF and SHA-256 checked. Atomic replace is reported only when achieved;
  unsupported atomic move uses the honest recoverable two-slot mode.
- Added fail-open recovery for empty/truncated/corrupt/future state, ambiguous
  or overflowing generations, I/O/permission/cleanup failure and process-lock
  conflict. State contains bounded version/backend/run/feature/checkpoint/LKG/
  failure/Safe-Start identifiers, never personal paths, raw configuration,
  unbounded logs, stack traces or vendor binary data.
- Defined `STARTING`, `INITIALIZING`, `STABLE`, `FAILED`, previous-run
  `UNCLEAN` and `CLEAN_SHUTDOWN` exactly. LKG requires backend plus active
  feature publication, a first successful world frame and 120 consecutive
  successful frames. A partial or one-shot Safe-Start run cannot replace LKG.
- Kept the clean marker independent of failure: a confirmed failed run may
  later close orderly without erasing `FAILED` or its error. Clean requires
  ClientStopping, ClientStopped, normal `Minecraft.close` return and successful
  DLSS plus engine cleanup; releasing the persistence lock cannot mark clean.
- Added an in-game title-screen Safe-Start offer once per matching event.
  Explicit acceptance queues a one-shot for the next process, where it is
  consumed before optional Vulkan/DLSS setup. Ignore/decline remains normal.
  Normal configuration stays byte-identical; no world/player data, quality
  setting, content, particles or view/simulation distance is modified.
- Made F8 feature/run-state and Tracy capability display fully cached. Its
  stability line means “last sparse state publication”: lifecycle transitions
  and world-frame 1/120 republish it, while frames 2-119 reuse the same cached
  lines. `config-owner` identifies the canonical file/key contract, not whether
  a value came from the current file, legacy compatibility snapshot or default.
  The enabled physical-memory feature performs only a cheap due check in the
  frame coordinator; real OS and eligible driver queries remain at least one
  second apart. The known 48-byte allocation per actual device-local
  aggregation remains a final allocation-audit candidate.
- Bound resource-load revalidation to the successful
  `ClientResourceLoadFinishedEvent` actually exposed by NeoForge. Initial load
  and completed F3+t reset/revalidate the stability window; an abort before the
  finish event is unobservable and is not claimed as covered.
- Passed the Phase-1A.12 full suite at 77 suites / 486 tests with zero
  failures, errors or skips, then passed the strict clean build. The
  33,169,623-byte JAR has SHA-256
  `3C1C559EFE6879570C40D6EFBD2E24686267108F97B7D20DDF1644DD905610B9`;
  its 1,086-byte generated manifest has SHA-256
  `B62B126CBB22B579DAE55ED7D5D10CCDF75ABFD22D7A36A0FAEC103480A95A4F`.
  A previously observed Windows JUnit `@TempDir` cleanup failure did not recur
  in 34 repeats across 25 Gradle invocations; no product leak was evidenced,
  but an external holder was not proven.
- Added the five-fork, 25-fresh-JVM cached-state benchmark: policy enabled,
  registry state, cached snapshot, cached debug lines and stable tracker
  p50/p95/p99 are respectively `1.1450/1.1824/1.4282`,
  `1.7328/2.0194/2.7568`, `1.1474/1.1868/1.2474`,
  `1.1492/1.4342/1.5000` and `1.1454/1.1712/1.2232 ns/op`. All rows report
  0 measured allocation, 0 collections and 0 ms GC. The 17,767-byte CSV has
  SHA-256
  `AD742FDA9BDAFFC8B4AC8E8A8EFEE18CF786DC95C1FB4B03E2CF92C56F9A4E2A`.
  It measures no Minecraft scene, Vulkan/GPU work, persistence transition/I/O,
  FPS, frame time, RSS, VRAM or durability.
- Passed the completed Vulkan portions of the Phase-1A.12 live matrix: normal
  Quality with F8/F3+t/Save-Title-Reload/second-world/shutdown, individually
  disabled DLSS/material-cache/physical-memory/Device-Fault cases, the
  4,096-byte shader-resource fallback, and the explicit queued/consumed
  Safe-Start one-shot followed by a normal next run. Safe Start alone showed
  zero Native-Cache/Streamline bootstrap.
- Passed OpenGL with one OpenGL backend selection, no Native Cache, Streamline,
  Vulkan request, VUID, error, exception, use-after-free or leak. F8 used the
  cached state; completed F3+t and Save-Title-Reload produced three 120-frame
  stability points, and shutdown durably reached `CLEAN_SHUTDOWN`. The exact
  known Mojang/NeoForge `item_translucent_unlit` and `item_cutout_unlit`
  `Sampler2` warning types repeat once after F3+t (four occurrences total);
  they remain disclosed warnings rather than a warning-free claim. The
  31,002-byte log has SHA-256
  `26DBF4AAE29E969A4133D81ACECA780A3CDD3184A1C77378FFC0B474F51D275B`;
  its 107-byte stderr has SHA-256
  `5C8020B222ADD6DC287898C6EC487A49E7B912D2EA18BF877AEF3949EAB96C4F`.
- Rebuilt the JNI bridge with transactional failed-bootstrap cleanup and a
  result-bearing shutdown: resolved pointers are cleared before
  `FreeLibrary`, and release uncertainty remains sticky instead of being
  reported clean. `nvidia_dlss_bridge.dll` is 418,304 bytes with SHA-256
  `A9E56A536BF6117870D705F200495B068EF5DEFEAC7DD84D71207BF6DB517002`;
  the current ten-file payload is 62,557,070 bytes with bundle SHA-256
  `7EEDDA802C3E39D53EA8291300062B217EBE7FF12E7C91B03692AC71D0DED178`.
- Phase 1A.12 remains unaccepted: the normal `mode=off` bootstrap exception
  needs explicit resolution/acceptance, and the final all-current-owner
  allocation/GC audit remains executable Phase-1A work. No state/persistence
  benchmark establishes FPS, frame-time, speedup, RSS, VRAM or image-quality
  results. Phase 2 remains paused.
- Restored the Vulkan/OpenGL runtime baselines, removed only the owned
  one-off menu-arguments temporary, retained evidence and valid state slots,
  stopped all repository Java/Gradle/client processes and closed Computer Use.
  The stale Git index remains untouched at 8,208 bytes, timestamp
  `2026-07-22T18:54:54.1286550Z`, SHA-256
  `5748988C3C76A48504A6E8C100E2DA8AA6F538A4700683E9B1771F03638EFECD`.
- Recorded Phase 1A.11 as
  `ACCEPTED_WITH_DEFERRED_POSITIVE_CAPTURE`. A positive real Device-Fault
  capture and live in-process device recreation remain `NOT_RUN`; neither is
  simulated or inferred.
- Implemented Phase 1A.11 optional `VK_EXT_device_fault` diagnostics at the
  exact pre-`vkCreateDevice` boundary. Activation is Vulkan-only and requires
  both extension advertisement and the `deviceFault` feature from a
  `VkPhysicalDeviceFeatures2`/pNext query; it is never a required extension.
- Preserved Mojang ownership of the extension/feature sets after publication,
  the create-time `MemoryStack`, logical device, queues, VMA allocator,
  `VulkanDebug` and checkpoint diagnostics. Unsupported capability, false
  feature, hook conflict, mutation/allocation failure or any uncertainty
  disables only Device Fault and leaves normal Vulkan startup unchanged.
- Bound `vkGetDeviceFaultInfoEXT` only to the current device generation and
  detached it during close. Capture is attempted once only after a real
  `VK_ERROR_DEVICE_LOST`, with at most two Vulkan calls, 32 address records,
  32 vendor records, 256 text bytes and zero requested or persisted vendor
  binary bytes. There is no polling thread, per-frame query or induced loss;
  F8 reads immutable cached state only.
- Added supported, unsupported, feature-false, duplicate-extension, pNext,
  missing-function, device-generation, close, bounded-capture and failure
  source/unit contracts. The final clean build executed all 10 tasks in
  15 seconds and passed 61 suites / 361 tests with zero failures, errors or
  skips. The 33,045,483-byte JAR has SHA-256
  `21274E7F64022A65A41D244B091D03CA104C0EA6DC179D1DFCFBF17B726D52A8`.
- Passed normal Vulkan with requested/extension-supported/feature-supported/
  enabled/function-resolved all true and cached status `READY_NOT_CAPTURED`.
  Passed forced-disabled Vulkan with requested/enabled/function-resolved
  false, extension/feature support true, status `NOT_REQUESTED` and reason
  `disabled-by-configuration`; F8, F3+t, world save/title and clean shutdown
  passed. OpenGL requested and queried nothing. Normal Vulkan and OpenGL
  passed F8, F3+t, Save–Title–Reload into a second world and clean shutdown.
- The scoped six-log scan found zero VUID, validation, device-lost,
  use-after-free, leak, lifecycle, fatal or BlockFrame-error matches. It does
  not reclassify the two exact OpenGL warnings:
  `[mojang/GlProgram]: neoforge:pipeline/item_cutout_unlit shader program does
  not use sampler Sampler2 defined in the pipeline. This might be a bug.` and
  `[mojang/GlProgram]: neoforge:pipeline/item_translucent_unlit shader program
  does not use sampler Sampler2 defined in the pipeline. This might be a bug.`
- A positive real device-fault capture remains `NOT_RUN` because no safe
  existing trigger exists and no device loss was intentionally induced.
  Phase 1A.11 makes no FPS, frame-time or speedup claim.
- Implemented Phase 1A.10 current GPU pass/resource/borrowed-queue visibility.
  `GpuPassIdentity` supplies stable Frame, Motion Compute, DLSS Evaluate and
  Graphics Submit names across the existing breadcrumbs, CPU Tracy, borrowed
  Mojang GPU Tracy/Debug Utils boundaries and frame timer.
- Named the current persistent motion resources, bounded material samplers,
  DLSS low-resolution color/depth images and views, borrowed graphics/compute
  queue roles and the already existing frame-timer query pool. Minecraft
  retains ownership of `VulkanDebug`, queues, encoders and GPU Tracy
  timestamp machinery.
- Kept diagnostics fail open and ownership-neutral: no second profiler,
  timestamp pool, queue, callback, thread, resource, native owner, RAM/VRAM
  lease or scheduler. Graphics Submit wraps the exact real submission close
  in a CPU zone and reports GPU duration `N/A`; it does not replay submission
  or invent completion.
- Added a five-pair alternating fresh-JVM disabled-diagnostics benchmark:
  production/control p50/p95/p99 is `4.6604/5.0644/6.0652` versus
  `4.7076/6.1600/20.1556 ns/op` over 26,250,000 operations/backend. Both
  record 800 measured bytes total, zero collections/0 ms GC and checksum
  `14375184728760148521`; no speedup, Minecraft, GPU, FPS, RSS or VRAM result
  is claimed. CSV SHA-256 is
  `67125C24291D42EA89CF89C5E323D2B4640CAAC2838D55E0EE19938B9D949884`.
- Passed the strict 56-suite/332-test clean build in 15 seconds. The
  33,010,765-byte JAR has SHA-256
  `34288CA7063ADF848858914FA99E697B34489D986802764A90220AF93548EBF9`.
- Passed every executable Phase-1A.10 live gate: normal Vulkan Quality;
  label-enabled Vulkan with advertised `VK_EXT_debug_utils`; combined
  one-byte diagnostics/staging plus 4,096-byte shader-resource fallbacks; and
  OpenGL, including F3+t, reload/title and clean shutdown coverage. Function,
  required content, view/simulation distances and the selected DLSS mode were
  retained; the image-quality impact/parity of fallback bias `0` is unproven.
- Kept external Debug Utils/Tracy label capture, live in-process device
  recreation and paired normal-bias/fallback-bias image-quality evidence
  explicitly `NOT_RUN`. Phase 1A.10 is
  `ACCEPTED_WITH_DEFERRED_EXTERNAL_GATES`; the diagnostic microbenchmark does
  not establish an FPS or speedup result.
- Implemented the Phase-1A.9 cached physical-memory diagnostics: Java 25
  operating-system total/available physical RAM plus exact-generation,
  render-thread-confined Vulkan device-local heap capacity, driver budget,
  driver-estimated usage and derived clamped headroom.
- Kept the telemetry observational with at least `1,000,000,000 ns` between
  due refreshes. Each due refresh performs at most one real OS query and, only
  with exact valid Vulkan ownership, advertised extension and render thread,
  at most one real driver query. Phase 1A.12 puts only the cheap due check in
  the enabled frame coordinator; intervening frames and every F8 extraction
  reuse the same snapshot. F8 performs no query. There is no logical-budget/
  eviction/quality/render decision, scheduler, pool, persistent native owner
  or newly enabled Vulkan extension.
- Added explicit OpenGL, unsupported-extension, no-device-local-heap, query,
  overflow, wrong-thread, owner-conflict, device-closing, stale-generation,
  reentrancy and shutdown states that clear stale numeric data.
- Passed the strict 54-suite/324-test clean build in 15 seconds and produced
  the 33,004,693-byte JAR (SHA-256
  `067020F19C1752FBA6E35E4BB25EE89E459669DEC9FC7ACAD31FA0C63DE3D5A0`).
- Added the 40-fresh-worker physical-telemetry benchmark. Cached production
  p50/p95/p99 is `8.899/10.466/11.189 ns/op`; due real OS probing is
  `1,297.4/1,520.0/18,962.0 ns/op`; driver-free fixed device aggregation is
  `6.608/10.906/18.116 ns/op`. The benchmark issues no Vulkan-driver query
  and proves no Minecraft FPS, frame-time, RSS or incremental-VRAM result.
- Retained the fixed production device aggregation's measured 48-byte
  allocation as a named small candidate for the final Phase-1A allocation
  audit; it may be removed only without added complexity or regression.
- Passed every executable Phase-1A.9 live gate: normal Vulkan Quality with
  readable OS/device-local telemetry, F3+t, Save–Title–Reload and shutdown;
  the 4,096-byte shader-budget regression with exactly one bounded rejection,
  active transform slab and original sampler/effective bias `0`; and OpenGL
  with RAM `AVAILABLE`, device `NOT_VULKAN`, no numeric device values and
  Vulkan scratch/material paths not attempted.
- Recorded normal Vulkan OS available/total
  `33,304,283,616/66,174,603,264` bytes and Vulkan
  budget/usage/derived-headroom/device-local-heap
  `24,505,221,120/1,701,388,288/22,803,832,832/25,310,527,488` bytes.
  All three final logs have zero VUID, validation, device-lost,
  use-after-free, leak, lifecycle or error matches.
- Recorded Phase 1A.9 as accepted. Live in-process device recreation remains
  `NOT_RUN`, and the 4,096-byte bias-0 path still has no paired A/B
  image-quality-parity proof against normal bias `-1.5849625`.
- Added the Phase-1A.4 Vulkan-only GPU-submission breadcrumb implementation: a fixed
  64-entry x 6-`long` ring using exactly 3,072 native RAM `DIAGNOSTICS` bytes
  and zero VRAM, fed by motion compute, Streamline evaluate and Mojang encoder
  submit.
- Separated encoded, submitted, timeline-proven completed and unproven-close
  abandoned states; bounded overflow overwrites the oldest entry and
  conservative close remains retryable.
- Added normal/reload/save-title/fresh-process/shutdown, one-byte diagnostic
  fallback and OpenGL no-allocation gates plus the isolated 15-sample
  breadcrumb benchmark.
- Passed the strict 44-suite/237-test build and produced its 32,942,478-byte
  distributable JAR (SHA-256
  `79E698BB3BE40E967B69A410BC883EFBD760A8FDDB03EF3E6A3CFD47C6173FD9`).
- Kept live in-process Vulkan-device recreation explicitly open: the
  source/unit close/new-generation contract and fresh-process allocation pass,
  but Minecraft 26.2 exposes no live recreation action in this test setup.
- Rejected a separate triple BlockFrame staging ring; Mojang remains the sole
  staging/upload owner.
- Added Phase-1A.3 fixed object-slab, confined native-arena and native-block-pool primitives with budget-first publication and owner-thread cleanup retries.
- Moved DLSS transform scratch to stable slab slots with exact same-frame legacy fallback and post-evaluation temporal-state publication.
- Budgeted the Vulkan timestamp scratch and the 32-KiB CPU shader-setup pool, while preserving timer-only disable and direct shader-loading fallbacks.
- Added exact RAM `entities/shader/staging` diagnostics plus transform/pool state, constrained-category regression gates and hardened reload/device-recreation/shutdown order.
- Removed the legacy Sodium build/runtime dependency and shader hooks; Sodium is now an isolated benchmark reference only.
- Added safe native-present fallback and corrected aliased compute-queue indexing.
- Added canonical SHA-256 manifest/key generation and an atomically published, process-locked native runtime cache with exact corruption recovery, deterministic LRU cleanup and verified transient fallback.
- Added configurable `cache.maxBytes`, cache I/O diagnostics and a Java-25 five-cold-entry/five-warm-entry native-bundle benchmark.
- Kept Streamline logs outside immutable entries and reject non-Windows-x64 native materialization before cache access.

## 0.3.14

- Replaced the symmetric moving-edge history mask with a one-sided disocclusion test to reduce close-range block ghosting without discarding valid history on both sides of an edge.
- Kept a conservative, separate history path for cutout terrain and foliage.
- Added an opaque transparency marker for relevant RGBA8 terrain outputs.
- Cached NVIDIA optimal-size queries until the mode or output resolution changes.
- Kept automatic sharpening disabled for DLAA and Quality after controlled foliage-shimmer testing; manual sharpening remains available.

## 0.3.7

- Added DLSS Super Resolution and DLAA modes for Minecraft 26.2's native Vulkan backend.
- Added reconstructed motion vectors, temporal jitter, reset handling and reversed-Z depth constants.
- Added world-only LOD bias and anisotropic filtering.
- Added optional NIS sharpening, the F8 diagnostics overlay and F9 same-frame captures.
- Added Vivecraft detection with DLSS/DLAA disabled during active VR rendering.
