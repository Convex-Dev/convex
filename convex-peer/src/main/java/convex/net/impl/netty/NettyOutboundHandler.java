package convex.net.impl.netty;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.util.Bits;
import convex.core.util.Utils;
import convex.net.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

class NettyOutboundHandler extends ChannelOutboundHandlerAdapter {
	@Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        Message m = (Message) msg;
        Blob data=m.getMessageData();
        
        int mlen=Utils.checkedInt(data.count());
        int headLen=Format.getVLQCountLength(mlen);

        // Buffer for header
        ByteBuf headBuf = ctx.alloc().buffer(headLen);
        writeVLQCount(headBuf,mlen);

		// Buffer for message data
		ByteBuf encodedBuf = Unpooled.wrappedBuffer(data.getInternalArray(), data.getInternalOffset(), mlen);

		// Write the buffers, flushing at the end of the message
		ctx.write(headBuf);
		ctx.writeAndFlush(encodedBuf);
    }
	
	/**
	 * Helper function for writing message length
	 * @param bb
	 * @param x
	 */
	public static void writeVLQCount(ByteBuf bb, long x) {
		if (x<128) {
			if (x<0) throw new IllegalArgumentException("Negative count!");
			// single byte
			byte single = (byte) (x);
			bb.writeByte(single);
			return;
		}
		int bitLength = 64-Bits.leadingZeros(x);
		int blen = (bitLength + 6) / 7; // 8 bits overflows to 2 bytes etc.
		for (int i = blen - 1; i >= 1; i--) {
			byte single = (byte) (0x80 | (x >>> (7 * i))); // 7 bits
			bb.writeByte(single);
		}
		byte end = (byte) (x & 0x7F); // last 7 bits of long, high bit zero
		bb.writeByte(end);
	}
}