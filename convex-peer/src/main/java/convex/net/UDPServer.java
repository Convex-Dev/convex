package convex.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.util.ThreadUtils;

public class UDPServer implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(UDPServer.class.getName());

	private static final int UDP_TIMEOUT = 3000;
	DatagramSocket socket;
	protected volatile boolean running=false;
	/** Set by the first close, which every later launch checks. Guarded by this. */
	private boolean closed;
	/** The receiving thread once launched. Guarded by this; read by tests. */
	Thread thread;

	public UDPServer() {
		try {
			socket= new DatagramSocket(null);
		} catch (SocketException e) {
			throw new Error(e);
		}
	}

	/**
	 * Binds the socket and starts receiving on a thread of its own.
	 *
	 * @param bindAddress Address to bind, or null for every address
	 * @param port Port to bind, or null or 0 for the default peer port if free, else any
	 * @throws IOException if the socket cannot be bound
	 * @throws IllegalStateException if this server has been closed
	 */
	public synchronized void launch(String bindAddress, Integer port) throws IOException {
		if (closed) throw new IllegalStateException("UDP server has been closed");
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

		// Before the thread starts, so a close from now on stops it
		running=true;
		thread=Thread.ofVirtual().name("udp-server").start(this::run);
	}

	public void run() {
		DatagramPacket packet = new DatagramPacket(new byte[65536],0);
		while (running) {
			try {
				socket.receive(packet);
				log.debug("Received {} bytes from {}",packet.getLength(),packet.getAddress());
			} catch (SocketTimeoutException e) {
				// Normal while idle: the loop checks whether it should still run
			} catch (IOException e) {
				// Closing the socket ends a receive under way
				if (!running || socket.isClosed()) return;
				log.debug("UDP receive failed: {}",e.getMessage());
			}
		}
	}

	/**
	 * Stops receiving, closes the socket and waits for the receiving thread to end. A
	 * closed server does not launch again.
	 */
	@Override
	public void close() {
		Thread t;
		synchronized (this) {
			if (closed) return;
			closed=true;
			running=false;
			t=thread;
		}
		socket.close();
		// Outside the lock, which the receiving thread never takes
		if (t!=null) ThreadUtils.joinUninterruptibly(t);
	}

	public static void main(String... args) throws IOException {
		try (UDPServer server=new UDPServer()) {
			server.launch(null, 18888);
			server.sendMessage(Blobs.empty());
		}
	}

	private void sendMessage(Blob b) {
		DatagramPacket packet=new DatagramPacket(b.getBytes(),b.size());
		packet.setAddress(InetAddress.getLoopbackAddress());
		packet.setPort(18888);
		try {
			socket.send(packet);
		} catch (IOException e) {
			log.warn("Unable to send UDP message",e);
		}
	}
}
