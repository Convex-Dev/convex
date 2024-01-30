package convex.cli.key;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
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
	mixinStandardHelpOptions=false,
	description="Export a key pair from the keystore to a PEM file.")
public class KeyExport extends AKeyCommand {

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
	
	@Parameters(
			index = "0", 
			arity = "0..1", 
			defaultValue="pem",
			description = "Type of file exported. Supports: pem, seed")
    private String type;
	
	private void ensureExportPAssword() {
		if (exportPassword == null || exportPassword.length() == 0) {
			log.warn("No export password '--export-password' provided: Defaulting to blank.");
			exportPassword="";
		}
	}
	
	@Override
	public void run() {
		if ((keystorePublicKey == null)||(keystorePublicKey.isEmpty())) {
			log.warn("You need to provide at least --public-key parameter");
			return;
		}

		String publicKey = keystorePublicKey;
		AKeyPair keyPair = cli().loadKeyFromStore(publicKey);
		if (keyPair==null) {
			// TODO: maybe prompt?
			throw new CLIError("Key pair not found for key: "+keystorePublicKey);
		}
		
		String output;
		if ("pem".equals(type)) {
			ensureExportPAssword();
			String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), exportPassword.toCharArray());
			output=pemText;
		} else if ("seed".equals(type)){
			cli().paranoia("Raw seed export forbidden in strict mode.");
			String rawSeed = keyPair.getSeed().toHexString();
			output=rawSeed;
		} else {
			throw new CLIError("Export type not recognised: "+type);
		}

		cli().println(output);
	}



}
