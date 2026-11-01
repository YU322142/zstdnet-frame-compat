// SPDX-License-Identifier: MIT
package io.github.framecompat;

import io.netty.buffer.*;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.jar.*;

/** Tests a user-supplied upstream jar without checking it into the repository. */
public final class PatchIntegration {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply -PupstreamJar=INPUT.jar");
        Path upstream = Path.of(args[0]), directory = Files.createTempDirectory("frame-compat-check-"), patched = directory.resolve("patched.jar");
        try {
            ZstdNetFramePatch.patch(upstream, patched);
            int changed = 0;
            try (JarFile before = new JarFile(upstream.toFile()); JarFile after = new JarFile(patched.toFile())) {
                for (Enumeration<JarEntry> rows = before.entries(); rows.hasMoreElements();) {
                    JarEntry entry = rows.nextElement();
                    byte[] oldBytes = before.getInputStream(entry).readAllBytes(), newBytes = after.getInputStream(after.getJarEntry(entry.getName())).readAllBytes();
                    if (!Arrays.equals(oldBytes, newBytes)) {
                        changed++;
                        if (!Set.of(ZstdNetFramePatch.DECODER, "META-INF/neoforge.mods.toml").contains(entry.getName())) throw new AssertionError("Unrelated content changed");
                    }
                }
                if (changed != 2 || after.getJarEntry("LICENSES/ZstdNet-MIT.txt") == null) throw new AssertionError("Patch or notice mismatch");
                String metadata = new String(after.getInputStream(after.getJarEntry("META-INF/neoforge.mods.toml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!metadata.contains("version=\"1.4.9-framefix.2\"")) throw new AssertionError("Version not marked");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[]{patched.toUri().toURL()}, PatchIntegration.class.getClassLoader())) {
                Class<?> decoderType = loader.loadClass("cn.tohsaka.factory.zstdnet.core.transport.ZstdFramedCodec$Decoder");
                Class<?> encoderType = loader.loadClass("cn.tohsaka.factory.zstdnet.core.transport.ZstdFramedCodec$Encoder");
                LongConsumer ignore = value -> {};
                for (boolean compressed : new boolean[]{false, true}) {
                    byte[] payload = new byte[2 * 1024 * 1024 + 31]; Arrays.fill(payload, (byte) 42);
                    ByteBuf original = Unpooled.buffer().writeBytes(FrameRegression.header(payload.length)).writeBytes(payload), wire = original;
                    EmbeddedChannel encoder = null;
                    if (compressed) {
                        encoder = new EmbeddedChannel((ChannelHandler) encoderType.getConstructor(int.class, LongConsumer.class, LongConsumer.class).newInstance(3, ignore, ignore));
                        encoder.writeOutbound(original); wire = encoder.readOutbound();
                        if (wire.readableBytes() > payload.length / 10) throw new AssertionError("Compression ratio changed");
                    }
                    EmbeddedChannel decoder = new EmbeddedChannel((ChannelHandler) decoderType.getConstructor(LongConsumer.class, LongConsumer.class).newInstance(ignore, ignore));
                    while (wire.isReadable()) decoder.writeInbound(wire.readRetainedSlice(Math.min(4093, wire.readableBytes())));
                    wire.release(); ByteBuf result = decoder.readInbound();
                    byte[] expected = new byte[FrameRegression.header(payload.length).length + payload.length];
                    System.arraycopy(FrameRegression.header(payload.length), 0, expected, 0, FrameRegression.header(payload.length).length);
                    System.arraycopy(payload, 0, expected, FrameRegression.header(payload.length).length, payload.length);
                    byte[] actual = new byte[result.readableBytes()]; result.readBytes(actual); result.release();
                    if (!Arrays.equals(expected, actual)) throw new AssertionError("Large-frame round trip changed bytes");
                    decoder.finishAndReleaseAll(); if (encoder != null) encoder.finishAndReleaseAll();
                }
                EmbeddedChannel empty = new EmbeddedChannel((ChannelHandler) decoderType.getConstructor(LongConsumer.class, LongConsumer.class).newInstance(ignore, ignore));
                empty.writeInbound(Unpooled.wrappedBuffer(new byte[]{0})); ByteBuf frame = empty.readInbound();
                if (frame == null || frame.readableBytes() != 1 || frame.readByte() != 0) throw new AssertionError("Zero frame rejected");
                frame.release(); empty.finishAndReleaseAll();
            }
            System.out.println("PASS actual-jar patch isolation, notices, zero frame and compressed/uncompressed large frames");
        } finally { Files.deleteIfExists(patched); Files.deleteIfExists(directory); }
    }
}
