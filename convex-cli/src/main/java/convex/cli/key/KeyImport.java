package convex.cli.key;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;

import org.bouncycastle.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.Main;
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
	aliases={"im"},
	mixinStandardHelpOptions=true,
	description="Import key pairs to the keystore.")
public class KeyImport extends AKeyCommand {

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
		Main mainParent = cli();
		if (importFilename != null && importFilename.length() > 0) {
			Path path=Paths.get(importFilename);
			try {
				if (!path.toFile().exists()) {
					throw new CLIError("Import file does not exist: "+path);
				}
				importText = Files.readString(path, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new CLIError("Unable to read import file: "+path,e);
			}
		}
		if (importText == null || importText.length() == 0) {
			log.warn("You need to provide an import text '--import' or import filename '--import-file' to import a private key");
			return;
		}

		if (importPassword == null || importPassword.length() == 0) {
			log.warn("You need to provide an import password '--import-password' of the imported encrypted PEM data");
		}

		PrivateKey privateKey = PEMTools.decryptPrivateKeyFromPEM(importText, importPassword.toCharArray());
		AKeyPair keyPair = AKeyPair.create(privateKey);

		char[] keyPassword=mainParent.getKeyPassword();
		mainParent.addKeyPairToStore(keyPair,keyPassword);
		Arrays.fill(keyPassword, 'x');
		
		saveKeyStore();
		mainParent.println(keyPair.getAccountKey().toHexString());
	}
}
