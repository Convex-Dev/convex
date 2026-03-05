package convex.cli.dlfs;

import java.io.File;
import java.nio.file.FileSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.ACommand;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.Main;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.dlfs.DLFSServer;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.fs.DLFS;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Mixin;

/**
 * Starts a DLFS WebDAV server backed by lattice replication via NodeServer.
 *
 * <p>Drives are connected to the NodeServer's cursor at {@code :fs},
 * so changes propagate to peers automatically.
 *
 * <p>Usage:
 * <pre>
 * convex dlfs start --port 8080 --node-port 19888 --drive home
 * </pre>
 */
@Command(name="start",
	mixinStandardHelpOptions=true,
	description="Starts a DLFS WebDAV server with lattice replication via NodeServer.")
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

	@Override
	public Main cli() {
		return parent.cli();
	}

	@Override
	public void execute() throws InterruptedException {
		// Resolve or generate key pair
		AKeyPair keyPair = resolveKeyPair();
		inform("Key: " + keyPair.getAccountKey().toChecksumHex());

		// Create store
		AStore store = createStore();

		// Build NodeConfig
		NodeConfig config = buildNodeConfig();

		// Create and configure NodeServer
		NodeServer<?> nodeServer = new NodeServer<>(Lattice.ROOT, store, config);
		nodeServer.setMergeContext(LatticeContext.create(null, keyPair));

		try {
			nodeServer.launch();
		} catch (Exception e) {
			throw new CLIError(ExitCodes.CONFIG, "Failed to launch NodeServer: " + e.getMessage(), e);
		}
		inform("Lattice node started on port " + nodeServer.getPort());

		// Connect to remote peers
		connectPeers(nodeServer);

		// Navigate cursor to this owner's drives map: :fs → accountKey → :value
		// This traverses OwnerLattice → SignedLattice → MapLattice<DLFSLattice>
		AccountKey accountKey = keyPair.getAccountKey();
		ALatticeCursor<?> drivesCursor = nodeServer.getCursor()
			.path(Keywords.FS, accountKey, Keywords.VALUE);

		// Create DLFSServer with drives backed by the lattice cursor
		DLFSServer dlfsServer = DLFSServer.create(keyPair);

		for (String driveName : driveNames) {
			FileSystem fs = DLFS.connect(drivesCursor, Strings.create(driveName));
			dlfsServer.getDriveManager().seedDrive(null, driveName, fs);
			inform("Drive '" + driveName + "' connected to lattice at :fs/" + accountKey.toHexString(6) + ".../" + driveName);
		}

		dlfsServer.start(port);
		informSuccess("DLFS WebDAV server running on http://localhost:" + dlfsServer.getPort() + "/dlfs/");
		inform("Connect with: curl http://localhost:" + dlfsServer.getPort() + "/dlfs/" + driveNames[0] + "/");

		cli().notifyStartup();

		// Add shutdown hooks
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			dlfsServer.close();
			try {
				nodeServer.close();
			} catch (Exception e) {
				log.warn("Error closing NodeServer", e);
			}
		}));

		// Block until node server shuts down
		while (nodeServer.isRunning()) {
			Thread.sleep(1000);
		}
	}

	private AKeyPair resolveKeyPair() {
		if (publicKey != null && !publicKey.isBlank()) {
			AKeyPair kp = storeMixin.loadKeyFromStore(publicKey, () -> keyMixin.getKeyPassword());
			if (kp == null) {
				throw new CLIError(ExitCodes.CONFIG, "Key not found in keystore: " + publicKey);
			}
			return kp;
		}
		AKeyPair kp = AKeyPair.generate();
		informWarning("No key specified, generated ephemeral key pair");
		inform("  Public key: " + kp.getAccountKey().toChecksumHex());
		inform("  Ed25519 seed: " + kp.getSeed().toHexString());
		return kp;
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

	private void connectPeers(NodeServer<?> nodeServer) {
		if (peers == null) return;
		for (String peer : peers) {
			try {
				String[] parts = peer.split(":");
				String host = parts[0];
				int peerPort = Integer.parseInt(parts[1]);
				java.net.InetSocketAddress addr = new java.net.InetSocketAddress(host, peerPort);
				convex.api.Convex connection = convex.api.ConvexRemote.connect(addr);
				convex.core.data.AccountKey peerKey = convex.core.data.AccountKey.dummy("0000");
				nodeServer.getPropagator().addPeer(peerKey, connection);
				inform("Connected to peer: " + peer);
			} catch (Exception e) {
				informWarning("Failed to connect to peer " + peer + ": " + e.getMessage());
			}
		}
	}
}
