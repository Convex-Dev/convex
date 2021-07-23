package convex.cli;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;

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

	@Option(names={"-i", "--import"},
		description="Import format PEM text of the keypair.")
	private String importText;


	@Option(names={"-f", "--import-file"},
		description="Import file name of the keypair PEM file.")
	private String importFilename;

	@Option(names={"--key-password"},
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
			log.warn("You need to provide an import text '--import' or import filename '--import-file' to import a key");
			return;
		}

		if (importPassword == null || importPassword.length() == 0) {
			log.warn("You need to provide an import password '--key-password' of the imported key");
		}

		try {
			AKeyPair keyPair = PEMTools.readPEM(importText);
			mainParent.addKeyPairToStore(keyPair);
		} catch (Error | GeneralSecurityException e) {
			mainParent.showError(e);
		}
	}
}
