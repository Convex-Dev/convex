package convex.net;

import convex.core.data.Blob;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.ReferenceCountUtil;

public class NettyServer {
	
	private class MessageHandler extends ChannelInboundHandlerAdapter { // (1)

	    @Override
	    public void channelRead(ChannelHandlerContext ctx, Object msg) { 
	    	try {
	    		ByteBuf buf=(ByteBuf)msg;
	    		int n= buf.readableBytes();
	    		byte[] dst=new byte[n];
	    		buf.readBytes(dst);
	    		System.err.println(Blob.wrap(dst));
	    	} finally {
	    		 ReferenceCountUtil.release(msg);
	    	}
	    }

	    @Override
	    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { // (4)
	        // Close the connection when an exception is raised.
	        cause.printStackTrace();
	        ctx.close();
	    }
	}

	
	private Integer port;

	public NettyServer(Integer port) {
		this.port=port;
	}
	
	public void run() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(); 
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap(); 
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class) 
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ch.pipeline().addLast(new MessageHandler());
                 }
             })
             .option(ChannelOption.SO_BACKLOG, 128)          
             .childOption(ChannelOption.SO_KEEPALIVE, true); 
    
            ChannelFuture f = b.bind(port).sync(); 
    
            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
	
	public static void main(String... args) throws Exception {
		new NettyServer(8000).run();
	}
}
