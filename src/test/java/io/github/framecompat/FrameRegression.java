// SPDX-License-Identifier: MIT
package io.github.framecompat;

import cn.tohsaka.factory.zstdnet.core.transport.BoundedVanillaFrame;
import io.netty.buffer.*;
import io.netty.handler.codec.DecoderException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 合成边界与生命周期检查 / Synthetic boundaries, byte preservation and ownership. */
public final class FrameRegression {
    static int checks;
    static void expect(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static byte[] header(int value) {
        ByteBuf buffer = Unpooled.buffer(5);
        do { int next = value & 127; value >>>= 7; buffer.writeByte(value == 0 ? next : next | 128); } while (value != 0);
        byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); buffer.release(); return bytes;
    }
    static void complete(int length) {
        byte[] prefix = header(length), payload = new byte[length]; Arrays.fill(payload, (byte) 37);
        ByteBuf input = Unpooled.buffer(prefix.length + length + 3).writeZero(3).writeBytes(prefix).writeBytes(payload); input.skipBytes(3);
        List<Object> output = new ArrayList<>(); AtomicLong raw = new AtomicLong(), wire = new AtomicLong();
        try {
            BoundedVanillaFrame.forward(input, output, raw::addAndGet, wire::addAndGet);
            expect(output.size() == 1, "one frame expected");
            ByteBuf frame = (ByteBuf) output.getFirst();
            expect(frame.readableBytes() == prefix.length + length, "frame size changed");
            byte[] actualPrefix = new byte[prefix.length]; frame.readBytes(actualPrefix);
            expect(Arrays.equals(prefix, actualPrefix), "prefix bytes changed");
            byte[] actual = new byte[length]; frame.readBytes(actual);
            expect(Arrays.equals(payload, actual), "payload changed");
            expect(raw.get() == prefix.length + length && wire.get() == raw.get(), "accounting changed");
            expect(!input.isReadable(), "frame was not consumed exactly");
        } finally { for (Object frame : output) ((ByteBuf) frame).release(); input.release(); }
    }
    static void incomplete(byte[] bytes) {
        ByteBuf input = Unpooled.wrappedBuffer(bytes); int index = input.readerIndex(); List<Object> output = new ArrayList<>();
        try {
            BoundedVanillaFrame.forward(input, output, x -> { throw new AssertionError("partial accounting"); }, x -> { throw new AssertionError("partial accounting"); });
            expect(output.isEmpty(), "incomplete frame forwarded"); expect(input.readerIndex() == index, "partial frame consumed");
        } finally { input.release(); }
    }
    static void reject(byte[] bytes) {
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        try {
            try { BoundedVanillaFrame.forward(input, new ArrayList<>(), x -> {}, x -> {}); throw new AssertionError("Expected rejection"); }
            catch (DecoderException expected) { checks++; }
            expect(input.readerIndex() == 0, "invalid prefix consumed");
        } finally { input.release(); }
    }
    public static void main(String[] args) {
        for (int length : new int[]{0, 1, 127, 128, 16383, 16384, 2 * 1024 * 1024 + 31, 8 * 1024 * 1024}) complete(length);
        byte[] prefix = header(2 * 1024 * 1024 + 31);
        for (int size = 0; size <= prefix.length; size++) incomplete(Arrays.copyOf(prefix, size));
        incomplete(new byte[]{3, 1, 2});
        reject(header(8 * 1024 * 1024 + 1)); reject(new byte[]{-1, -1, -1, -1, -1});
        System.out.println("PASS " + checks + " bounded-frame assertions");
    }
}
