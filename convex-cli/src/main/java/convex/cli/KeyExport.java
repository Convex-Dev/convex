package convex.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;


/**
 *
 * Convex key sub commands
 *
 *		convex.key.export
 *
 *
 */
@Command(name="export",
	mixinStandardHelpOptions=true,
	description="Export a key pair from the keystore to a PEM file.")
public class KeyExport implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(KeyExport.class);

	@ParentCommand
	protected Key keyParent;

	@Option(names = {"--public-key" },
		description = "Hex string of the public key in the Keystore to use for the peer.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey ;

	@Option(names={"--export-password"},
		description="Password of the exported key.")
    private String exportPassword;


	@Override
	public void run() {
		// sub command to generate keys
		Main mainParent = keyParent.mainParent;

		if (keystorePublicKey == null) {
			log.warn("You need to provide at least --public-key parameter");
			return;
		}

		if (exportPassword == null || exportPassword.length() == 0) {
			log.warn("You should provide an export password '--export-password' for the exported key");
			return;
		}

		String publicKey = keystorePublicKey;

		AKeyPair keyPair = mainParent.loadKeyFromStore(publicKey);
		String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), exportPassword.toCharArray());

		mainParent.println(pemText);
	}
}
