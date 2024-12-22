package convex.net.impl.netty;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cpos.CPoSConstants;
import convex.core.data.Blob;
import convex.core.exceptions.BadFormatException;
import convex.core.message.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

class NettyInboundHandler extends ByteToMessageDecoder { 
	
	static final Logger log = LoggerFactory.getLogger(NettyInboundHandler.class.getName());

	
	private final Consumer<Message> receiveAction;

	private Predicate<Message> returnAction;

	private long receivedCount=0;

	public NettyInboundHandler(Consumer<Message> receiveAction, Predicate<Message> returnAction)  {
		this.receiveAction=receiveAction;
		this.returnAction=returnAction;
	}

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { // (4)
        // Close the connection when an exception is raised.
		log.info("Closed Netty channel due to: "+cause.getMessage(),cause);
        ctx.close();
    }

	public long getReceivedCount() {
		return receivedCount;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
   		ByteBuf buf=(ByteBuf)in;
   		buf.markReaderIndex(); // mark reader position, may revert to this if no complete message 
   		
   		int mlen=0;
		int newBytes=buf.readableBytes();
		if (newBytes==0) return;
		
		for (int i=0; ; i++) {
			if (i>=newBytes) {
				// insufficient bytes for message length, need to wait for more
				buf.resetReaderIndex();
				return;
			}

			byte b=buf.readByte();

			if ((i==0)&&(b==0x80)) {
				byte[] bytes=new byte[newBytes];
				bytes[0]=b;
				buf.readBytes(bytes, 1, newBytes-1);
				Blob tmp=Blob.wrap(bytes);
				throw new BadFormatException("Zero leading bits in message length, content: "+tmp);
			}
			
   			int bm=(b&0x7f); // new bits for length
   		    mlen=(mlen<<7)+bm;
			if (mlen>CPoSConstants.MAX_MESSAGE_LENGTH) throw new BadFormatException("Message too long: "+mlen);
			if ((b&0x80)==0) {
				// we have a complete message length
				break;
			};
		}
   	    
		if (buf.readableBytes()<mlen) {
			// insufficient bytes for message, need to wait for more
			buf.resetReaderIndex();
			return;
		}
		
		byte[] messageData=new byte[mlen];
		
		// We now have a complete message!
		buf.readBytes(messageData);
		
		// Create message with return action, unspecified (null) type and Blob data
		Message m=Message.create(returnAction,null,Blob.wrap(messageData));
		out.add(m);
	
		receivedCount++;
		if (receiveAction!=null) {
			// Call the message receiver handler
			receiveAction.accept(m);
		} else {
			log.warn("Message ignored, no receiveAction");
		}
	}
}