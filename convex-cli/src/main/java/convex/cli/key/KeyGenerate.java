package convex.cli.key;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;

import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;


/**
 *
 * Convex key sub commands
 *
 *		convex.key.generate
 *
 *
 */
@Command(name="generate",
	aliases={"gen"},
	mixinStandardHelpOptions=true,
	description="Generate private key pair(s) in the currently configured keystore.")
public class KeyGenerate extends AKeyCommand {

	@Option(names="--count",
		defaultValue="" + Constants.KEY_GENERATE_COUNT,
		description="Number of keys to generate. Default: ${DEFAULT-VALUE}")
	private int count;
	
	@Option(names="--words",
			defaultValue="12",
			description="Number of words in BIP39 mnemonic. Default: ${DEFAULT-VALUE}")
		private int words;
	
	@Option(names={"--type"},
			defaultValue="bip39",
			description="Type of key generation. Supports random, bip39, entropy")
	private String type;
	
	@Option(names="--passphrase",
			description="BIP39 passphrase. If not provided, will be requested from user (or assumed blank in non-interactive mode).")
	private String passphrase;
	
	@Option(names = { "-p","--keypass" }, 
			defaultValue = "${env:CONVEX_KEY_PASSWORD}", 
			scope = ScopeType.INHERIT, 
			description = "Key pair password for generated key. Can specify with CONVEX_KEY_PASSWORD.")
	protected char[] keyPassword;

	private AKeyPair generateKeyPair() {	
		if ("bip39".equals(type)) {
			if (words<12) {
				paranoia("Can't use less than 12 BIP39 words in strict security mode");
			}
			
			String mnemonic=BIP39.createSecureMnemonic(words);
			inform("BIP39 mnemonic generated with "+words+" words:");
			inform(mnemonic);
			if (passphrase==null) {
				if (isInteractive()) {
					passphrase=new String(readPassword("Enter BIP39 passphrase: "));
				} else {
					paranoia("Passphrase must be explicity provided");
					passphrase="";
				}
			}
			if (passphrase.isBlank()) {
				paranoia("Cannot use an empty BIP39 passphrase for key generation with strict security");
			}
			Blob bipseed = BIP39.getSeed(mnemonic, passphrase);
			AKeyPair result= BIP39.seedToKeyPair(bipseed);
			return result;
		} else if ("random".equals(type)) {
			return AKeyPair.generate();
		} else if ("entropy".equals(type)) {
			if (!isInteractive()) throw new CLIError(ExitCodes.USAGE,"Entropy based genration requires interactive mode");
			inform("Press some random keys to generate entropy. Press ENTER to finish.");
			Hash h=Blob.createRandom(new SecureRandom(), 64).getContentHash();
			while (true) {
				try {
					int c=System.console().reader().read();
					ABlob entropy=Blobs.forLong(c).append(Blobs.forLong(System.currentTimeMillis()));
					h=h.append(entropy).getContentHash();
					informWarning(h.getContentHash().toHexString());
					if ((c=='\r')||(c=='\n')) break;
				} catch (IOException e) {
					throw new CLIError(ExitCodes.IOERR,"Unable to collect entropy");
				}
			}
			return AKeyPair.create(h.toFlatBlob());
		} else {
			throw new CLIError(ExitCodes.USAGE,"Unsupprted key generation type: "+type);
		}
	}
	
	@Override
	public void execute() {
		// check the number of keys to generate.
		if (count <= 0) {
			informWarning("No keys generated. Perhaps you want a positive --count ?");
			return;
		}
		
		for ( int index = 0; index < count; index ++) {
			AKeyPair kp=generateKeyPair();
			
            String publicKeyHexString =  kp.getAccountKey().toHexString();
			storeMixin.ensureKeyStore();
			
			inform("Generated key pair with public key: 0x"+kp.getAccountKey().toChecksumHex());

			if (keyPassword==null) {
				keyPassword=readPassword("Enter password for generated key: ");
			}
			
			storeMixin.addKeyPairToStore(kp, keyPassword); 
			println(publicKeyHexString); // Output generated public key		
			Arrays.fill(keyPassword, 'p');
		}
		storeMixin.saveKeyStore();
		informSuccess(count+ " key(s) generated and saved in store "+storeMixin.getStorePath());
	}
}
