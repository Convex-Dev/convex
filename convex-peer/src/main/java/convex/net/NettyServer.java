package convex.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.data.Strings;
import convex.core.util.Shutdown;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class NettyServer extends AServer {

	static final Logger log = LoggerFactory.getLogger(NettyServer.class.getName());

	static EventLoopGroup bossGroup=null;
	
	protected synchronized static EventLoopGroup getEventLoopGroup() {
		if (bossGroup!=null) return bossGroup;
		bossGroup=new NioEventLoopGroup();
		Shutdown.addHook(Shutdown.SERVER,()->{
			if (bossGroup!=null) {
				bossGroup.shutdownGracefully();
			}
		});
		return bossGroup;
	}
	
	private Consumer<Message> receiveAction=m->{
		m.returnMessage(Message.createResult(m.getID(), Strings.create("Received"), null));
		System.err.println(m);
	};

	private Channel channel;

	public NettyServer(Integer port) {
		setPort(port);
	}
	
	public void launch() throws IOException,InterruptedException {
        EventLoopGroup bossGroup = NettyServer.getEventLoopGroup(); 
        EventLoopGroup workerGroup = NettyClient.getEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap(); 
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class) 
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             public void initChannel(SocketChannel ch) throws Exception {
            	 Predicate<Message> returnHandler=m->{
            		 ch.writeAndFlush(m);
            		 return true;
            	 };
            	 NettyInboundHandler inbound=new NettyInboundHandler(getReceiveAction(),returnHandler);
                 ch.pipeline().addLast(inbound,new NettyOutboundHandler());
             }
         })
         .option(ChannelOption.SO_BACKLOG, 128) // Backlog of incoming connection requests         
         .childOption(ChannelOption.SO_KEEPALIVE, true); 

        ChannelFuture f=null;
        Integer port=getPort();
        if (port==null) try {
        	f = b.bind(Constants.DEFAULT_PEER_PORT).sync(); 
        	port=Constants.DEFAULT_PEER_PORT;
        } catch (Exception e) {
        	// failed so try with random port
        	port=0;
    	}
        
        if (f==null) {
        	f = b.bind(port).sync(); 
        	
        	// Check local port
        	InetSocketAddress localAddress=(InetSocketAddress) f.channel().localAddress();
        	setPort(localAddress.getPort());
        }
   		System.out.println("Server started on port: "+getPort());
   	   
        // Wait until the server socket is closed.
   		this.channel=f.channel();
    }
	
	protected Consumer<Message> getReceiveAction() {
		return receiveAction;
	}
	
	public static void main(String... args) throws Exception {
		try (NettyServer server=new NettyServer(8000)) {
			server.launch();
			
			server.waitForClose();
		}
	}

	@Override
	public void close() {
		if (channel!=null) {
			channel.close();
		}
	}
	
	public void waitForClose() throws InterruptedException {
		channel.closeFuture().sync();
	}

	@Override
	public InetSocketAddress getHostAddress() {
		return (InetSocketAddress) channel.localAddress();
	}
}
