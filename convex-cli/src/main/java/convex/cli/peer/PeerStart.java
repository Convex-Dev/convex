package convex.cli.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.cli.TraySupport;
import convex.cli.mixins.RemotePeerMixin;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.State;
import convex.core.data.AccountKey;
import convex.core.cvm.Address;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.cvm.Keywords;
import convex.core.init.Init;
import convex.core.store.AStore;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;
import convex.restapi.RESTServer;
import convex.gui.utils.TrayManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Start a Convex peer
 *
 */

@Command(
	name = "start",
	mixinStandardHelpOptions = true, 
	description = "Start a local peer.")
public class PeerStart extends APeerCommand {

	private static final Logger log = LoggerFactory.getLogger(PeerStart.class);

	@Option(names = {"--reset" },
			description = "Reset and delete the etch database if it exists. Default: ${DEFAULT-VALUE}")
	private boolean isReset;

	@Option(names = { "--peer-port" },
			description = "Local port for this peer to listen on (note: --port is the port of the remote sync source). If unspecified, 18888 will be used if available. If set to 0, will choose a random port.")
	private Integer port;

	@Option(names = { "--url" }, 
			description = "URL for the peer to set for other peers to use.")
	private String url;
	
	@Option(names = { "--base-url" }, 
			description = "Base URL for REST API / web access.")
	private String baseURL;

	
	@Option(names = { "--norest" }, description = "Disable REST server.")
	private boolean norest;

	@Option(names = { "--no-tray" }, description = "Disable the system tray icon.")
	private boolean noTray;
	
	@Option(names = { "--recalc" }, description = "Recalculate state from the specified block position onwards.")
	private Integer recalc;
	
	@Option(names = { "--genesis" }, 
			defaultValue = "${env:CONVEX_GENESIS_SEED}",
			description = "Governance seed for network genesis. For testing use only.")
	private String genesis;

	@Option(names = { "--protocol-version" },
			description = "Protocol version for a new network genesis (used with --genesis). Default: latest version supported by this release.")
	private Long protocolVersion;

	@Option(names = { "--api-port" },
			description = "Port for REST API. If unspecified, prefers port 8080.")
	private Integer apiport;

//	@Option(names = { "-b",
//			"--bind-address" }, description = "Bind address of the network interface. Defaults to local loop back device for %n"
//                    + "local peer, and if a public --url is set, then defaults to all network devices.")
//	private String bindAddress;

	@Mixin
	protected RemotePeerMixin peerMixin;

	@Option(names = { "-a", "--address" }, description = "Account address to use for the peer controller.")
	private String controllerAddress;
	
	AKeyPair findPeerKey(AStore store, HashMap<Keyword,Object> config) {
		String specifiedKey=peerKeyMixin.getPublicKey();
		Object configKeyPair=config.get(Keywords.KEYPAIR);
		if (specifiedKey!=null) {
			if (configKeyPair instanceof AKeyPair) return (AKeyPair)configKeyPair;
			throw new CLIError(ExitCodes.CONFIG,"Peer key not found in keystore: "+specifiedKey);
		}

		// Key pair from config file, if provided
		if (configKeyPair instanceof AKeyPair) {
			inform("Using peer key from config file");
			return (AKeyPair)configKeyPair;
		}

		log.debug("Peer key not explicitly configured, attempting to infer from store");
		try {
			List<AccountKey> peerList=API.listPeers(store);
			AKeyPair hintedKey=getHintedEtchKey();
			if ((hintedKey!=null)&&peerList.contains(hintedKey.getAccountKey())) {
				inform("Using peer key identified by the Etch publicKeyHint");
				return hintedKey;
			}

			// In strict mode, an authenticated Etch hint for an actual stored Peer is
			// sufficient; otherwise require an explicit peer identity.
			paranoia("--peer-key not specified");
			if (peerList.size()==0) {
				throw new CLIError(ExitCodes.CONFIG,"No peers configured in Etch store "+store+". Consider using `convex peer create` or `convex peer genesis` first.");
			} else if (peerList.size()>1) {
				throw new CLIError(ExitCodes.CONFIG,"Multiple peers configured in Etch store "+store+". specify which one you want with --peer-key.");
			}
			AccountKey peerKey=peerList.get(0);
			AKeyPair pkp=storeMixin.loadKeyFromStore(peerKey.toHexString(), ()->peerKeyMixin.getKeyPassword());
			return pkp;
		} catch (IOException e) {
			throw new CLIError(ExitCodes.IOERR,"Unable to read peer list from Etch store "+store,e);
		}
	}

