// SPDX-License-Identifier: MIT
package cn.tohsaka.factory.zstdnet.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.function.LongConsumer;

/** Vanilla-frame passthrough during Zstd negotiation, preserving wire bytes. */
public final class BoundedVanillaFrame {
    private BoundedVanillaFrame() {}
    public static void forward(ByteBuf input,List<Object> output,LongConsumer raw,LongConsumer wire) {
        int start=input.readerIndex();
        long size=0;
        for(int prefix=0;prefix<5;prefix++) {
            if(input.readableBytes()<=prefix)return;
            int next=input.getUnsignedByte(start+prefix);
            size|=(long)(next&127)<<(prefix*7);
            if((next&128)==0) {
                if(size>8*1024*1024)throw new DecoderException("Frame exceeds 8 MiB transport budget");
                int total=prefix+1+(int)size;
                if(input.readableBytes()<total)return;
                ByteBuf frame=input.readRetainedSlice(total);
                raw.accept(total);wire.accept(total);output.add(frame);
                return;
            }
        }
        throw new DecoderException("Frame length exceeds five bytes");
    }
}
