package convex.cli.key;

import org.bouncycastle.util.Arrays;

import convex.cli.CLIError;
import convex.cli.util.CLIUtils;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.PEMTools;
import convex.core.data.ABlob;
import convex.core.data.Blobs;
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
	description="Import key pairs to the keystore. Can specify either a raw key with --text or --import-file")
public class KeyImport extends AKeyCommand {

	@ParentCommand
	protected Key keyParent;

	@Option(names={"-i", "--import-file"},
		description="Import file for the the keypair. Use '-' for STDIN.")
	private String importFilename;
	
	@Option(names={"-t", "--text"},
			description="Text string to import.")
		private String importText;
	
	@Option(names={"--passphrase"},
			description="Passphrase for BIP39 or encrypted PEM imported key")
	private String importPassphrase;
	
	@Option(names={"--type"},
			description="Type of file imported. Supports: pem, seed, bip39. Will attempt to autodetect unless strict security is enabled")
	private String type;

	@Override
	public void run() {
		// Ensure importText is filled
		if (importFilename != null && importFilename.length() > 0) {
			if (importText!=null) throw new CLIError("Please provide either --import-file or --text, not both!");
			importText=CLIUtils.loadFileAsString(importFilename);
		}
		if (importText == null || importText.length() == 0) {
			showUsage();
			return;
		}
		
		// Parse input as hex string, will be null if not parsed. For BIP39 is 64 bytes, Ed25519 32
		ABlob hex=Blobs.parse(importText.trim());
		if (type==null) {
			if (cli().isParanoid()) {
				cli().informError("Not permitted to infer key import type in strict mode");
			}
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
					hex=BIP39.getSeed(importText, importPassphrase);
				} catch (Exception e) {
					throw new CLIError("Error interpreting BIP39 seed",e);
				}
			}
			keyPair=BIP39.seedToKeyPair(hex.toFlatBlob());
		} else if ("pem".equals(type)) {
			if (importPassphrase==null) {
				importPassphrase=new String(cli().readPassword("Enter passphrase for imported PEM key: "));
			}
			
			try {
				keyPair = PEMTools.decryptPrivateKeyFromPEM(importText, importPassphrase.toCharArray());
			} catch (Exception e) {
				throw new CLIError("Cannot decode PEM. File may be corrupt or wrong passphrase used.",e);
			}
		}
		if (keyPair==null) throw new CLIError("Unable to import keypair");
		
		// Finally write to store
		char[] storePassword=cli().storeMixin.getStorePassword(cli());
		char[] keyPassword=cli().getKeyPassword();
		cli().storeMixin.addKeyPairToStore(cli(), keyPair,keyPassword);
		Arrays.fill(keyPassword, 'x');
		cli().storeMixin.saveKeyStore(storePassword);	
		cli().println(keyPair.getAccountKey().toHexString());
	}
}
