package convex.cli;

import java.io.File;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.logging.Logger;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/*
	*  peer start command
	*
*/

@Command(name="start",
	mixinStandardHelpOptions=true,
	description="Starts one or more peer servers.")
public class PeerStart implements Runnable {

	private static final Logger log = Logger.getLogger(PeerStart.class.getName());

	@ParentCommand
	private Peer peerParent;

	@Parameters(paramLabel="index",
		description="Keystore index or public address to use for the peer.")
	private String keystoreIndex;


	@Override
	public void run() {

		Main mainParent = peerParent.mainParent;
		AKeyPair keyPair = null;
		int port = 0;
		int index = Integer.parseInt(keystoreIndex);

		String password = mainParent.getPassword();
		if (password == null) {
			log.severe("You need to provide a keystore password");
			return;
		}
		File keyFile = new File(mainParent.getKeyStoreFilename());
		try {
			if (!keyFile.exists()) {
				log.severe("Cannot find keystore file "+keyFile.getCanonicalPath());
				return;
			}
			KeyStore keyStore = PFXTools.loadStore(keyFile, password);

			if ( index < 1 || index > keyStore.size()) {
				log.severe("Keystore index is out of range");
				return;
			}
			int counter = 1;
			Enumeration<String> aliases = keyStore.aliases();

			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				if (counter == index) {
					System.out.println(alias);
					keyPair = PFXTools.getKeyPair(keyStore, alias, password);
					break;
				}
				counter ++;
			}
		} catch (Throwable t) {
			System.out.println("Cannot load key store "+t);
			t.printStackTrace();
		}

		if (keyPair==null) {
			log.severe("Cannot find key in keystore");
			return;
		}

		if (mainParent.getPort()!=0) {
			port = Math.abs(mainParent.getPort());
		}

		log.info("Starting peer");
		peerParent.launchPeer(keyPair, port);
		peerParent.waitForPeers();
	}
}
