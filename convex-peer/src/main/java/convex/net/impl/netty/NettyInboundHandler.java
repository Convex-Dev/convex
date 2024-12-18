package convex.net.impl.netty;

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cpos.CPoSConstants;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;
import convex.net.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

class NettyInboundHandler extends ChannelInboundHandlerAdapter { 
	
	static final Logger log = LoggerFactory.getLogger(NettyInboundHandler.class.getName());

	
	private final Consumer<Message> receiveAction;


	private Predicate<Message> returnAction;


	private long receivedCount=0;

	ByteBuf lenBuf;
	ByteBuf messageBuf=null;

	public NettyInboundHandler(Consumer<Message> receiveAction, Predicate<Message> returnAction)  {
		this.receiveAction=receiveAction;
		this.returnAction=returnAction;
	}
	
	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		lenBuf = Unpooled.wrappedBuffer(new byte[16]); 
		lenBuf.writerIndex(0);
	}

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) { 
    	try {
       		ByteBuf buf=(ByteBuf)msg;
       		int mlen;
       	    if (messageBuf==null) {
       	    	mlen=0;
    			int lb=lenBuf.writerIndex(); // number of bytes written to lenBuf already
    			int i=0;
    			int newBytes=buf.readableBytes();
    			while (true) {
    				byte b;
    				if (i<lb) {
    					// take from lenBuf
    					b=lenBuf.getByte(i);
    				} else {
    					// read from new input
    					b=buf.readByte();
    					lenBuf.writeByte(b);
    					newBytes--;
    				}
    				
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
        	         	messageBuf=Unpooled.wrappedBuffer(new byte[mlen]);
        	         	messageBuf.writerIndex(0); // position ready to read
        	    		lenBuf.writerIndex(0); // reset lenBuf writer position
        	    		lenBuf.readerIndex(0); // reset lenBuf reader position
        				break;
        			};
        			
        			// no new bytes, need to wait for more
        			if (newBytes==0) return;
        			i++;
    			}
       	    } else {
       	    	// get expected length from existing length buffer
          	    mlen=Utils.checkedInt(Format.readVLQCount(lenBuf.array(), 0));     	    	
       	    }
       	       
    		buf.readBytes(messageBuf,Math.min(buf.readableBytes(), messageBuf.writableBytes()));
    		if (messageBuf.writerIndex()<mlen) return; // exit if still expect more
    		
    		// We now have a complete message!
    		// Create message with return action, unspecified (null) type and Blob data
    		
    		Message m=Message.create(returnAction,null,Blob.wrap(messageBuf.array()));
    		messageBuf=null; // clear messageBuf for next message
   		
    		receivedCount++;
    		if (receiveAction!=null) {
    			// Call the message receiver handler
    			receiveAction.accept(m);
    		} else {
    			log.warn("Message ignored, no receiveAction");
    		}

    	} catch (Exception e) {
    		ctx.channel().close();
    		NettyServer.log.info("Closing channel in Netty channelRead: "+e.getMessage());
    	} finally {
			ReferenceCountUtil.release(msg);
		}
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
}