package convex.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;

import convex.core.Constants;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.util.ThreadUtils;

public class UDPServer {

	private static final int UDP_TIMEOUT = 3000;
 DatagramSocket socket;
	protected boolean running=false;
	
	public UDPServer() {
		try {
			socket= new DatagramSocket(null);
		} catch (SocketException e) {
			throw new Error(e);
		}
	}
	
	public void launch(String bindAddress, Integer port) throws IOException {
		socket.setSoTimeout(UDP_TIMEOUT);
		bindAddress = (bindAddress == null) ? "::" : bindAddress;
		InetSocketAddress address;
		if (port==null) port=0;
		if (port==0) {
			try {
				address = new InetSocketAddress(bindAddress, Constants.DEFAULT_PEER_PORT);
				socket.bind(address);
			} catch (IOException e) {
				// try again with random port
				address = new InetSocketAddress(bindAddress, 0);
				socket.bind(address);
			}
		} else {
			address = new InetSocketAddress(bindAddress, port);
			socket.bind(address);
		}
		
		ThreadUtils.runVirtual(this::run);
	}
	
	public void run() {
		running=true;
		
		DatagramPacket packet = new DatagramPacket(new byte[65536],0);
		while (running) {
			try {
				socket.receive(packet);
				System.out.println("Received "+packet.getLength() + " bytes from "+packet.getAddress());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	public void close() {
		running=false;
	}
	
	public static void main(String... args) throws IOException {
		UDPServer server=new UDPServer();
		server.launch(null, 18888);
		
		server.sendMessage(Blobs.empty());
		
		server.close();
	}

	private void sendMessage(Blob b) {
		DatagramPacket packet=new DatagramPacket(b.getBytes(),b.size());
		packet.setAddress(InetAddress.getLoopbackAddress());
		packet.setPort(18888);
		try {
			socket.send(packet);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
