package convex.cli.key;

import java.io.IOException;

import org.bouncycastle.util.Arrays;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.PEMTools;
import convex.core.crypto.SLIP10;
import convex.core.data.Blob;
import convex.core.exceptions.BadFormatException;
import convex.core.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


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

	@Option(names={"-i", "--import-file"},
		description="Import file for the the keypair. Use '-' for STDIN.")
	private String importFilename;
	
	@Option(names={"-t", "--text"},
			description="Text string to import.")
		private String importText;
	
	@Option(names={"--passphrase"},
			description="Passphrase for BIP39 or encrypted PEM imported key")
	private char[] importPassphrase;
	
	@Option(names={"--type"},
			description="Type of file imported. Supports: pem, seed, bip39. Will attempt to autodetect unless strict security is enabled")
	private String type;
	
	@Option(names={"--path"},
			defaultValue=convex.core.Constants.DEFAULT_BIP39_PATH,
			description="Derivation path for SLIP-0010 when using BIP39. Default: ${DEFAULT-VALUE}")
	private String path;
	
	@Option(names = { "-p","--keypass" },
			defaultValue = "${env:CONVEX_KEY_PASSWORD}",
			description = "Key pair password for imported key. Can specify with CONVEX_KEY_PASSWORD.")
	protected char[] keyPassword;

	/**
	 * Import key pair
	 * @return Key pair, or null if cancelled
	 */
	public AKeyPair importKeyPair() {
		// Ensure importText is filled
		if (importFilename != null && importFilename.length() > 0) {
			if (importText!=null) throw new CLIError(ExitCodes.USAGE,"Please provide either --import-file or --text, not both!");
			try {
				importText=FileUtils.loadFileAsString(importFilename);
			} catch (IOException e ) {
				throw new CLIError("Unable to import key file",e);
			}
		}
		if (importText == null || importText.length() == 0) {
			showUsage();
			return null;
		}
		
		// Parse input as hex string, will be null if not parsed. For BIP39 is 64 bytes, Ed25519 32
		Blob hex=Blob.parse(importText.trim());
		if (type==null) {
			if (isParanoid()) {
				informError("Not permitted to infer key import type in strict mode");
				return null;
			}
			inform("No import type specified, attempting to auto-detect");

			if (hex!=null) {
				if (hex.count()==AKeyPair.SEED_LENGTH) {
					type="seed";
					inform("Detected type 'seed'");
				} else if (hex.count()==BIP39.SEED_LENGTH) {
					type="bip39";
					inform("Detected type 'bip39'");
				}
			} else if (importText.contains("-----BEGIN")) {
				type="pem";
				inform("Detected type 'pem'");
			} else if (importText.trim().contains(" ")) {
				// Multiple words, presumably a BIP39 mnemonic phrase
				type="bip39";
				inform("Detected type 'bip39' (mnemonic phrase)");
			}

			if (type==null) {
				throw new CLIError(ExitCodes.DATAERR,"Could not auto-detect import type. Specify with e.g. --type=seed, --type=bip39 or --type=pem");
			}
		}
		
		AKeyPair keyPair=null;
		if ("seed".equals(type)) {
			if (hex==null) throw new CLIError(ExitCodes.DATAERR,"'seed' import type requires a hex private key seed");
			if (hex.count()!=AKeyPair.SEED_LENGTH) throw new CLIError(ExitCodes.DATAERR,"32 byte hex Ed25519 seed expected as input");
			keyPair=AKeyPair.create(hex.toFlatBlob());
		} else if ("bip39".equals(type)) {
			if (hex==null) {
				// We attempt to interpret as BIP39 mnemonic
				if (importPassphrase==null) {
					importPassphrase=readPassword("Enter passphrase for imported BIP39 mnemonic: ");
				}
				// Note: BIP39.getSeed currently requires a String passphrase
				hex=BIP39.getSeed(importText, new String(importPassphrase));
			}
			keyPair=SLIP10.deriveKeyPair(hex, path);

		} else if ("pem".equals(type)) {
			if (importPassphrase==null) {
				importPassphrase=readPassword("Enter passphrase for imported PEM key: ");
			}

			try {
				keyPair = PEMTools.decryptPrivateKeyFromPEM(importText, importPassphrase);
			} catch (BadFormatException e) {
				throw new CLIError(ExitCodes.DATAERR,"Cannot decode PEM. File may be corrupt or wrong passphrase used.",e);
			}
		}
		if (importPassphrase!=null) Arrays.fill(importPassphrase, 'x');
		if (keyPair==null) throw new CLIError(ExitCodes.DATAERR,"Unable to import keypair of type '"+type+"'");
		return keyPair;
	}
	
	@Override
	public void execute() {
		// Import the key pair in requested format
		AKeyPair keyPair=importKeyPair();
		if (keyPair==null) return; // returning without failure, presumably usage to show or otherwise cancelled
		
		// Get password for key. Follows same policy as key generation
		if (keyPassword==null) {
			if (isInteractive()) {
				keyPassword=readPassword("Enter password for imported key: ");
			} else if (isParanoid()) {
				throw new CLIError(ExitCodes.USAGE,
					"Password required in strict security mode. Use --keypass or CONVEX_KEY_PASSWORD environment variable.");
			} else {
				informWarning("No password provided - using empty password for key encryption.");
				keyPassword = new char[0];
			}
		}

		// Finally write to store, creating it if necessary
		storeMixin.ensureKeyStore();

		storeMixin.addKeyPairToStore(keyPair,keyPassword);
		Arrays.fill(keyPassword, 'x');
		storeMixin.saveKeyStore();	
		println(keyPair.getAccountKey().toHexString());
	}
}
