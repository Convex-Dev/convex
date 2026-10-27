package convex.dlfs;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.lattice.LatticeHost;
import convex.node.LatticeNode;
import convex.node.LatticePropagator;
import convex.node.LatticePropagatorConfig;
import convex.node.NodeConfig;

/**
 * Standalone entry point demonstrating a complete DLFS stack on a
 * {@link LatticeNode}.
 *
 * <p>This attaches {@link DLFSApplication} at its configured lattice path, puts the
 * host on TCP with a {@link LatticeNode}, routes one owner's drives and starts the
 * HTTP transport. JSON5 configuration controls persistent identity/storage,
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
		Keyword[] regionPath=config.getRegionPath();
		NodeConfig nodeConfig=config.getNodeConfig();
		String driveName=config.getDriveName();
		AStore store=openStore(config,keyPair);

		LatticeHost<Index<Keyword,ACell>> host;
		DLFSApplication application;
		try {
			host=LatticeHost.open(store);
			application=host.addApplication(new DLFSApplication(host,keyPair::signData,Utils::getCurrentTimestampCell,
				config.getMaxFutureTimestampSkew(),regionPath));
		} catch (IOException | RuntimeException e) {
			store.close();
			throw e;
		}
		LatticeNode<Index<Keyword,ACell>> node=new LatticeNode<>(host,nodeConfig)
			.setIdentity(DID.forKey(keyPair.getAccountKey()),keyPair);
		// The config file's node section also carries the group limits
		LatticePropagator propagator=node.createPropagator(LatticePropagatorConfig.from(nodeConfig));
		if (config.isPublicLatticeInbound()) node.serveAllInbound(propagator);

		// Starting the HTTP server is the last step, so a failure leaves nothing of it open
		DLFSDriveManager drives;
		DLFSServer server;
		try {
			node.launch();
			for (DLFSConfig.BootstrapPeer peer:config.getBootstrapPeers()) {
				if (!keyPair.getAccountKey().equals(peer.key())) {
					propagator.getConnectionManager().addPeer(peer.key(),peer.address());
				}
			}
			if (application.drives(keyPair.getAccountKey()).getOrCreateDrive(driveName)==null) {
				throw new IllegalStateException("Could not open or create drive: "+driveName);
			}
			drives=DLFSDriveManager.createRouter(()->{
				application.sync();
				try { host.checkpoint(); }
				catch (IOException e) { throw new UncheckedIOException(e); }
			});
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
			drives.sync();

			String writeAuth=config.getWriteAuthentication();
			server=DLFSConfig.WRITE_AUTH_NODE_KEY.equals(writeAuth)
				?DLFSServer.createWithAudience(drives,keyPair)
				:DLFSServer.create(drives);
			if (DLFSConfig.WRITE_AUTH_DENY.equals(writeAuth)) {
				server.setRequireAuthForWrites(true);
			}
			server.setBindHost(config.getHTTPBindHost());
			server.setMaxRequestSize(config.getMaxRequestBytes());
			server.start(config.getHTTPPort());
		} catch (IOException | InterruptedException | RuntimeException e) {
			// A failed start closes what it opened, in shutdown order
			try (store; node; host) {
				// Closing the host saves what it retains
			} catch (Exception closeFailure) {
				e.addSuppressed(closeFailure);
			}
			throw e;
		}

		System.out.println("DLFS owner: "+keyPair.getAccountKey().toChecksumHex());
		System.out.println("DLFS region: "+Arrays.toString(regionPath));
		System.out.println("DLFS drive: http://"+config.getHTTPBindHost()+":"+server.getPort()+
			"/dlfs/"+driveName+"/");
		System.out.println("Lattice node port: "+node.getListener().getPort()+
			(config.isPublicLatticeInbound()?" (public propagation view)":" (inbound denied)"));
		System.out.println("Bootstrap peers: "+config.getBootstrapPeers().size());
		System.out.println("Store: "+config.getStorePath());
		System.out.println("Press Ctrl+C to stop.");

		// Runs once; a second caller waits until it has finished
		Shutdown.Once shutdown=Shutdown.once(()->{
			// Closes the host (final checkpoint, sent to peers), then the node, then the store
			try (store; node; host) {
				try {
					server.close();
				} catch (RuntimeException e) {
					System.err.println("Failed to stop DLFS HTTP server: "+e.getMessage());
				}
				node.stopIngress();
				try {
					drives.sync();
				} catch (RuntimeException e) {
					System.err.println("Failed to publish DLFS state: "+e.getMessage());
				}
			} catch (Exception e) {
				// The first close to fail is the host's final save, if that failed
				System.err.println("DLFS shutdown failed: "+e.getMessage());
				for (Throwable also:e.getSuppressed()) System.err.println("  also: "+also.getMessage());
			}
		});

		// Runs in Convex's shutdown order, before transports and Etch files close
		Shutdown.addHook(Shutdown.SERVICE,shutdown);
		try {
			shutdown.await();
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
