# BlockFrame DLSS 0.3.16 – Changelog

## Fixed

- Fixed long third-person trails and stale double contours around the player.
- Fixed trailing on arms, legs, held items, large item models and additional
  skin layers.
- Fixed world smearing during camera movement with DLAA.
- Corrected motion data during switches between first person, third-person
  back and third-person front views.
- Removed temporal artifacts caused by mismatched current and previous data.

## Rendering

- Implemented camera-relative reprojection for static world geometry.
- Current and previous camera matrices are now published atomically for each
  render frame.
- Reworked motion vectors for the player, entities and held items.
- RG16F motion values are now mapped to the valid range while preserving their
  direction.
- Updated temporal resource rotation and history hand-off between consecutive
  render frames.
- Split release and diagnostic motion shaders into separate variants.

## Graphics settings

- Added a DLSS/DLAA mode selector to vanilla Video Settings.
- Added direct DLSS/DLAA selection to Sodium 0.9.1.
- Added a fallback settings screen for Reese's Sodium Options.
- Added Off, Quality, Balanced, Performance, DLAA and Ultra (4K) modes.
- Shortened the option name to `DLSS` and compacted mode labels to prevent
  overlapping text in the graphics menu.
- Extended the tooltip with the selected mode and the bundled NVIDIA DLSS and
  Streamline versions.

## Diagnostics and performance

- Moved capture sequences, GPU readbacks, PNG output, replay harnesses, Tracy,
  GPU breadcrumbs and debug mixins behind a developer master switch.
- Removed debug-image bindings and diagnostic branches from the normal motion
  shader.
- Removed obsolete diagnostic hints and their associated runtime switches.
