# BlockFrame DLSS 0.3.16 native source snapshot

This directory preserves the exact project-built native source and build
script corresponding to the still-published Modrinth version
`0.3.16-neoforge-26.2`.

The complete exact source tree for that JAR is published on the
[`release/0.3.16-neoforge-26.2`](https://github.com/oliverwalter33-design/blockframe-dlss/tree/release/0.3.16-neoforge-26.2)
branch. This smaller copy exists so the `main` branch can verify both
published versions in one workflow.

| Artifact | Value |
| --- | --- |
| Modrinth version ID | `d2TCEgJ0` |
| JAR SHA-256 | `70e53af0f3c3f505122438d792ef0568808409f05eee8cd26288ab2c2db1b5a4` |
| Exact full source archive SHA-256 | `1e39036f571b43c42d03879c5d1ed40cec40a689b5d76eee6bf07f80893f0d08` |
| Bridge compiler recorded in release | Zig `0.16.0` |
| Shader compiler recorded in release | LWJGL shaderc `3.4.1`, Vulkan 1.2, performance optimization |

The two checked-in sources and `native-source-v1.properties` are byte-for-byte
copies from that exact 0.3.16 source archive. `scripts/verify-native-provenance.ps1`
validates these source hashes and, when given the published JAR, all nine
native DLL/SPIR-V hashes.

The six NVIDIA DLLs are the same signed Streamline 2.12.0 production files as
in 0.3.18. The three project-built output hashes are version-specific and are
listed in the top-level `MODRINTH-BINARY-PROVENANCE.md`.
