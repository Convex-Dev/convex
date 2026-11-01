package convex.cli.p2p;

import java.io.File;
import java.io.IOException;

import convex.cli.ACommand;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.Main;
import convex.cli.mixins.KeyMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.util.ConfigUtils;
import convex.core.util.FileUtils;
import convex.etch.EtchStore;
import convex.node.NodeConfig;
import convex.p2p.P2PNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Starts a public P2P lattice node, joining the network through any seed nodes.
 * Options override the JSON5 configuration that {@code P2PNode} also reads.
 *
 * <p>Usage:
 * <pre>
 * convex p2p start --key 0xabc --etch node.etch --seed 0x1234...@tcp://peer.example.com:18888
 * </pre>
 */
@Command(name="start",
	mixinStandardHelpOptions=true,
	description="Starts a P2P lattice node, joining the network through any seed nodes.")
public class P2PStart extends ACommand {

	@ParentCommand
	private P2P parent;

	@Mixin
	protected KeyStoreMixin storeMixin;

	@Mixin
	protected KeyMixin keyMixin;

	@Option(names={"--config"},
		description="JSON5 node configuration file: port, url, seeds, maxNodeRecords, store and keypair.")
	private String configFile;

	@Option(names={"--port"},
		description="Listener port. 0 picks a free port; negative accepts no connections.")
	private Integer port;

	@Option(names={"--url"},
		description="Public URL to advertise. Must be reachable from the internet.")
	private String publicUrl;

	@Option(names={"--seed"},
		description="Seed node to join through, as <key>@<url>, e.g. 0x1234...@tcp://peer.example.com:18888. "
			+"Repeatable, and added to the configured seeds.")
	private String[] seeds;

	@Option(names={"--etch"},
		description="Etch store file for persistence; needs a stable key. If omitted, uses a temporary store.")
	private String etchFile;

	@Override
	public Main cli() {
		return parent.cli();
	}

	@Override
	public void execute() throws InterruptedException {
		AMap<AString,ACell> settings=settings();
		AString storeFile=(etchFile!=null && !etchFile.isBlank())
			? Strings.create(etchFile) : RT.ensureString(settings.get(P2PNode.STORE));
		AKeyPair keyPair=resolveKeyPair(settings.get(P2PNode.KEYPAIR));
		if (storeFile!=null && keyPair==null) {
			throw new CLIError(ExitCodes.CONFIG,
				"A persistent store requires a stable key supplied with --key or the configured keypair");
		}
		NodeConfig config=NodeConfig.create(settings.dissoc(P2PNode.STORE).dissoc(P2PNode.KEYPAIR));
		try {
			P2PNode.seeds(config);
		} catch (IllegalArgumentException e) {
			throw new CLIError(ExitCodes.CONFIG,e.getMessage(),e);
		}
		if (keyPair==null) {
			keyPair=AKeyPair.generate();
			informWarning("No key specified, generated ephemeral key pair");
		}
		inform("Node key: "+keyPair.getAccountKey().toChecksumHex());

		AStore store=createStore(storeFile);
		try {
			P2PNode.serve(store,config,keyPair,node -> {
				Integer bound=node.getPort();
				informSuccess((bound!=null && bound>=0)
					? "P2P node listening on port "+bound : "P2P node started without a listener");
				cli().notifyStartup();
			});
		} catch (IOException | IllegalArgumentException e) {
			throw new CLIError(ExitCodes.CONFIG,"Failed to launch P2P node: "+e.getMessage(),e);
		}
	}

	/** The configuration file's settings, with options applied over them. */
	AMap<AString,ACell> settings() {
		AMap<AString,ACell> settings=Maps.empty();
		if (configFile!=null) {
			try {
				settings=ConfigUtils.readConfigFile(configFile);
			} catch (IOException e) {
				throw new CLIError(ExitCodes.CONFIG,"Cannot read configuration "+configFile+": "+e.getMessage(),e);
			}
		}
		if (port!=null) settings=settings.assoc(NodeConfig.PORT,CVMLong.create(port));
		if (publicUrl!=null && !publicUrl.isBlank()) settings=settings.assoc(NodeConfig.URL,Strings.create(publicUrl));
		if (seeds!=null) {
			ACell configured=settings.get(P2PNode.SEEDS);
			AVector<ACell> list=(configured==null)?Vectors.empty():RT.ensureVector(configured);
			if (list==null) throw new CLIError(ExitCodes.CONFIG,P2PNode.SEEDS+" in the configuration must be a list");
			for (String seed:seeds) list=list.conj(seedEntry(seed));
			settings=settings.assoc(P2PNode.SEEDS,list);
		}
		return settings;
	}

	/** A {@code --seed} option, {@code <key>@<url>}, as a configured seed entry. */
	static AMap<AString,ACell> seedEntry(String seed) {
		int at=seed.indexOf('@');
		if (at<=0 || at==seed.length()-1) {
			throw new CLIError(ExitCodes.USAGE,"--seed must be <key>@<url>: "+seed);
		}
		return Maps.of(Strings.create("key"),Strings.create(seed.substring(0,at).trim()),
			Strings.create("url"),Strings.create(seed.substring(at+1).trim()));
	}

	/** The key from --key, else the configured keypair, else null. */
	private AKeyPair resolveKeyPair(ACell configured) {
		String keySpec=keyMixin.getPublicKey();
		if (keySpec!=null && !keySpec.isBlank()) {
			AKeyPair keyPair=storeMixin.loadKeyFromStore(keySpec,() -> keyMixin.getKeyPassword());
			if (keyPair==null) throw new CLIError(ExitCodes.CONFIG,"Key not found in keystore: "+keySpec);
			return keyPair;
		}
		try {
			return P2PNode.keyPair(configured);
		} catch (IllegalArgumentException e) {
			throw new CLIError(ExitCodes.CONFIG,e.getMessage(),e);
		}
	}

	private static AStore createStore(AString storeFile) {
		try {
			if (storeFile==null) return EtchStore.createTemp("convex-p2p");
			File file=FileUtils.getFile(storeFile.toString());
			return EtchStore.create(file);
		} catch (IOException e) {
			throw new CLIError(ExitCodes.CONFIG,"Failed to open Etch store: "+storeFile,e);
		}
	}
}
