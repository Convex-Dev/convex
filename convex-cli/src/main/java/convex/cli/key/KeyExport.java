package convex.cli.key;

import java.io.IOException;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
import convex.cli.mixins.KeyMixin;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
import convex.core.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
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
	mixinStandardHelpOptions=false,
	description="Export a private key from the keystore. Use with caution.")
public class KeyExport extends AKeyCommand {

	private static final Logger log = LoggerFactory.getLogger(KeyExport.class);

	@ParentCommand
	protected Key keyParent;
	
	
	@Mixin
	protected KeyMixin keyMixin;

	
	@Option(names={"-o", "--output-file"},
			description="Output file for the private key. Use '-' for STDOUT (default).")
	private String outputFilename;


	@Option(names={"--export-password"},
		description="Password for the exported key, if applicable")
    private String exportPassword;
	
	@Option(names={"--type"},
			description="Type of file exported. Supports: pem, seed (default).")
	private String type;
	
	
	
	private void ensureExportPassword() {
		if ((exportPassword==null)&&(cli().isInteractive())) {
			exportPassword=new String(cli().readPassword("Enter passphrase for exported key: "));
		}
		
		if (exportPassword == null || exportPassword.length() == 0) {
			
			if (cli().isParanoid()) {
				throw new CLIError("Strict security: attempting to export PEM with no passphrase.");
			} else {
				log.warn("No export passphrase '--export-password' provided: Defaulting to blank.");
			}
			exportPassword="";
		}
	}
	
	@Override
	public void execute() {
		String keystorePublicKey=keyMixin.getPublicKey();
		if ((keystorePublicKey == null)||(keystorePublicKey.isEmpty())) {
			if (outputFilename==null) {
				cli().inform("You must provide a --key parameter");
				showUsage();
				return;
			}
			
			keystorePublicKey=cli().prompt("Enter public key to export: ");
		}

		String publicKey = keystorePublicKey;
		AKeyPair keyPair = storeMixin.loadKeyFromStore(publicKey,keyMixin.getKeyPassword());
		if (keyPair==null) {
			// TODO: maybe prompt?
			throw new CLIError("Key pair not found for key: "+keystorePublicKey);
		}
		
		// Default to "seed" type unless security is strict
		if (type==null) {
			if (cli().isParanoid()) throw new CLIError("Strict security: must specifiy key export type, e.g. --type=seed");
			type="seed";
		}
		
		String output;
		if ("pem".equals(type)) {
			ensureExportPassword();
			try {
				String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair, exportPassword.toCharArray());
				output=pemText;
			} catch (GeneralSecurityException e) {
				throw new CLIError("Cannot encrypt PEM",e);
			}
		} else if ("seed".equals(type)){
			paranoia("Raw seed export forbidden in strict mode.");
			String rawSeed = keyPair.getSeed().toHexString();
			output=rawSeed;
		} else {
			throw new CLIError("Export type not recognised: "+type);
		}

		if ((outputFilename==null)||("-".equals(outputFilename.trim()))) {
			println(output);
		} else {
			try {
				FileUtils.writeFileAsString(Paths.get(outputFilename),output);
			} catch (IOException e) {
				throw new CLIError("Failed to write output file: "+e.getMessage());
			}
		}
	}



}
