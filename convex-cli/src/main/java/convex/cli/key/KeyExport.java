package convex.cli.key;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.mixins.KeyMixin;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;


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
	description="Export a private key from the keystore. Use with caution.")
public class KeyExport extends AKeyCommand {

	private static final Logger log = LoggerFactory.getLogger(KeyExport.class);

	@Mixin
	protected KeyMixin keyMixin;

	
	@Option(names={"-o", "--output-file"},
			description="Write the private key to a new owner-only file. Use '-' to explicitly write to stdout. Required non-interactively; defaults to the attached console.")
	private String outputFilename;


	@Option(names={"--export-password"},
		description="Password for the exported key, if applicable")
    private char[] exportPassword;
	
	@Option(names={"--type"},
			description="Type of file exported. Supports: pem, seed (default).")
	private String type;
	
	
	
	private void ensureExportPassword() {
		if ((exportPassword==null)&&(cli().isInteractive())) {
			exportPassword=cli().readPassword("Enter passphrase for exported key: ");
		}

		if (exportPassword == null || exportPassword.length == 0) {

			if (cli().isParanoid()) {
				throw new CLIError("Strict security: attempting to export PEM with no passphrase.");
			} else {
				log.warn("No export passphrase '--export-password' provided: Defaulting to blank.");
			}
			exportPassword=new char[0];
		}
	}
	
	@Override
	public void execute() {
		String keystorePublicKey=keyMixin.getPublicKey();
		if ((keystorePublicKey == null)||(keystorePublicKey.isEmpty())) {
			if (!isInteractive()) {
				cli().inform("You must provide a --key parameter");
				showUsage();
				return;
			}

			keystorePublicKey=cli().prompt("Enter public key to export: ");
		}

		String publicKey = keystorePublicKey;
		AKeyPair keyPair = storeMixin.loadKeyFromStore(publicKey, () -> keyMixin.getKeyPassword());
		if (keyPair == null) {
			throw new CLIError("Key pair not found in keystore for: " + keystorePublicKey +
				". Use 'convex key list' to see available keys.");
		}
		
		// Raw seed is the canonical lossless export and remains the default.
		if (type==null) type="seed";
		
		String output;
		if ("pem".equals(type)) {
			ensureExportPassword();
			try {
				output = PEMTools.encryptPrivateKeyToPEM(keyPair, exportPassword);
			} catch (GeneralSecurityException e) {
				throw new CLIError("Cannot encrypt PEM",e);
			} finally {
				Arrays.fill(exportPassword, 'x');
			}
		} else if ("seed".equals(type)){
			String rawSeed = keyPair.getSeed().toHexString();
			output=rawSeed;
		} else {
			throw new CLIError("Export type not recognised: "+type);
		}

		try (SecretOutput secretOutput=SecretOutput.open(this,outputFilename,"--output-file","Private key")) {
			secretOutput.write(output,"Private key export ("+type+"):");
		}
	}



}
