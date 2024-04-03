package examples;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import convex.core.util.Utils;

public class IPTest {
	
	public static final int PORT=6666;
	private static boolean running=true;

	@SuppressWarnings({ "unused", "resource" })
	public static void main (String[] args) throws IOException {
		System.out.println("Local host: "+InetAddress.getLocalHost());
		System.out.println("Loopback: "+InetAddress.getLoopbackAddress().getHostAddress());
		
		new Thread(SERVER).start();
		
		//InetAddress address = InetAddress.getByName("::1");
	   // Socket socket = new Socket(address, PORT);
		//System.out.println("Socket: "+socket);
		
		//InetSocketAddress sa=new InetSocketAddress("[::1]",PORT);
		//System.out.println("Socket Address: "+sa);
		
		InetSocketAddress sa2=Utils.toInetSocketAddress("::1:6666");
		System.out.println("Utils.toInetSocketAddress: "+sa2);
		Socket socket = new Socket();
		socket.connect(sa2);
		int ix=socket.getInputStream().read();
		System.out.println(ix);
		
		running=false;
	}
	
	private static Runnable SERVER=() ->{
		try (ServerSocket serverSocket = new ServerSocket(PORT)) {
			serverSocket.setSoTimeout(10000);
			while(running) {
				Socket sock=serverSocket.accept();
				System.err.println("Accepted: "+sock);
				sock.close();
			}	
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		} 

		System.err.println("Stopped Server");
	};
	
}
