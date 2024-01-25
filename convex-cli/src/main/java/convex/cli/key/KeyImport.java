package convex.cli.key;

import java.io.Console;
import java.security.PrivateKey;

import org.bouncycastle.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;


/**
 *
 * Convex key sub commands
 *
 *		convex.key.import
 *
 *
 */
@Command(name="import",
	description="Import key pairs to the keystore.")
public class KeyImport extends AKeyCommand {

	private static final Logger log = LoggerFactory.getLogger(KeyImport.class);

	@ParentCommand
	protected Key keyParent;

	@Option(names={"-i", "--import-file"},
		description="Import file for the the keypair.")
	private String importFilename;
	
	@Option(names={"-t", "--text"},
			description="Text string to import.")
		private String importText;

	@Option(names={"--import-password"},
		description="Password for the imported key.")
    private String importPassword;

	@Override
	public void run() {
		// Ensure importText is filled
		if (importFilename != null && importFilename.length() > 0) {
			if (importText!=null) throw new CLIError("Please provide either --import-file or --text, not both!");
			importText=cli().loadTextFile(importFilename);
		}
		if (importText == null || importText.length() == 0) {
			throw new CLIError("You need to provide '--text' or import filename '--import-file' to import a private key");
		}

		if (importPassword == null) {
			if (cli().isInteractive()) {
				importPassword=new String(System.console().readPassword("Enter import password:"));
			} else {
				throw new CLIError("--import-password not provided during non-interatice import");
			}
		}
		
		

		PrivateKey privateKey = PEMTools.decryptPrivateKeyFromPEM(importText, importPassword.toCharArray());
		AKeyPair keyPair = AKeyPair.create(privateKey);

		char[] storePassword=cli().getStorePassword();
		char[] keyPassword=cli().getKeyPassword();
		cli().addKeyPairToStore(keyPair,keyPassword);
		Arrays.fill(keyPassword, 'x');
		cli().saveKeyStore(storePassword);
		
		cli().println(keyPair.getAccountKey().toHexString());
	}
}
