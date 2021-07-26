package convex.cli;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Option;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;


/**
 *
 * Convex key sub commands
 *
 *		convex.key.export
 *
 *
 */
@Command(name="export",
	aliases={"ex"},
	mixinStandardHelpOptions=true,
	description="Export 1 or more key pairs from the keystore.")
public class KeyExport implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(KeyExport.class);

	@ParentCommand
	protected Key keyParent;

	@Option(names={"-i", "--index-key"},
		description="Keystore index of the public/private key to use for the peer.")

    private int[] keystoreIndex;

	@Option(names = {"--public-key" },
		description = "Hex string of the public key in the Keystore to use for the peer.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String[] keystorePublicKey ;

	@Option(names={"--export-password"},
		description="Password of the exported key.")
    private String exportPassword;


	@Override
	public void run() {
		// sub command to generate keys
		Main mainParent = keyParent.mainParent;

		if (keystoreIndex == null && keystorePublicKey == null) {
			log.warn("You need to provide at least on --index-key or --public-key parameter");
			return;
		}

		if (exportPassword == null || exportPassword.length() == 0) {
			log.warn("You need to provide an export password '--export-password' of the exported key");
			return;
		}

		try {
			int index = 0;
			int count = 0;
			if (keystoreIndex != null) {
				count = keystoreIndex.length;
			}
			if (keystorePublicKey != null ) {
				count = keystorePublicKey.length;
			}
			while (index < count) {
				String publicKey = null;
				int indexKey = 0;
				if (keystoreIndex != null) {
					indexKey = keystoreIndex[index];
				}
				if (keystorePublicKey != null) {
					publicKey = keystorePublicKey[index];
				}
				AKeyPair keyPair = mainParent.loadKeyFromStore(publicKey, indexKey);
				String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), exportPassword.toCharArray());

				mainParent.output.setField("index", String.format("%5d", index + 1));
				mainParent.output.setField("publicKey", keyPair.getAccountKey().toHexString());
				mainParent.output.setField("export", pemText);
				mainParent.output.addRow();

				index ++;
			}

		} catch (Error e) {
			mainParent.showError(e);
		}
	}
}
