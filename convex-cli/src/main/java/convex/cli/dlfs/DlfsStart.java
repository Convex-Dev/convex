package convex.cli.dlfs;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.cli.ACommand;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.Main;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.util.Shutdown;
import convex.etch.EtchStore;
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSDriveManager;
import convex.dlfs.DLFSDrives;
import convex.dlfs.DLFSServer;
import convex.lattice.LatticeHost;
import convex.gui.utils.Toolkit;
import convex.gui.utils.TrayManager;
import convex.node.NodeConfig;
import convex.node.LatticeNode;
import convex.node.LatticePropagator;
import convex.node.LatticePropagatorConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Mixin;

/**
 * Starts a DLFS WebDAV server backed by lattice replication on a {@link LatticeNode}.
 *
 * <p>Drives belong to the DLFS application on the node's host at {@code :fs}, so
 * synced changes propagate to peers.
 *
 * <p>Usage:
 * <pre>
 * convex dlfs start --port 8080 --node-port 19888 --drive home
 * </pre>
 */
@Command(name="start",
	mixinStandardHelpOptions=true,
	description="Starts a DLFS WebDAV server with lattice replication.")
public class DlfsStart extends ACommand {

	private static final Logger log = LoggerFactory.getLogger(DlfsStart.class);

	@ParentCommand
	private Dlfs parent;

	@Mixin
	protected KeyStoreMixin storeMixin;

	@Mixin
	protected KeyMixin keyMixin;

	@Option(names={"--port"},
		defaultValue="8080",
		description="WebDAV server port. Default: ${DEFAULT-VALUE}")
	private int port;

	@Option(names={"--node-port"},
		defaultValue="19888",
		description="Lattice node port for replication. Default: ${DEFAULT-VALUE}")
	private int nodePort;

	@Option(names={"--drive"},
		defaultValue="home",
		description="Drive name(s) to create. Default: ${DEFAULT-VALUE}")
	private String[] driveNames;

	@Option(names={"--peer"},
		description="Remote peer address(es) to connect to for replication (host:port).")
	private String[] peers;

	@Option(names={"--public-key"},
		defaultValue="",
		description="Public key from keystore for signing. If empty, a new key is generated.")
	private String publicKey;

	@Option(names={"--etch"},
		description="Etch store file for persistence. If omitted, uses in-memory store.")
	private String etchFile;

	@Option(names={"--url"},
		description="Public URL for this node (enables P2P discovery). Must be reachable from the internet.")
	private String publicUrl;

	@Option(names={"--no-tray"},
		description="Disable the system tray icon.")
	private boolean noTray;

	@Override
	public Main cli() {
		return parent.cli();
	}

