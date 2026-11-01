# Third-party notices

The target codec belongs to [ZstdNet](https://github.com/wish131400/zstdnet), copyright (c) [2026] [wish], under MIT. Its complete notice is retained in `src/main/resources/LICENSES/ZstdNet-MIT.txt` and added to generated output jars. Upstream mod binaries are not distributed in this repository.

The patch tool uses [ASM](https://asm.ow2.io/) under BSD-3-Clause. Netty, under Apache-2.0, supplies compile and test APIs; zstd-jni, under BSD-2-Clause, is a test dependency. Gradle resolves these dependencies; no dependency jars or native libraries are checked in. Preserve each dependency's license when distributing a built application bundle.

New patch tooling and the bounded passthrough helper are MIT licensed. All original upstream entries retain their original notices. This project does not include patches for other mods.
