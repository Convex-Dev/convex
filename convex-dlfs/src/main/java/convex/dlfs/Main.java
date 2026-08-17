package convex.dlfs;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.etch.EtchStore;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
import convex.node.NodeConfig;
import convex.node.NodeServer;

/**
 * Standalone entry point demonstrating a complete NodeServer-hosted DLFS stack.
 *
 * <p>This boots {@link NodeServer} with the complete {@link Lattice#ROOT}, attaches
 * {@link DLFSApplication} at {@code :fs}, routes one owner's drives and starts the
 * HTTP transport. A temporary Etch store provides zero-setup store-backed refs.
 * WebDAV remains loopback-only; inbound lattice replication remains
 * deny-by-default unless {@code --serve-inbound} is supplied explicitly.</p>
 *
 * <pre>{@code
 * java -cp convex.jar convex.dlfs.Main
 * java -cp convex.jar convex.dlfs.Main --http-port 8080 --node-port 19888 \
 *     --drive home --serve-inbound
 * }</pre>
 */
public final class Main {

	private static final int DEFAULT_PORT=8080;
	private static final int DEFAULT_NODE_PORT=19888;
	private static final String DEFAULT_DRIVE="home";

	private Main() {
	}

	/**
	 * Starts a standalone local DLFS server and runs until the JVM is interrupted.
	 *
	 * @param args Optional HTTP/node ports, drive name and inbound serving policy
	 * @throws IOException If local application persistence fails
	 * @throws InterruptedException If the process is interrupted
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		Config config=parseArguments(args);
		if (config==null) return;

		AKeyPair keyPair=AKeyPair.generate();
		AStore store=EtchStore.createTemp("convex-dlfs");
		NodeServer<Index<Keyword,ACell>> node=
			new NodeServer<>(Lattice.ROOT,store,NodeConfig.port(config.nodePort()));
		node.setMergeContext(LatticeContext.create(null,keyPair));
		if (config.serveInbound()) {
			node.setInboundPropagatorSelector(connection->node.getPropagator());
		}
		try {
			node.launch();
		} catch (IOException | InterruptedException | RuntimeException e) {
			store.close();
			throw e;
		}

		DLFSApplication<Index<Keyword,ACell>> application=DLFSApplication.connect(
			node.getRootComponent(),Keywords.FS);
		DLFSDriveManager drives=new DLFSDriveManager(
			application.drives(keyPair.getAccountKey()));
		if (!drives.createDrive(null,config.driveName())) {
			closeNode(node);
			store.close();
			throw new IllegalStateException("Could not create drive: "+config.driveName());
		}
		try {
			application.sync();
		} catch (RuntimeException e) {
			closeNode(node);
			store.close();
			throw e;
		}

		DLFSServer server=DLFSServer.create(drives);
		try {
			server.start(config.httpPort());
		} catch (RuntimeException e) {
			closeNode(node);
			store.close();
			throw e;
		}

		System.out.println("DLFS owner: "+keyPair.getAccountKey().toChecksumHex());
		System.out.println("DLFS drive: http://localhost:"+server.getPort()+
			"/dlfs/"+config.driveName()+"/");
		System.out.println("Lattice node port: "+node.getPort()+
			(config.serveInbound()?" (serving inbound replication)":" (inbound denied)"));
		System.out.println("Temporary Etch-backed example; press Ctrl+C to stop.");

		CountDownLatch stopped=new CountDownLatch(1);
		AtomicBoolean closing=new AtomicBoolean();
		Runnable shutdown=()->{
			if (!closing.compareAndSet(false,true)) return;
			server.close();
			try {
				application.sync();
			} catch (Exception e) {
				System.err.println("Failed to publish DLFS state: "+e.getMessage());
			}
			closeNode(node);
			try {
				application.flush();
			} catch (Exception e) {
				System.err.println("Failed to flush DLFS store: "+e.getMessage());
			}
			store.close();
			stopped.countDown();
		};

		Runtime.getRuntime().addShutdownHook(new Thread(shutdown,"convex-dlfs-shutdown"));
		try {
			stopped.await();
		} finally {
			shutdown.run();
		}
	}

	private record Config(int httpPort, int nodePort, String driveName,
			boolean serveInbound) {
	}

	private static Config parseArguments(String[] args) {
		int httpPort=DEFAULT_PORT;
		int nodePort=DEFAULT_NODE_PORT;
		String driveName=DEFAULT_DRIVE;
		boolean serveInbound=false;
		for (int i=0; i<args.length; i++) {
			String arg=args[i];
			switch (arg) {
				case "--help", "-h" -> {
					printUsage();
					return null;
				}
				case "--http-port" -> httpPort=parsePort(requireValue(args,++i,arg));
				case "--node-port" -> nodePort=parsePort(requireValue(args,++i,arg));
				case "--drive" -> driveName=requireValue(args,++i,arg);
				case "--serve-inbound" -> serveInbound=true;
				default -> throw new IllegalArgumentException("Unknown argument: "+arg);
			}
		}
		if (!DLFSDriveManager.isValidDriveName(driveName)) {
			throw new IllegalArgumentException("Invalid drive name: "+driveName);
		}
		return new Config(httpPort,nodePort,driveName,serveInbound);
	}

	private static String requireValue(String[] args, int index, String option) {
		if (index>=args.length) throw new IllegalArgumentException("Missing value for "+option);
		return args[index];
	}

	private static int parsePort(String value) {
		int port;
		try {
			port=Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid port: "+value,e);
		}
		if (port<0 || port>65535) throw new IllegalArgumentException("Invalid port: "+value);
		return port;
	}

	private static void closeNode(NodeServer<?> node) {
		try {
			node.close();
		} catch (Exception e) {
			System.err.println("Failed to close lattice node: "+e.getMessage());
		}
	}

	private static void printUsage() {
		System.out.println("Usage: java -cp convex.jar convex.dlfs.Main [options]");
		System.out.println("  --http-port <port>  WebDAV/MCP port (default 8080)");
		System.out.println("  --node-port <port>  lattice replication port (default 19888)");
		System.out.println("  --drive <name>      initial drive (default home)");
		System.out.println("  --serve-inbound     serve the primary lattice view to inbound peers");
		System.out.println("Starts a loopback HTTP, temporary Etch-backed, NodeServer-hosted DLFS system.");
	}
}