	@Override
	public void execute() throws InterruptedException {
		if (etchFile!=null && !etchFile.isBlank() && getKeySpec()==null) {
			throw new CLIError(ExitCodes.CONFIG,
				"Persistent --etch storage requires a stable key supplied with --public-key or --key");
		}
		if (driveNames.length>DLFSDriveManager.MAX_DRIVES_PER_IDENTITY) {
			throw new CLIError(ExitCodes.CONFIG, "Too many initial drives");
		}
		for (String driveName: driveNames) {
			if (!DLFSDriveManager.isValidDriveName(driveName)) {
				throw new CLIError(ExitCodes.CONFIG, "Invalid drive name: " + driveName);
			}
		}
		// Resolve or generate key pair
		AKeyPair keyPair = resolveKeyPair();
		inform("Key: " + keyPair.getAccountKey().toChecksumHex());

		// Create store
		AStore store = createStore();

		// Build NodeConfig
		NodeConfig config = buildNodeConfig();

		// Register DLFS before starting any incoming delivery.
		LatticeHost<Index<Keyword,ACell>> host;
		DLFSApplication application;
		try {
			host=LatticeHost.open(store);
			application=host.addApplication(new DLFSApplication(host,keyPair::signData));
		} catch (IOException e) {
			store.close();
			throw new CLIError(ExitCodes.CONFIG, "Failed to open node store: " + e.getMessage(), e);
		}
		LatticeNode<Index<Keyword,ACell>> node=new LatticeNode<>(host,config)
			.setIdentity(DID.forKey(keyPair.getAccountKey()),keyPair);
		// The command's node config also carries the group limits
		LatticePropagator propagator=node.createPropagator(LatticePropagatorConfig.from(config));

		try {
			node.launch(); // closes the node if it fails
		} catch (Exception e) {
			store.close();
			throw new CLIError(ExitCodes.CONFIG, "Failed to launch lattice node: " + e.getMessage(), e);
		}
		inform("Lattice transport started on port " + node.getListener().getPort());

		// Connect to remote peers
		connectPeers(propagator);

		// Serve the local owner's drives through the application's registered region.
		AccountKey accountKey = keyPair.getAccountKey();
		DLFSDriveManager driveManager=createDriveManager(host,application,accountKey);
		DLFSServer dlfsServer = DLFSServer.createWithAudience(driveManager,keyPair);
		// The CLI currently exposes shared anonymous drives on the loopback-only server.
		// Authentication-aware per-user drive provisioning is a separate product concern.
		dlfsServer.setRequireAuthForWrites(false);

		try {
			for (String driveName : driveNames) {
				boolean created=driveManager.createDrive(null, driveName);
				if (!created && driveManager.getDrive(null, driveName)==null) {
					throw new CLIError(ExitCodes.CONFIG, "Cannot create excess drive: " + driveName);
				}
				inform("Drive '" + driveName + "' connected to lattice at :fs/" + accountKey.toHexString(6) + ".../" + driveName);
			}
			driveManager.sync();
			dlfsServer.start(port);
		} catch (RuntimeException e) {
			dlfsServer.close();
			try {
				node.close();
			} catch (Exception closeError) {
				e.addSuppressed(closeError);
			}
			store.close();
			throw e;
		}
		informSuccess("DLFS WebDAV server running on http://localhost:" + dlfsServer.getPort() + "/dlfs/");
		if (driveNames.length > 0) {
			inform("Connect with: curl http://localhost:" + dlfsServer.getPort() + "/dlfs/" + driveNames[0] + "/");
		}

		cli().notifyStartup();

		AtomicBoolean closing=new AtomicBoolean();
		CountDownLatch closed=new CountDownLatch(1);
		Runnable shutdown=()->{
			if (!closing.compareAndSet(false,true)) {
				// Another thread is closing: wait, so neither process exit nor the
				// Etch shutdown hook can overtake its final flush.
				try { closed.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				return;
			}
			// Closes the host (final checkpoint, sent to peers), then the node, then the store
			try (store; node; host) {
				try {
					dlfsServer.close();
				} catch (RuntimeException e) {
					log.warn("Error stopping DLFS HTTP server",e);
				}
				node.stopIngress();
				try {
					driveManager.sync();
				} catch (RuntimeException e) {
					log.warn("Error publishing DLFS state",e);
				}
			} catch (Exception e) {
				// The first close to fail is the host's final save, if that failed
				log.warn("Error shutting down DLFS",e);
			} finally {
				closed.countDown();
			}
		};

		// JVM and tray shutdown use the same idempotent close path. Registered in
		// Convex's shutdown order so it completes before transports and Etch files close.
		Shutdown.addHook(Shutdown.SERVICE,shutdown);
		String webUrl="http://localhost:"+dlfsServer.getPort()+"/dlfs/";
		boolean trayInstalled=!noTray&&TrayManager.install(
			"Convex DLFS on port "+dlfsServer.getPort(),"Open Web",()->Toolkit.launchBrowser(webUrl),shutdown);

		try {
			// Block until node server shuts down
			while (node.getServer().isRunning()) {
				Thread.sleep(1000);
			}
		} finally {
			if (trayInstalled) TrayManager.remove();
			Shutdown.removeHook(Shutdown.SERVICE,shutdown);
			shutdown.run();
		}
	}

	/** Composes local routing with the CLI's explicit publication policy. */
	static DLFSDriveManager createDriveManager(LatticeHost<Index<Keyword,ACell>> host,
			DLFSApplication application,AccountKey accountKey) {
		DLFSDrives ownerDrives=application.drives(accountKey);
		return DLFSDriveManager.createRouter(()->{
			application.sync();
			try { host.checkpoint(); }
			catch (IOException e) { throw new java.io.UncheckedIOException(e); }
		}).mountAnonymous(ownerDrives)
			.mount(DID.forKey(accountKey).toString(),ownerDrives);
	}

	private AKeyPair resolveKeyPair() {
		String keySpec=getKeySpec();
		if (keySpec!=null) {
			AKeyPair kp = storeMixin.loadKeyFromStore(keySpec, () -> keyMixin.getKeyPassword());
			if (kp == null) {
				throw new CLIError(ExitCodes.CONFIG, "Key not found in keystore: " + keySpec);
			}
			return kp;
		}
		AKeyPair kp = AKeyPair.generate();
		informWarning("No key specified, generated ephemeral key pair");
		inform("  Public key: " + kp.getAccountKey().toChecksumHex());
		inform("  Ed25519 seed: " + kp.getSeed().toHexString());
		return kp;
	}

	private String getKeySpec() {
		if (publicKey!=null && !publicKey.isBlank()) return publicKey;
		String inherited=keyMixin.getPublicKey();
		return (inherited==null || inherited.isBlank())?null:inherited;
	}

	private AStore createStore() {
		if (etchFile != null && !etchFile.isBlank()) {
			try {
				return EtchStore.create(new File(etchFile));
			} catch (Exception e) {
				throw new CLIError(ExitCodes.CONFIG, "Failed to open Etch store: " + etchFile, e);
			}
		}
		return new MemoryStore();
	}

	private NodeConfig buildNodeConfig() {
		convex.core.data.AMap<convex.core.data.AString, convex.core.data.ACell> configMap =
			convex.core.data.Maps.of(
				NodeConfig.PORT, convex.core.data.prim.CVMLong.create(nodePort)
			);
		if (publicUrl != null && !publicUrl.isBlank()) {
			configMap = configMap.assoc(NodeConfig.URL, Strings.create(publicUrl));
		}
		return NodeConfig.create(configMap);
	}

	private void connectPeers(LatticePropagator propagator) {
		if (peers == null) return;
		for (int i = 0; i < peers.length; i++) {
			String peer = peers[i];
			try {
				String[] parts = peer.split(":");
				if (parts.length != 2) {
					informWarning("Invalid --peer address (expected host:port): " + peer);
					continue;
				}
				String host = parts[0];
				int peerPort = Integer.parseInt(parts[1]);
				java.net.InetSocketAddress addr = new java.net.InetSocketAddress(host, peerPort);
				convex.api.Convex connection = convex.api.ConvexRemote.connect(addr);
				// Placeholder key, unique per peer so connections don't collide. Since we
				// choose to trust these peers the exact key may not matter, but ideally
				// this would be the peer's real key from a handshake. See #629
				AccountKey peerKey = AccountKey.dummy(Integer.toHexString(i + 1));
				propagator.addPeer(peerKey, connection);
				inform("Connected to peer: " + peer);
			} catch (Exception e) {
				informWarning("Failed to connect to peer " + peer + ": " + e.getMessage());
			}
		}
	}
}
