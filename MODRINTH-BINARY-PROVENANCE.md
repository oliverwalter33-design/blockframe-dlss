# Native binary provenance for Modrinth review

This document maps every native binary in both **BlockFrame DLSS** versions
currently present on Modrinth to either checked-in source or an exact official
NVIDIA SDK release. Generated and third-party binaries are deliberately not
committed to this repository.

## Published artifacts

| Version | JAR | Bytes | SHA-256 | Matching source |
| --- | --- | ---: | --- | --- |
| `0.3.18-neoforge-26.2` | `blockframe-dlss-0.3.18-unapproved-bricks-farlod-history-candidate-neoforge-26.2.jar` | 34,003,950 | `32fa02e499476ca25066efaf2ef1485c743c2938ba8eff0c7572b823618cc6f1` | Repository root; source-publication commit [`d1f2151f`](https://github.com/oliverwalter33-design/blockframe-dlss/commit/d1f2151f07ad11b9431382774ecb74f4d9271e20) |
| `0.3.16-neoforge-26.2` | `blockframe-dlss-0.3.16-neoforge-26.2.jar` | 33,953,776 | `70e53af0f3c3f505122438d792ef0568808409f05eee8cd26288ab2c2db1b5a4` | Exact full source tree: [`release/0.3.16-neoforge-26.2`](https://github.com/oliverwalter33-design/blockframe-dlss/tree/release/0.3.16-neoforge-26.2); source ZIP SHA-256 `1e39036f571b43c42d03879c5d1ed40cec40a689b5d76eee6bf07f80893f0d08`; native snapshot on `main`: [`provenance/0.3.16`](provenance/0.3.16/README.md) |

Both use Minecraft 26.2 and NeoForge 26.2. The public source repository is
<https://github.com/oliverwalter33-design/blockframe-dlss>.

The command below auto-detects either published JAR by SHA-256 and verifies all
nine DLL/SPIR-V entries. It rejects an unknown JAR, or any missing, additional,
or modified native binary entry.

```powershell
.\scripts\verify-native-provenance.ps1 -JarPath 'C:\path\to\candidate.jar'
```

## Project-built native outputs

These three files are built from source in this repository by
[`native/build-native.ps1`](native/build-native.ps1). The script records the
source hashes, output hashes, compiler versions, and shader build defines in
[`native-source-v1.properties`](src/main/resources/assets/nvidia_dlss/native/win-x64/native-source-v1.properties).

| JAR entry | Checked-in source | Build command | Compiler recorded for candidate | Bytes | Candidate output SHA-256 |
| --- | --- | --- | --- | ---: | --- |
| `nvidia_dlss_bridge.dll` | `native/nvidia_dlss_bridge.cpp` — `268381373b40822996272ed4bcaa7af234464937e4c3c9ddabed6cbba161ffaa` | Zig C++: `-target x86_64-windows-gnu -shared -O3` | Zig `0.15.2` | 558,592 | `f1f1f4ec4b7ecc7c85d0f824b3552468e92600ceca76141c8441965a54a407c6` |
| `motion_vectors.comp.spv` | `native/shaders/motion_vectors.comp` — `0ff5614444f5adf20ac17d863a2b3e3563c709d8bb7e0c8cf91318b735b14b68` | `glslc --target-env=vulkan1.2 -O -DBLOCKFRAME_DEVELOPER_DIAGNOSTICS=0` | shaderc/glslc `v2026.2` | 15,188 | `940687072284efc94f9e3fd4e55c38f7f7593e6ed5e8ebc5bbffb54a73e47551` |
| `motion_vectors.debug.comp.spv` | same shader source | `glslc --target-env=vulkan1.2 -O -DBLOCKFRAME_DEVELOPER_DIAGNOSTICS=1` | shaderc/glslc `v2026.2` | 19,080 | `7807fea40ecf2f6e9c74f651f340715dd611365d6775881cb62f16c328820b0a` |

Before packaging, Gradle's `verifyNativeRuntime` task recomputes these source
and output hashes and compares them with the checked-in stamp. The `jar` task
depends on that verification and therefore fails if a generated binary is
missing or stale.

### Version 0.3.16 project-built outputs

The still-published 0.3.16 JAR predates the current native implementation. Its
complete matching source tree is published on
[`release/0.3.16-neoforge-26.2`](https://github.com/oliverwalter33-design/blockframe-dlss/tree/release/0.3.16-neoforge-26.2).
For one-command cross-version checks on `main`, its exact native source,
shader, build script, and source stamp are additionally preserved under
[`provenance/0.3.16`](provenance/0.3.16/README.md).

| JAR entry | Checked-in 0.3.16 source | Compiler recorded for 0.3.16 | Bytes | Output SHA-256 |
| --- | --- | --- | ---: | --- |
| `nvidia_dlss_bridge.dll` | `provenance/0.3.16/native/nvidia_dlss_bridge.cpp` — `b7a47af8d0c5fe689dbd5aea39dcc43274918a0de1c570bf57d1e455cc14ae96` | Zig `0.16.0` | 583,168 | `c251c0702df49bbfc2caffad0e2be74d525e62d07ceab9d56043d3a3a41dd327` |
| `motion_vectors.comp.spv` | `provenance/0.3.16/native/shaders/motion_vectors.comp` — `3aba5ea79bc3f6bfde8dd38da1e75050ea6f1527fa624830de1153ead74c458f` | LWJGL shaderc `3.4.1`; Vulkan 1.2; performance optimization | 12,272 | `fc89b518ffbfd39b1a2d8d37957534663b10a922a25629d116d72fe25810ea19` |
| `motion_vectors.debug.comp.spv` | same 0.3.16 shader source | same | 16,224 | `a505c8cedd93575a852c5d277bf873447f962bc43016146404562b42f7c053b6` |

## Third-party NVIDIA runtime

The six DLLs below are unmodified production binaries from official NVIDIA
SDK releases. They remain under NVIDIA's licenses, not this project's MIT
license. Both JARs also contain the corresponding NVIDIA license and notice
texts.

Official origins (the complete SDK archive was downloaded and compared
locally with the submitted JAR):

- NVIDIA Streamline SDK `2.12.0` release:
  <https://github.com/NVIDIA-RTX/Streamline/releases/tag/v2.12.0>
- Exact official Streamline archive:
  <https://github.com/NVIDIA-RTX/Streamline/releases/download/v2.12.0/streamline-sdk-v2.12.0.zip>
  — 231,958,617 bytes, SHA-256
  `f5c0a3d870707dddc3570fb4bcd3655cf48a8a68c3a9d342910cfa21b77dcf48`
- NVIDIA DLSS SDK `310.7.0` release (independent confirmation of the bundled
  DLSS library): <https://github.com/NVIDIA/DLSS/releases/tag/v310.7.0>
- Exact official DLSS library at tag `v310.7.0`:
  <https://github.com/NVIDIA/DLSS/blob/v310.7.0/lib/Windows_x86_64/rel/nvngx_dlss.dll>

| JAR entry | Official package/version | Bytes | Windows version metadata | Candidate SHA-256 |
| --- | --- | ---: | --- | --- |
| `NvLowLatencyVk.dll` | Streamline `2.12.0` `bin/x64`, exact archive match | 57,840 | Authenticode-signed by NVIDIA Corporation | `2a77dc3e1c724b7eea5755be0ae7423752e79a2459fae72181a9f00e3507e5d6` |
| `sl.common.dll` | Streamline `2.12.0` `bin/x64`, exact archive match | 830,080 | `2.12.0.0`; original build `v2.12.0-rc1` | `c57930ef5a8a3fe9be85efdf71a61d8107c1148e8a6aed456464547128f7f4ae` |
| `sl.dlss.dll` | Streamline `2.12.0` `bin/x64`, exact archive match | 421,504 | `2.12.0.0`; original build `v2.12.0-rc1` | `a997022d2b93601e0eefc3ddb3067c36df386dd3163ae71e11095191fb14f8e4` |
| `sl.interposer.dll` | Streamline `2.12.0` `bin/x64`, exact archive match | 647,808 | `2.12.0.0`; original build `v2.12.0-rc1` | `2a79db6857ae8c75bbd871a9489c48bc6a39f7fcc88b9b02afd53d0376cbec66` |
| `sl.nis.dll` | Streamline `2.12.0` `bin/x64`, exact archive match | 1,155,200 | `2.12.0.0`; original build `v2.12.0-rc1` | `82ac2d3936ad24856b2219ce43016e7428539bf9dfc217a690a3cc8b05adaf63` |
| `nvngx_dlss.dll` | Streamline `2.12.0` `bin/x64`; also exact NVIDIA DLSS tag `v310.7.0` match | 58,977,904 | `310.7.0.0` | `be6e434a94ca32499515eb62ca0e6c274526055d568d0426e4c652dcdfb6ee6e` |

[`scripts/stage-nvidia-runtime.ps1`](scripts/stage-nvidia-runtime.ps1)
accepts only these exact hashes and requires a valid Authenticode signature
whose signer subject contains `NVIDIA Corporation`. It rejects Streamline's
development binaries and stages only the signed production set.

## Transparent verification chain

1. Download the exact Streamline `2.12.0` archive from the official NVIDIA
   release link above and verify the archive SHA-256.
2. Stage the production NVIDIA DLLs with
   `scripts/stage-nvidia-runtime.ps1`; exact SHA-256 and signer checks run
   before any file is copied.
3. Build the JNI bridge and both SPIR-V variants with
   `native/build-native.ps1`; the script writes source/output/compiler
   provenance into `native-source-v1.properties`.
4. Run `gradle clean test build` with Gradle 9.2.1. Gradle verifies that the source stamp
   still matches every project-built native output before creating the JAR.
5. Run `scripts/verify-native-provenance.ps1 -JarPath <jar>` to compare either
   published artifact with its exact JAR and version-specific native-entry
   hashes.

The repository workflow runs the source-side provenance check on every push
and pull request. A local release build additionally performs the native
runtime checks because NVIDIA's redistributable binaries are intentionally not
stored in GitHub.
