package convex.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Option;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.crypto.PEMTools;


/**
 *
 * Convex key sub commands
 *
 *		convex.key.import
 *
 *
 */
@Command(name="import",
	aliases={"im"},
	mixinStandardHelpOptions=true,
	description="Import key pairs to the keystore.")
public class KeyImport implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(KeyImport.class);

	@ParentCommand
	protected Key keyParent;

	@Option(names={"-i", "--import-text"},
		description="Import format PEM text of the keypair.")
	private String importText;


	@Option(names={"-f", "--import-file"},
		description="Import file name of the keypair PEM file.")
	private String importFilename;

	@Option(names={"--import-password"},
		description="Password of the imported key.")
    private String importPassword;

	@Override
	public void run() {
		// sub command to generate keys
		Main mainParent = keyParent.mainParent;
		if (importFilename != null && importFilename.length() > 0) {
			try {
				importText = Files.readString(Paths.get(importFilename), StandardCharsets.UTF_8);
			} catch ( IOException e) {
				mainParent.showError(e);
				return;
			}
		}
		if (importText == null || importText.length() == 0) {
			log.warn("You need to provide an import text '--import' or import filename '--import-file' to import a private key");
			return;
		}

		if (importPassword == null || importPassword.length() == 0) {
			log.warn("You need to provide an import password '--import-password' of the imported encrypted PEM data");
		}

		try {
			PrivateKey privateKey = PEMTools.decryptPrivateKeyFromPEM(importText, importPassword.toCharArray());
			AKeyPair keyPair = Ed25519KeyPair.create(privateKey);
			mainParent.addKeyPairToStore(keyPair);
			mainParent.output.setField("public key", keyPair.getAccountKey().toHexString());

		} catch (Error e) {
			mainParent.showError(e);
		}
	}
}
