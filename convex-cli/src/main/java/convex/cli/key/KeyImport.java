package convex.cli.key;

import java.security.PrivateKey;

import org.bouncycastle.util.Arrays;

import convex.cli.CLIError;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.PEMTools;
import convex.core.data.ABlob;
import convex.core.data.Blobs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
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
	
	@Parameters(index = "0", arity = "0..1", description = "Type of file imported. Supports: pem, seed, bip39")
    private String type;

	@Override
	public void run() {
		// Ensure importText is filled
		if (importFilename != null && importFilename.length() > 0) {
			if (importText!=null) throw new CLIError("Please provide either --import-file or --text, not both!");
			importText=cli().loadFileAsString(importFilename);
		}
		if (importText == null || importText.length() == 0) {
			throw new CLIError("You need to provide '--text' or import filename '--import-file' to import a private key");
		}
		
		// Get import password
		if (importPassword == null) {
			if (cli().isInteractive()) {
				importPassword=new String(System.console().readPassword("Enter import password:"));
			} else {
				throw new CLIError("--import-password not provided during non-interatice import");
			}
		}
		
		// Parse input as hex string, will be null if not parsed. For BIP39 is 64 bytes, Ed25519 32
		ABlob hex=Blobs.parse(importText.trim());
		if (type==null) {
			cli().inform("No import file type specified, attempting to auto-detect");
			if (hex!=null) {
				if (hex.count()==AKeyPair.SEED_LENGTH) {
					type="seed";
					cli().inform("Detected type 'seed'");
				} else if (hex.count()==BIP39.SEED_LENGTH) {
					type="bip39";
				}
			}
		}
		
		AKeyPair keyPair=null;
		if ("seed".equals(type)) {
			if (hex==null) throw new CLIError("'seed' import type requires a hex private key seed");
			if (hex.count()!=AKeyPair.SEED_LENGTH) throw new CLIError("32 byte hex Ed25519 seed expected as input");
			keyPair=AKeyPair.create(hex.toFlatBlob());
		} else if ("bip39".equals(type)) {
			if (hex==null) {
				try {
					hex=BIP39.getSeed(importText, importPassword);
				} catch (Exception e) {
					throw new CLIError("Error interpreting BIP39 seed",e);
				}
			}
			keyPair=BIP39.seedToKeyPair(hex.toFlatBlob());
		} else if ("pem".equals(type)) {
			PrivateKey privateKey = PEMTools.decryptPrivateKeyFromPEM(importText, importPassword.toCharArray());
			keyPair = AKeyPair.create(privateKey);
		}
		if (keyPair==null) throw new CLIError("Unable to import keypair");
		
		// Finally write to store
		char[] storePassword=cli().getStorePassword();
		char[] keyPassword=cli().getKeyPassword();
		cli().addKeyPairToStore(keyPair,keyPassword);
		Arrays.fill(keyPassword, 'x');
		cli().saveKeyStore(storePassword);	
		cli().println(keyPair.getAccountKey().toHexString());
	}
}