	@Override
	public void execute() throws InterruptedException {
		Server server=null;

		storeMixin.ensureKeyStore();
		// Config file (if any) provides base values; explicit CLI options take precedence.
		HashMap<Keyword,Object> config=loadPeerConfig();
		AKeyPair genesisKey=null;
		if (genesis!=null&&(!genesis.isEmpty())) {
			// Resolve the test identity before opening Etch so a new encrypted file
			// receives the correct public-key hint.
			paranoia("Shouldn't use Genesis Seed in strict security mode! Consider key compromised!");
			Blob seed=Blob.parse(genesis);
			if ((seed==null)||(seed.count()!=32)) {
				throw new CLIError(ExitCodes.DATAERR,"Genesis seed must be a 32 byte hex blob");
			}
			genesisKey=AKeyPair.create(seed);
			setConfiguredPeerKey(config,genesisKey);
			informWarning("Using test genesis seed: "+seed);
		}
		try (AStore store = openPeerStore(config)) {

			AKeyPair peerKey;
			if (genesisKey!=null) {
				peerKey=genesisKey;
			} else {
				peerKey=findPeerKey(store,config);
				if (peerKey==null) {
					informWarning("No --peer-key specified or inferred from Etch Store "+store);
					showUsage();
					return;
				}
			}

			Address controller=Address.parse(controllerAddress);
			if (controller==null) {
				paranoia("--address for peer controller not specified");
				log.debug("Controller address not specified.");
			}

			RESTServer restServer=null;
			boolean trayInstalled=false;
			try {
				InetSocketAddress remoteSource=peerMixin.getSpecifiedSource();

				config.put(Keywords.KEYPAIR, peerKey);
				config.put(Keywords.STORE, store);
				if (url!=null) config.put(Keywords.URL, url);
				if (port!=null) config.put(Keywords.PORT, port);

				if (remoteSource!=null) {
					config.put(Keywords.SOURCE, remoteSource); // if remote source to sync with is specified
				} else if (!config.containsKey(Keywords.SOURCE)&&!config.containsKey(Keywords.RESTORE)) {
					// if no remote host to sync with, assume we want to restore existing peer
					config.put(Keywords.RESTORE,true);
				}
				if (recalc!=null) config.put(Keywords.RECALC, recalc);
				if (baseURL!=null) config.put(Keywords.BASE_URL, baseURL);

				// Pass the intended controller to peer launch. Note the authoritative
				// controller is the on-chain PeerStatus controller: see checkController
				if (controller!=null) config.put(Keywords.CONTROLLER, controller);

				if (genesisKey!=null) {
					if (remoteSource!=null) {
						throw new CLIError("--genesis option should not be used when syncing with remote source");
					}

					AccountKey gpk=genesisKey.getAccountKey();
					State state=Helpers.applyGenesisProtocol(Init.createState(gpk,gpk,List.of(gpk)),protocolVersion);
					informWarning("Created genesis State: "+state.getHash()+" at protocol version "+state.getProtocolVersion());
					config.put(Keywords.STATE, state);
				}
				server=API.launchPeer(config);

				if (controller!=null) checkController(server,controller);

				if (!norest) {
					restServer=RESTServer.create(server);
					restServer.start(apiport);
				}
				Server launchedServer=server;
				trayInstalled=TraySupport.installPeerTray(
					"Convex Peer on port "+server.getPort(),server,restServer,noTray,launchedServer::close);

				informSuccess("Peer started");
				cli().notifyStartup();
				server.waitForShutdown();
				inform("Peer shutdown completed");
			} catch (ConfigException t) {
				throw new CLIError(ExitCodes.CONFIG,"Error in peer configuration: "+t.getMessage(),t);
			} catch (LaunchException e) {
				throw new CLIError("Error launching peer: "+e.getMessage(),e);
			} finally {
				if (trayInstalled) TrayManager.remove();
				if (restServer!=null) restServer.close();
				if (server!=null) {
					server.close();
				}
			}
		}
	}

	/**
	 * Warns if the user-specified controller is unlikely to be able to control this peer.
	 * The authoritative controller is the on-chain PeerStatus controller: a different
	 * specified controller can only work if the on-chain controller is an actor
	 * (e.g. a trust monitor) that permits it.
	 */
	private void checkController(Server server, Address controller) {
		try {
			Address onChain=server.getPeerController();
			if (onChain==null) {
				informWarning("Peer has no on-chain controller yet: unable to verify --address "+controller);
			} else if (!controller.equals(onChain)) {
				AccountStatus as=server.getPeer().getConsensusState().getAccount(onChain);
				if ((as!=null)&&as.isActor()) {
					informWarning("--address "+controller+" is not the on-chain controller "+onChain
							+" (an actor). Peer control will only work if the actor (e.g. a trust monitor) permits it.");
				} else {
					informWarning("--address "+controller+" does not match on-chain peer controller "+onChain
							+". Peer control transactions are unlikely to work.");
				}
			}
		} catch (RuntimeException e) {
			log.debug("Unable to verify peer controller",e);
		}
	}


	
}
