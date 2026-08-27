package convex.dlfs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.ALattice;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.KeyedLattice;
import convex.node.NodeServer;
import convex.node.LatticePropagator;

/**
 * Standalone entry point demonstrating a complete NodeServer-hosted DLFS stack.
 *
 * <p>This boots {@link NodeServer}, attaches {@link DLFSApplication} at its
 * configured lattice path, routes one owner's drives and starts the HTTP
 * transport. JSON5 configuration controls persistent identity/storage,
 * identity-aware bootstrap peers and explicit network exposure policy.
 *
 * <pre>{@code
 * java -cp convex.jar convex.dlfs.Main --config dlfs-config.json5
 * }</pre>
 */
public final class Main {

	private Main() {
	}

	/**
	 * Starts a standalone local DLFS server and runs until the JVM is interrupted.
	 *
	 * @param args Optional JSON5 config path and simple development overrides
	 * @throws IOException If local application persistence fails
	 * @throws InterruptedException If the process is interrupted
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		LaunchOptions options=parseArguments(args);
		if (options==null) return;
		DLFSConfig config=loadConfig(options);
		config.validate();

		AKeyPair keyPair=config.getKeyPair();
		if (keyPair==null) {
			keyPair=AKeyPair.generate();
			System.err.println("Generated an ephemeral DLFS node identity; configure node.keypair "
				+"before relying on restart or peer identity.");
		}
		AStore store=openStore(config,keyPair);
		Keyword[] regionPath=config.getRegionPath();
		KeyedLattice root=createRoot(regionPath);
		NodeServer<Index<Keyword,ACell>> node=
			new NodeServer<>(root,store,config.getNodeConfig());
		LatticeContext context=LatticeContext.create(null,keyPair);
		node.setMergeContext(context);
		LatticePropagator propagator=new LatticePropagator(
			store,root,value -> value,config.getNodeConfig());
		propagator.setMergeContext(context);
		propagator.setTransportKeyPair(keyPair);
		node.addPropagator(propagator);
		if (config.isPublicLatticeInbound()) {
			node.setInboundPropagatorSelector(connection->propagator);
		}
		try {
			node.launch();
		} catch (IOException | InterruptedException | RuntimeException e) {
			store.close();
			throw e;
		}
		for (DLFSConfig.BootstrapPeer peer:config.getBootstrapPeers()) {
			if (!keyPair.getAccountKey().equals(peer.key())) {
				propagator.getConnectionManager().addPeer(peer.key(),peer.address());
			}
		}

		DLFSApplication<Index<Keyword,ACell>> application=DLFSApplication.connect(
			node.getRootComponent(),regionPath);
		DLFSDrives localDrives=application.drives(keyPair.getAccountKey());
		String driveName=config.getDriveName();
		if (!localDrives.driveNames().contains(driveName)
				&&(localDrives.createDrive(driveName)==null)) {
			closeNode(node);
			store.close();
			throw new IllegalStateException("Could not create drive: "+driveName);
		}
		DLFSDriveManager drives=DLFSDriveManager.createRouter();
		for (DLFSConfig.DriveMount mount:config.getDriveMounts()) {
			var owner=(mount.owner()==null)?keyPair.getAccountKey():mount.owner();
			DLFSDrives ownerDrives=application.drives(owner);
			String identity=mount.identity();
			if (DLFSConfig.LOCAL_IDENTITY.equals(identity)) {
				identity=DID.forKey(keyPair.getAccountKey()).toString();
			}
			if (identity==null) {
				drives.mountAnonymous(ownerDrives);
			} else {
				drives.mount(identity,ownerDrives);
			}
		}
		try {
			application.sync();
		} catch (RuntimeException e) {
			closeNode(node);
			store.close();
			throw e;
		}

		String writeAuth=config.getWriteAuthentication();
		DLFSServer server=DLFSConfig.WRITE_AUTH_NODE_KEY.equals(writeAuth)
			?DLFSServer.createWithAudience(drives,keyPair)
			:DLFSServer.create(drives);
		if (DLFSConfig.WRITE_AUTH_DENY.equals(writeAuth)) {
			server.setRequireAuthForWrites(true);
		}
		server.setBindHost(config.getHTTPBindHost());
		server.setMaxRequestSize(config.getMaxRequestBytes());
		try {
			server.start(config.getHTTPPort());
		} catch (RuntimeException e) {
			closeNode(node);
			store.close();
			throw e;
		}

		System.out.println("DLFS owner: "+keyPair.getAccountKey().toChecksumHex());
		System.out.println("DLFS region: "+Arrays.toString(regionPath));
		System.out.println("DLFS drive: http://"+config.getHTTPBindHost()+":"+server.getPort()+
			"/dlfs/"+driveName+"/");
		System.out.println("Lattice node port: "+node.getPort()+
			(config.isPublicLatticeInbound()?" (public propagation view)":" (inbound denied)"));
		System.out.println("Bootstrap peers: "+config.getBootstrapPeers().size());
		System.out.println("Store: "+config.getStorePath());
		System.out.println("Press Ctrl+C to stop.");

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

	private static DLFSConfig loadConfig(LaunchOptions options) throws IOException {
		String configured=options.configPath();
		if ((configured==null)||configured.isBlank()) configured=System.getenv("CONVEX_DLFS_CONFIG");
		DLFSConfig result=((configured==null)||configured.isBlank())
			?DLFSConfig.create():DLFSConfig.load(Path.of(configured.trim()));
		if (options.httpPort()!=null) result=result.withHTTPPort(options.httpPort());
		if (options.nodePort()!=null) result=result.withNodePort(options.nodePort());
		if (options.driveName()!=null) result=result.withDriveName(options.driveName());
		if (options.serveInbound()) result=result.withPublicLatticeInbound();
		return result;
	}

	private record LaunchOptions(String configPath,Integer httpPort,Integer nodePort,
			String driveName,boolean serveInbound) {
	}

	private static LaunchOptions parseArguments(String[] args) {
		String configPath=null;
		Integer httpPort=null;
		Integer nodePort=null;
		String driveName=null;
		boolean serveInbound=false;
		for (int i=0; i<args.length; i++) {
			String arg=args[i];
			switch (arg) {
				case "--help", "-h" -> {
					printUsage();
					return null;
				}
				case "--config", "-c" -> configPath=requireValue(args,++i,arg);
				case "--http-port" -> httpPort=parsePort(requireValue(args,++i,arg));
				case "--node-port" -> nodePort=parsePort(requireValue(args,++i,arg));
				case "--drive" -> driveName=requireValue(args,++i,arg);
				case "--serve-inbound" -> serveInbound=true;
				default -> throw new IllegalArgumentException("Unknown argument: "+arg);
			}
		}
		if ((driveName!=null)&&!DLFSDriveManager.isValidDriveName(driveName)) {
			throw new IllegalArgumentException("Invalid drive name: "+driveName);
		}
		return new LaunchOptions(configPath,httpPort,nodePort,driveName,serveInbound);
	}

	private static AStore openStore(DLFSConfig config,AKeyPair keyPair) throws IOException {
		String storePath=config.getStorePath();
		var etchConfig=config.getEtchConfigFor(keyPair);
		if ((etchConfig!=null)&&(etchConfig.getCipherMode()!=convex.etch.EtchConfig.CipherMode.NONE)
				&&(etchConfig.getPublicKeyHint()==null)) {
			etchConfig=etchConfig.withPublicKeyHint(keyPair.getAccountKey());
		}
		return switch (storePath.toLowerCase()) {
			case "temp" -> (etchConfig==null)
				?EtchStore.createTemp("convex-dlfs")
				:EtchStore.createTemp("convex-dlfs",etchConfig);
			case "memory" -> {
				if (etchConfig!=null) {
					throw new IllegalArgumentException("node.etch cannot be used with a memory store");
				}
				yield new MemoryStore();
			}
			default -> (etchConfig==null)
				?EtchStore.create(new File(storePath))
				:EtchStore.create(new File(storePath),etchConfig);
		};
	}

	private static KeyedLattice createRoot(Keyword[] path) {
		if ((path.length==1)&&Keywords.FS.equals(path[0])) return Lattice.ROOT;
		if (isStandardRootKey(path[0])) {
			throw new IllegalArgumentException("DLFS path conflicts with standard lattice region: "+path[0]);
		}
		ALattice<?> nested=DLFSRegion.LATTICE;
		for (int i=path.length-1;i>0;i--) {
			nested=KeyedLattice.create(path[i],nested);
		}
		return Lattice.ROOT.addLattice(path[0],nested);
	}

	private static boolean isStandardRootKey(Keyword key) {
		return Keywords.DATA.equals(key)||Keywords.FS.equals(key)||Keywords.KV.equals(key)
			||Keywords.QUEUE.equals(key)||Keywords.P2P.equals(key)
			||convex.lattice.LocalLattice.KEY_LOCAL.equals(key);
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
		System.out.println("  -c, --config <file> JSON5 configuration (or CONVEX_DLFS_CONFIG)");
		System.out.println("  --http-port <port>  override WebDAV/MCP port");
		System.out.println("  --node-port <port>  override lattice replication port");
		System.out.println("  --drive <name>      override initial drive");
		System.out.println("  --serve-inbound     serve the primary lattice view to inbound peers");
		System.out.println("Defaults to loopback HTTP, temporary Etch and denied inbound lattice access.");
	}
}
