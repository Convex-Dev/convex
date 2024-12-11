package convex.net;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.util.Shutdown;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class NettyServer {

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
	
	private Integer port;
	private Consumer<Message> receiveAction=m->{
		System.err.println(m);
	};

	public NettyServer(Integer port) {
		this.port=port;
	}
	
	public void run() throws Exception {
        EventLoopGroup bossGroup = NettyServer.getEventLoopGroup(); 
        EventLoopGroup workerGroup = NettyClient.getEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap(); 
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class) 
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             public void initChannel(SocketChannel ch) throws Exception {
                 ch.pipeline().addLast(new NettyInboundHandler(getReceiveAction()),new NettyOutboundHandler());
             }
         })
         .option(ChannelOption.SO_BACKLOG, 128) // Backlog of incoming connection requests         
         .childOption(ChannelOption.SO_KEEPALIVE, true); 

        ChannelFuture f=null;
        if (port==null) try {
        	f = b.bind(Constants.DEFAULT_PEER_PORT).sync(); 
        	port=Constants.DEFAULT_PEER_PORT;
        } catch (Exception e) {
        	// failed so try with random port
        	port=0;
    	}
        
        if (f==null) {
        	f = b.bind(port).sync(); 
        	InetSocketAddress localAddress=(InetSocketAddress) f.channel().localAddress();
        	port=localAddress.getPort();
        }
   		System.out.println("Server started on port: "+getPort());
   	   
        // Wait until the server socket is closed.
        f.channel().closeFuture().sync();

    }
	
	protected Consumer<Message> getReceiveAction() {
		return receiveAction;
	}

	
	public static void main(String... args) throws Exception {
		NettyServer server=new NettyServer(8000);
		server.run();
	}

	private Integer getPort() {
		return port;
	}
}
