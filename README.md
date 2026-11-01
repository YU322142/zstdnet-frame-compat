# ZstdNet Frame Compat

为 ZstdNet 的原版帧透传分支提供有界长度解析：允许零长度帧与较宽 VarInt 头，保留不完整输入，并拒绝超过 8MiB 的帧。

Source-only compatibility patch tooling for ZstdNet's vanilla-frame passthrough. Zstd compression, the encoder and encryption implementations remain unchanged. This is an independent compatibility tool, not an upstream release.

## Build and test

Requires JDK 21 and Gradle 9.2.1.

```text
gradle check installDist
```

The distribution contains a launcher for `ZstdNetFramePatch`. Supply an upstream jar obtained from its official source and a new output path:

```text
zstdnet-frame-compat upstream.jar patched.jar
```

Supported input: NeoForge 1.21.1 ZstdNet 1.4.9, SHA256 `6a53c2eae4e922408e4f5a4715c0282c8b67bcbafbd7131d65196ee1c758785c`. An unknown hash, unexpected method shape, signed jar, existing output or input/output collision is rejected. Install only one version of the mod in each instance. The tool does not change installed files or disable compression.

## Verification

`gradle check` exercises zero-length frames, split headers and payloads, wide lengths, byte preservation, counters and bounded rejection using synthetic buffers. To additionally patch and exercise an actual supported codec locally:

```text
gradle integration -PupstreamJar=upstream.jar
```

Upstream binaries are supplied locally and excluded from version control. Automated checks are not a substitute for testing the exact loader/mod combination before installation. This tool does not claim to fix every connection or authentication failure.

MIT licensed tooling. Upstream copyrights and dependency notices remain applicable; see `THIRD_PARTY_NOTICES.md`. No game files, modpacks, proprietary assets or deployment reports are included.
