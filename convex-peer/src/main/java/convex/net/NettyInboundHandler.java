package convex.net;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cpos.CPoSConstants;
import convex.core.data.Blob;
import convex.core.exceptions.BadFormatException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

class NettyInboundHandler extends ChannelInboundHandlerAdapter { 
	
	static final Logger log = LoggerFactory.getLogger(NettyInboundHandler.class.getName());

	
	private final Consumer<Message> receiveAction;

	public NettyInboundHandler(Consumer<Message> receiveAction) {
		this.receiveAction=receiveAction;
	}

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) { 
    	try {
    		ByteBuf buf=(ByteBuf)msg;
    		buf.markReaderIndex();
    		int n= buf.readableBytes();
    		
    		int mlen=0;
    		for (int i=0; i<n; i++) {
    			byte b=buf.readByte();
    			int bm=(b&0x7f); // new bits for length
    			if ((i==0)&&(bm==0)) throw new BadFormatException("Zero leading bits in message length");
    			mlen=(mlen<<7)+bm;
    			if (mlen>CPoSConstants.MAX_MESSAGE_LENGTH) throw new BadFormatException("Message too long");
    			if ((b&0x80)==0) break;
    		}
    		
    		if (buf.readableBytes()<mlen) {
    			// Not enough bytes for message
    			buf.resetReaderIndex();
    			return;
    		}
    		
    		byte[] dst=new byte[mlen];
    		buf.readBytes(dst);
    		Message m=Message.create(Blob.wrap(dst));
    		if (receiveAction!=null) {
    			receiveAction.accept(m);
    		} else {
    			log.warn("Message ignored, no receiveAction");
    		}
    	} catch (Exception e) {
    		ctx.channel().close();
    		NettyServer.log.info("Closing channel in Netty channelRead: "+e.getMessage(),e);
    	} finally {
    		ReferenceCountUtil.release(msg);
    	}
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { // (4)
        // Close the connection when an exception is raised.
		NettyServer.log.info("Closed Netty channel due to: "+cause.getMessage(),cause);
        ctx.close();
    }
}